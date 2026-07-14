package com.amaze

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.content.Context

class MoviesdaProvider : MainAPI() {
    override var mainUrl = "https://moviesda33.com"
    override var name = "Moviesda"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay: Long = 100
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val indexUrl = "https://raw.githubusercontent.com/zoneofmakos/data/refs/heads/main/mdindex/combined.csv"
    private var searchIndex: List<Pair<String, String>>? = null

    companion object {
        var context: Context? = null
    }

    override val mainPage = mainPageOf(
        "$mainUrl/tamil-2026-movies/" to "Latest Releases",
        "$mainUrl/tamil-hd-movies/" to "Tamil HD Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = if (page == 1) {
            app.get(request.data).document
        } else {
            app.get(request.data + "?page=$page/").document
        }

        val home = document.select("main div.f").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun getTvType(href: String): TvType =
        if (
            href.contains("-tamil-movie/") ||
            href.contains("-tamil-movie-1/") ||
            href.contains("-tamil-movie-2/") ||
            href.contains("-tamil-movie-3/") ||
            href.contains("-tamil-movie-moviesda/") ||
            href.contains("-movie/")
        ) TvType.Movie else TvType.TvSeries

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.text()?.trim()
            ?: return null
        val href = "" + fixUrl(this.selectFirst("a")?.attr("href").toString())

        // Extract poster URL from href
        val posterLocation = href.substringAfter("$mainUrl/")
        val slug = posterLocation
            .replace("-tamil-movie/", "")
            .replace("-tamil-web-series/", "")
            .replace("-movie/", "")
            .replace("-tamil-movie-1/", "")
            .replace("-tamil-movie-2/", "")
            .replace("-tamil-movie-3/", "")
            .replace("-tamil-movie-moviesda/", "")
            .trim('/')
        val posterUrl = "$mainUrl/uploads/posters/$slug.webp"

        val type = getTvType(href)

