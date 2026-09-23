package com.telegram

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import org.json.JSONObject
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

        val imdbId = meta.id.takeIf { it.startsWith("tt") }
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie
        val tmdbId = if (!imdbId.isNullOrBlank()) getTmdbIdFromImdb(imdbId, tvType) else null
        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = tvType,
            tmdbId = tmdbId,
            appLangCode = "en"
        ) ?: meta.logo
        val score = meta.imdbRating?.let { runCatching { Score.from10(it) }.getOrNull() }
        val tags = meta.genres ?: emptyList()
        val actors = meta.cast?.map { ActorData(Actor(it)) } ?: emptyList()
        val duration = parseDuration(meta.runtime)

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
                    this.description = video.overview ?: video.description
                    video.released?.takeIf { it.isNotBlank() }?.also { addDate(it) }
                }
            } ?: emptyList()

            return newTvSeriesLoadResponse(meta.name, url, TvType.TvSeries, episodes) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = meta.description
                this.year = meta.year?.toIntOrNull()
                this.score = score
                this.tags = tags
                this.actors = actors
                this.duration = duration
                try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                addImdbId(imdbId)
                addTMDbId(tmdbId?.toString())
            }
        } else {
            val dataString = "${meta.id}|${meta.name}"

            return newMovieLoadResponse(meta.name, url, TvType.Movie, dataString) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = meta.description
                this.year = meta.year?.toIntOrNull()
                this.score = score
                this.tags = tags
                this.actors = actors
                this.duration = duration
                try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                addImdbId(imdbId)
                addTMDbId(tmdbId?.toString())
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
        val logo: String? = null,
        val description: String?,
        val year: String?,
        val genres: List<String>? = null,
        val runtime: String? = null,
        val cast: List<String>? = null,
        val imdbRating: String? = null,
        val videos: List<CinemetaVideo>? = null
    )

    private data class CinemetaVideo(
        val id: String,
        val title: String?,
        val season: Int?,
        val episode: Int?,
        val thumbnail: String?,
        val overview: String? = null,
        val description: String? = null,
        val released: String? = null
    )
}

// metadata helpers

private suspend fun getTmdbIdFromImdb(imdbId: String, type: TvType): Int? {
    return try {
        val url = "https://api.themoviedb.org/3/find/$imdbId" +
            "?api_key=1865f43a0549ca50d341dd9ab8b29f49&external_source=imdb_id"
        val json = JSONObject(app.get(url).text)
        val key = if (type == TvType.TvSeries) "tv_results" else "movie_results"
        val results = json.optJSONArray(key)
        if (results != null && results.length() > 0)
            results.getJSONObject(0).optInt("id", -1).takeIf { it != -1 }
        else null
    } catch (_: Exception) { null }
}

private fun parseDuration(runtime: String?): Int? {
    if (runtime.isNullOrBlank()) return null
    val h = Regex("(\\d+)\\s*h").find(runtime)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val m = Regex("(\\d+)\\s*m").find(runtime)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val total = h * 60 + m
    return if (total > 0) total else runtime.filter { it.isDigit() }.toIntOrNull()
}

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String, apiKey: String,
    type: TvType, tmdbId: Int?, appLangCode: String?
): String? {
    if (tmdbId == null) return null
    val url = if (type == TvType.Movie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"
    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null
    val lang = appLangCode?.trim()?.lowercase()
    fun path(o: org.json.JSONObject) = o.optString("file_path")
    fun isSvg(o: org.json.JSONObject) = path(o).endsWith(".svg", true)
    fun urlOf(o: org.json.JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"
    var svgFallback: org.json.JSONObject? = null
    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (path(logo).isBlank()) continue
        val l = logo.optString("iso_639_1").trim().lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }
    var best: org.json.JSONObject? = null
    var bestSvg: org.json.JSONObject? = null
    fun voted(o: org.json.JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0
    fun better(a: org.json.JSONObject?, b: org.json.JSONObject): Boolean {
        if (a == null) return true
        return b.optDouble("vote_average", 0.0) > a.optDouble("vote_average", 0.0) ||
               (b.optDouble("vote_average", 0.0) == a.optDouble("vote_average", 0.0) &&
                b.optInt("vote_count", 0) > a.optInt("vote_count", 0))
    }
    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (!voted(logo)) continue
        if (isSvg(logo)) { if (better(bestSvg, logo)) bestSvg = logo }
        else { if (better(best, logo)) best = logo }
    }
    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }
    return null
}
