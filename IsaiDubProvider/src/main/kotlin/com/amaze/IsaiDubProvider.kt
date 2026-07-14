package com.amaze

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.content.Context

class IsaiDubProvider : MainAPI() {
    override var mainUrl = "https://isaidub.ceo"
    override var name = "IsaiDub"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay: Long = 100
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        var context: Context? = null
    }

    override val mainPage = mainPageOf(
        "$mainUrl/tamil-2026-dubbed-movies/" to "Latest Releases",
        "$mainUrl/tamil-dubbed-web-series/" to "Tamil Dubbed Web Series",
        "$mainUrl/movie/hollywood-movies-in-english/" to "Hollywood Movies (English)",
        "$mainUrl/tamil-chinese-dubbed-movies/" to "Chinese Tamil Dubbed Movies",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = if (page == 1) {
            app.get(request.data).document
        } else {
            app.get(request.data + "?get-page=$page/").document
        }

        val home = document.select("div.f, div.folder").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun getTvType(href: String): TvType =
        if (
            href.contains("-tamil-dubbed-movie/")
        ) TvType.Movie else TvType.TvSeries

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.text()?.trim()
            ?: return null
        val href = "" + fixUrl(this.selectFirst("a")?.attr("href").toString())

        // Extract poster URL from href
        val posterLocation = href.substringAfter("$mainUrl/movie/")
        val slug = posterLocation
            .replace("-tamil-dubbed-movie/", "")
            .replace("-tamil-dubbed-web-series/", "")
            .trim('/')
        val posterUrl = "$mainUrl/uploads/posters/$slug.jpg"

        val type = getTvType(href)

        return newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("div.f > a, div.folder > a")?.text()
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
                doc.selectFirst("div.f > a, div.folder > a")?.attr("href")
            )
            val seasonQualitiesListDoc = app.get(seasonQualitiesLink!!).document

            val episodesLink = if (!seasonQualitiesListDoc.select(".mv-content").isEmpty()) {
                seasonQualitiesLink
            } else {
                fixUrlNull(
                    seasonQualitiesListDoc.selectFirst("div.f > a, div.folder > a")?.attr("href")
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
                val nextPageLink = "$episodesLink?get-page=$page"

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
            val serversResolvePage1 = fixUrlNull(
                app.get(data).document.selectFirst(".download > .dlink > a")
                ?.attr("href")
            )
            val serversResolveDoc1 = app.get(serversResolvePage1!!).document

            val serversResolvePage2 = fixUrlNull(
                serversResolveDoc1.selectFirst(".download > .dlink > a")?.attr("href")
            )

            val serversResolveDoc2 = app.get(serversResolvePage2!!).document

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
                    this.quality = 720
                }
            )

            callback.invoke(
                newExtractorLink(
                    source = "" + name,
                    name = "" + name,
                    url = "" + link?.removeSuffix(".mp4") + "1080p_HD_.mp4",
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = 1080
                }
            )

            return true
        }

        // Movie
        val doc = app.get(data).document

        val formatsPage = fixUrlNull(doc.selectFirst("div.f > a, div.folder > a")?.attr("href"))
        val formatsDoc = app.get(formatsPage!!).document

        val qualitiesLinks = formatsDoc.select("div.f > a, div.folder > a")

        for (qualities in qualitiesLinks) {
            val quality = qualityRegex.find(qualities.text())?.value
            val qualitiesPage = fixUrlNull(qualities.attr("href"))
            val qualitiesDoc = app.get(qualitiesPage!!).document

            val serversList = qualitiesDoc.select(".mv-content")

            for (server in serversList) {
                val fileSize = server.select("li:nth-child(2)").text().trim().substringAfter("Size: ").trim()
                val serverPage = fixUrlNull(server.selectFirst("a.coral")?.attr("href"))
                val serversDoc = app.get(serverPage!!).document

                val serversResolvePage1 = fixUrlNull(serversDoc.selectFirst(".download > .dlink > a")?.attr("href"))
                val serversResolveDoc1 = app.get(serversResolvePage1!!).document

                val serversResolvePage2 = fixUrlNull(serversResolveDoc1.selectFirst(".download > .dlink > a")?.attr("href"))
                val serversResolveDoc2 = app.get(serversResolvePage2!!).document

                val link = fixUrlNull(serversResolveDoc2.selectFirst(".download > .dlink > a")?.attr("href"))

                callback.invoke(
                    newExtractorLink(
                        source = "$name ($fileSize)",
                        name = "$name ($fileSize)",
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