        return newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
    }

    private suspend fun getSearchIndex(): List<Pair<String, String>> {
        searchIndex?.let { return it }

        val csv = app.get(indexUrl).text

        searchIndex = csv.lineSequence()
            .mapNotNull { line ->
                val comma = line.indexOf(',')
                if (comma == -1) return@mapNotNull null

                val title = line.substring(0, comma).trim()
                val href = line.substring(comma + 1).trim()

                title to fixUrl(href)
            }
            .toList()

        return searchIndex!!
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()

        return getSearchIndex()
            .asSequence()
            .filter { (title, _) ->
                title.lowercase().contains(q)
            }
            .sortedBy { (title, _) ->
                title.lowercase().indexOf(q)
            }
            .map { (title, href) ->
                val posterLocation = href.substringAfter("$mainUrl/")
                val slug = posterLocation
                    .replace("-tamil-movie/", "")
                    .replace("-tamil-web-series/", "")
                    .replace("-movie/", "")
                    .replace("-tamil-movie-1/", "")
                    .replace("-tamil-movie-2/", "")
                    .replace("-tamil-movie-3/", "")
                    .replace("-tamil-movie-moviesda/", "")
                    .trim('/')

                val posterUrl = "$mainUrl/uploads/posters/$slug.webp"

                newMovieSearchResponse(title, href, getTvType(href)) {
                    this.posterUrl = posterUrl
                }
            }
            .toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        var title = doc.selectFirst(".f > a")?.text()?.trim()

        if (title?.contains(Regex("""\(\d+x\d+\)""")) == true) {
            title = doc.selectFirst("main > div.line")?.text()?.trim() ?: "Movie"
        }

        val poster = fixUrlNull(doc.selectFirst(".movie-info-container > picture > img")?.attr("src"))
        val description = doc.selectFirst(".movie-info-container > .movie-synopsis")?.text()?.trim()

        val yearText = doc.selectFirst(".movie-info-container > ul > li:nth-child(1) > span")?.text()?.trim()
        val year: Int? = yearText?.let { text ->
            Regex("""\b(?:19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
        }

        val director = doc.selectFirst(".movie-info-container > ul > li:nth-child(2) > span")
            ?.text()?.trim()?.split(", ")?.map {
                ActorData(
                    Actor(it.trim()),
                    roleString = "Director",
                )
            }?: emptyList()
        val cast = doc.selectFirst(".movie-info-container > ul > li:nth-child(3) > span")
            ?.text()?.trim()?.split(", ")?.map {
                ActorData(
                    Actor(it.trim()),
                    roleString = "Cast",
                )
            }?: emptyList()
        val actors = director + cast

        val tags = doc.selectFirst(".movie-info-container > ul > li:nth-child(4) > span")
            ?.text()?.trim()?.split(", ")

        val score = doc.selectFirst(".movie-info-container > ul > li:nth-child(7) > span")
            ?.text()?.trim()?.substringBefore("/")?.toDoubleOrNull()

        val type = getTvType(url)

        if (type == TvType.Movie) {
            return newMovieLoadResponse("$title", url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.actors = actors
                this.tags = tags
                this.score = score.let { Score.from10(it) }
            }
        } else {
            val seasonQualitiesLink = fixUrlNull(
                doc.selectFirst(".f > a")?.attr("href")
            )
            val seasonQualitiesListDoc = app.get(seasonQualitiesLink!!).document

            val episodesLink = if (!seasonQualitiesListDoc.select(".mv-content").isEmpty()) {
                seasonQualitiesLink
            } else {
                fixUrlNull(
                    seasonQualitiesListDoc.selectFirst(".f > a")?.attr("href")
                )
            }

            var episodesListDoc = app.get(episodesLink!!).document

            val episodeElements = mutableListOf<Element>()
            var page = 1

            while (true) {
                val currentElements = episodesListDoc.select(".mv-content")

                if (currentElements.isEmpty()) {
                    break
                }

                episodeElements.addAll(currentElements)

                page++
                val nextPageLink = "$episodesLink?page=$page"

                try {
                    episodesListDoc = app.get(nextPageLink).document
                } catch (_: Exception) {
                    break
                }
            }

            val episodes = episodeElements
                .reversed().mapIndexedNotNull { count, episodeElement ->
                    val episodeTitle = "Episode ${count + 1}"
                    val episodeUrl = fixUrlNull(
                        episodeElement.selectFirst("a.coral")?.attr("href")
                    )
                    val posterUrl = fixUrlNull(
                        episodeElement.selectFirst(".tblimg > img")?.attr("src")
                    )

                    newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.season = 1
                        this.episode = count + 1
                        this.posterUrl = posterUrl
                        this.data = "" + episodeUrl
                    }
                }

            return newTvSeriesLoadResponse("$title", url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.actors = actors
                this.tags = tags
                this.score = score.let { Score.from10(it) }
            }
        }
    }

    private val qualityRegex = Regex("""\b\d{3,4}p(?:\s+[A-Za-z]+)?\b""")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("/download/")) { // Series Episode
            val sourcePairs = listOf(
                Pair("", data),
                Pair(" 1080", data.removeSuffix("/") + "-1080p-hd/")
            )

            for ((resolution, url) in sourcePairs) {
                val serversResolvePage1 = fixUrlNull(
                    app.get(url).document.selectFirst(".download > .dlink > a")
                    ?.attr("href")
                )
                val serversResolveDoc1 = app.get(serversResolvePage1!!).document

                val serversResolvePage2 = fixUrlNull(
                    serversResolveDoc1.selectFirst(".download > .dlink > a")?.attr("href")
                )

                if (serversResolvePage2 == null) {
                    continue
                }

                val serversResolveDoc2 = app.get(serversResolvePage2).document

                val link = fixUrlNull(
                    serversResolveDoc2.selectFirst(".download > .dlink > a")?.attr("href")
                )

                callback.invoke(
                    newExtractorLink(
                        source = "" + name,
                        name = "" + name,
                        url = link ?: "",
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = resolution.trim().toIntOrNull() ?: Qualities.Unknown.value
                    }
                )
            }
        }

        // Movie
        val doc = app.get(data).document

        val formatsDoc = if (
            doc.selectFirst(".f > a")
                ?.text()
                ?.trim()
                ?.contains(Regex(
                    """(?:Mp4(?:\s+\w+)?|\((?:\d+x\d+|\d{3,4}p(?:\s+\w+)?)\))""",
                    RegexOption.IGNORE_CASE
                )) == true
        ) {
            doc
        } else {
            val formatsPage = fixUrlNull(doc.selectFirst(".f > a")?.attr("href"))!!
            var page = app.get(formatsPage).document

            if (
                page.selectFirst(".f > a")
                    ?.text()
                    ?.trim()
                    ?.contains(Regex("""\((?:\d+x\d+|\d{3,4}p(?:\s+\w+)?)\)""")) != true // (720p), (640x360)
            ) {
                val nextPage = fixUrlNull(page.selectFirst(".f > a")?.attr("href"))!!
                page = app.get(nextPage).document
            }

            page
        }

        val qualitiesLinks = formatsDoc.select(".f > a")

        for (qualities in qualitiesLinks) {
            val quality = qualityRegex.find(qualities.text())?.value
            val qualitiesPage = fixUrlNull(qualities.attr("href"))
            val qualitiesDoc = app.get(qualitiesPage!!).document

            val filesList = qualitiesDoc.select(".mv-content")

            for (file in filesList) {
                val fileName = file.select("li:nth-child(1)").text().trim().replace("Moviesda.Mobi - ", "").replace(".mp4", "")

                if (fileName.contains("Sample")) continue

                val fileSize = file.select("li:nth-child(2)").text().trim().substringAfter("Size: ").trim()
                val serverPage = fixUrlNull(file.selectFirst("a.coral")?.attr("href"))
                val serversDoc = app.get(serverPage!!).document

                val serversResolvePage1 = fixUrlNull(serversDoc.selectFirst(".download > .dlink > a")?.attr("href"))
                val serversResolveDoc1 = app.get(serversResolvePage1!!).document

                val serversResolvePage2 = fixUrlNull(serversResolveDoc1.selectFirst(".download > .dlink > a")?.attr("href"))
                val serversResolveDoc2 = app.get(serversResolvePage2!!).document

                val link = fixUrlNull(serversResolveDoc2.selectFirst(".download > .dlink > a")?.attr("href"))

                callback.invoke(
                    newExtractorLink(
                        source = "$name ($fileSize)",
                        name = "$fileName ($fileSize)",
                        url = link ?: "",
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality?.substringBefore("p")?.toIntOrNull() ?: Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}
