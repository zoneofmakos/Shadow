package com.telegram

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.drinkless.tdlib.TdApi

class TeleflixProvider : MainAPI() {
    override var mainUrl = "https://v3-cinemeta.strem.io"
    override var name = "Teleflix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/catalog/movie/top.json" to "Top Movies",
        "$mainUrl/catalog/series/top.json" to "Top TV Shows"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val skip = (page - 1) * 50
        val url = if (page == 1) request.data else request.data.replace(".json", "/skip=$skip.json")

        val response = try { app.get(url).text } catch (e: Exception) { return null }
        val catalog = try { parseJson<CinemetaCatalog>(response) } catch (e: Exception) { return null }

        val items = catalog.metas.map { meta ->
            val isMovie = meta.type == "movie"
            newMovieSearchResponse(meta.name, "${meta.type}/${meta.id}", if (isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = meta.poster
            }
        }

        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.replace(" ", "%20")
        val moviesUrl = "$mainUrl/catalog/movie/top/search=$encoded.json"
        val seriesUrl = "$mainUrl/catalog/series/top/search=$encoded.json"

        val moviesResponse = app.get(moviesUrl).text
        val seriesResponse = app.get(seriesUrl).text

        val movies = parseJson<CinemetaCatalog>(moviesResponse).metas
        val series = parseJson<CinemetaCatalog>(seriesResponse).metas

        val all = (movies + series).map { meta ->
            val isMovie = meta.type == "movie"
            newMovieSearchResponse(meta.name, "${meta.type}/${meta.id}", if (isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = meta.poster
            }
        }

        return all
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("/").filter { it.isNotEmpty() }
        val id = parts.last()
        var type = if (parts.size > 1) parts[parts.size - 2] else id

        if (type == id || (type != "movie" && type != "series")) {
            // Backward compatibility for old bookmarks without type prefix
            val checkUrl = "$mainUrl/meta/series/$id.json"
            val checkMeta = try { parseJson<CinemetaMetaResponse>(app.get(checkUrl).text).meta } catch (e: Exception) { null }
            type = if (checkMeta != null && checkMeta.type == "series") "series" else "movie"
        }

        val metaUrl = "$mainUrl/meta/$type/$id.json"
        val metaResponse = app.get(metaUrl).text
        val meta = parseJson<CinemetaMetaResponse>(metaResponse).meta

        if (meta == null) throw ErrorLoadingException("Failed to load metadata")

        val isSeries = meta.type == "series"

        if (isSeries) {
            val episodes = meta.videos?.map { video ->
                val season = video.season ?: 1
                val ep = video.episode ?: 1
                // We pass a custom data string to loadLinks containing the show name and episode
                val epCode = "S${season.toString().padStart(2, '0')}E${ep.toString().padStart(2, '0')}"
                val data = "${meta.id}|${meta.name} $epCode"

                newEpisode(video.title ?: "Episode $ep") {
                    this.name = video.title ?: "Episode $ep"
                    this.data = data
                    this.season = season
                    this.episode = ep
                    this.posterUrl = video.thumbnail ?: meta.poster
                }
            } ?: emptyList()

            return newTvSeriesLoadResponse(meta.name, url, TvType.TvSeries, episodes) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = meta.description
                this.year = meta.year?.toIntOrNull()
            }
        } else {
            val dataString = "${meta.id}|${meta.name}"

            return newMovieLoadResponse(meta.name, url, TvType.Movie, dataString) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = meta.description
                this.year = meta.year?.toIntOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!TelegramRepository.waitUntilAuthenticated()) {
            throw ErrorLoadingException("Please login to Telegram in settings first!")
        }

        // ── Parse the data string ──────────────────────────────────────
        val imdbId: String?
        val searchTitle: String

        if (data.contains("|")) {
            imdbId = data.substringBefore("|")
            searchTitle = data.substringAfter("|")
        } else {
            imdbId = null
            searchTitle = data
        }

        val results = mutableSetOf<TelegramVideoMessage>()

        // ── Step 1: Search by IMDB ID in captions ─────────────────────
        if (imdbId != null) {
            // {1,4} for episode — handles ep 1 to ep 9999
            val episodeMatch = Regex("(?i)S(\\d{1,2})E(\\d{1,4})").find(searchTitle)

            if (episodeMatch != null) {
                val s = episodeMatch.groupValues[1].toInt()
                val e = episodeMatch.groupValues[2].toInt()
                val epCode = episodeMatch.value.uppercase()  // "S01E01" or "S01E100"

                // Exact: "tt0903747 S01E01" — only this specific episode
                val exactResults = TelegramRepository.searchVideoMessagesByCaption(
                    "$imdbId $epCode", limit = 20
                )
                results.addAll(exactResults)

                // Broad: "tt0903747" with limit 200, then filter by episode in filename
                if (results.isEmpty()) {
                    val broadResults = TelegramRepository.searchVideoMessagesByCaption(
                        imdbId, limit = 200
                    )
                    val filtered = broadResults.filter { msg ->
                        // check filename first, then caption
                        val se = TelegramSearchMatcher.extractSeasonEpisodePublic(msg.fileName)
                            ?: TelegramSearchMatcher.extractSeasonEpisodePublic(msg.caption)
                        se?.first == s && se.second == e
                    }
                    results.addAll(filtered)
                }
            } else {
                // Movie: search by IMDB ID only
                val idResults = TelegramRepository.searchVideoMessagesByCaption(imdbId, limit = 20)
                results.addAll(idResults)
            }
        }

        // ── Step 2: Fallback filename queries if ID search empty ───────
        if (results.isEmpty()) {
            val queries = mutableSetOf<String>()
            queries.add(searchTitle)

            // {1,4} for episode here too
            val sxxEyyRegex = Regex("(?i)S(\\d{1,2})E(\\d{1,4})")
            val match = sxxEyyRegex.find(searchTitle)
            if (match != null) {
                val s = match.groupValues[1].toInt()
                val e = match.groupValues[2].toInt()
                val baseName = searchTitle.substring(0, match.range.first).trim()
                val sStr = String.format("%02d", s)
                val eStr = String.format("%02d", e)
                queries.add("$baseName ${s}x$eStr")
                queries.add("$baseName ${s}x$e")
                queries.add("$baseName S$sStr E$eStr")
                queries.add("$baseName Season $s Episode $e")
                queries.add("$baseName S$s E$e")
                queries.add(baseName)
                queries.add(baseName.replace(" ", ""))
            }

            val punctRegex = Regex("[^a-zA-Z0-9 ]")
            for (q in queries.toList()) {
                if (punctRegex.containsMatchIn(q)) {
                    queries.add(q.replace(punctRegex, " ").replace(Regex(" +"), " ").trim())
                    queries.add(q.replace(punctRegex, ""))
                }
            }

            for (q in queries) {
                val res = TelegramRepository.searchVideoMessages(
                    q, limit = 30, excludeImdbTagged = true
                )
                results.addAll(res)
            }
        }

        if (results.isEmpty()) {
            throw ErrorLoadingException("No streams found on Telegram for '$searchTitle'")
        }

        results.forEach { msg ->
            // Use fresh fileId to avoid stale IDs after TDLib session restarts
            val freshFileId = TelegramRepository.getFreshFileId(msg.chatId, msg.messageId) ?: msg.fileId
            val streamUrl = TelegramRepository.getStreamUrl(freshFileId, msg.fileName, msg.fileSize)
            val sizeStr = TelegramProvider.formatBytes(msg.fileSize)
            callback.invoke(
                newExtractorLink(
                    source = "Telegram",
                    name = "${msg.fileName} ($sizeStr)",
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = ""
                    this.quality = getQualityFromName(msg.fileName)
                }
            )
        }

        return true
    }

    private fun getQualityFromName(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    // Data classes for Cinemeta API
    private data class CinemetaCatalog(val metas: List<CinemetaMeta> = emptyList())
    private data class CinemetaMetaResponse(val meta: CinemetaMeta?)

    private data class CinemetaMeta(
        val id: String,
        val type: String?,
        val name: String,
        val poster: String?,
        val background: String?,
        val description: String?,
        val year: String?,
        val videos: List<CinemetaVideo>? = null
    )

    private data class CinemetaVideo(
        val id: String,
        val title: String?,
        val season: Int?,
        val episode: Int?,
        val thumbnail: String?
    )
}
