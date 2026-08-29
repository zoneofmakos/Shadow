package com.shadow

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.UUID

import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.runBlocking

data class SourceStream(
    val name: String,
    val url:  String,
    val type: String,           // "m3u" | "m3u8" → M3U parser   "json" → JSON parser
    val ua:   String?,
)

data class Channel(
    val name:    String,
    val logo:    String?,
    val group:   String?,
    val sources: MutableList<ChannelSource> = mutableListOf()
)

data class ChannelSource(
    val mpd_url:          String?,
    val m3u8_url:         String?,
    val license_url:      String?,
    val headers:          Map<String, String>?, // includes User-Agent
    val sourceStreamName: String?               // player label
)

/** Deserialization-only DTO for CloudPlay channel lists. Not used outside [parseCloudPlayChannels]. */
data class CloudPlayChannel(
    val id:          String?,
    val name:        String?,
    val group:       String?,
    val logo:        String?,
    val user_agent:  String?,
    val type:        String?,
    val mpd_url:     String?,
    val m3u8_url:    String?,
    val license_url: String?,
    val headers:     Map<String, String>?,
    val expires_in:  Any?
)

data class PremiumPlugChannel(
    val id: String?,
    val key: String?,
    val url: String?,
    val logo: String?,
    val name: String?,
    val keyId: String?,
    val category: String?,
    val cookie: String?
)

class ShadowTV(
    val streams: List<SourceStream> = emptyList()
) : MainAPI() {
    override var name = "Shadow TV"
    override var lang = "ta"

    override val hasMainPage          = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.Live)

    private val client = OkHttpClient()
    private val channelCache = mutableMapOf<String, Channel>()

    /**
     * Populate [channelCache] from every declared [SourceStream].
     * If a channel name already exists, sources are **appended** — never replaced.
     * Guard prevents double-fetch on warm cache.
     */
    private suspend fun ensureCachePopulated() {
        if (channelCache.isNotEmpty()) return
        streams.forEach { src -> fetchAndAppend(src) }
    }

    private suspend fun fetchAndAppend(src: SourceStream) {
        val url  = src.url
        val type = src.type
        val text = try {
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent", src.ua ?: "okhttp/4.12.0"
                )
                .build()

            client.newCall(request).execute().use { response ->
                    response.body?.string()?.trim() ?: return
                }
        } catch (_: Exception) { return }

        if (text.isBlank()) return

        val parsed: List<Channel> = when (type) {
            "m3u", "m3u8" -> parseM3u(text, src.name, src.ua)
            "cloudplay"   -> parseCloudPlayChannels(text, src.name, src.ua)
            "premiumplug" -> parsePremiumPlugChannels(text, src.name, src.ua)
            else          -> emptyList()
        }

        // Merge: append sources to existing entry, or insert a new one
        parsed.forEach { ch ->
            val key = ch.name.trim().lowercase()
            channelCache[key]
                ?.sources
                ?.addAll(ch.sources)
                ?: run { channelCache[key] = ch }
        }
    }

    private val homeUrl = "https://raw.githubusercontent.com/zoneofmakos/data/main/home.json"
    private val homeSections = runBlocking {
        parseJson<Map<String, List<String>>>(
            app.get(homeUrl).text.trim()
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureCachePopulated()

        val sections = homeSections.mapNotNull { (sectionName, channelNames) ->
            val items = channelNames.mapNotNull { name ->
                channelCache[name.trim().lowercase()]
            }.map { ch ->
                newLiveSearchResponse(ch.name, ch.toJson(), TvType.Live) {
                    posterUrl = ch.logo ?: ""
                }
            }

            if (items.isEmpty()) null
            else HomePageList(sectionName, items, isHorizontalImages = true)
        }

        return newHomePageResponse(sections, hasNext = false)
    }

    // ── 2. search ─────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        ensureCachePopulated()

        return channelCache.values
            .filter { it.name.contains(query, ignoreCase = true) }
            .map    { ch ->
                newLiveSearchResponse(ch.name, ch.toJson(), TvType.Live) {
                    posterUrl = ch.logo ?: ""
                }
            }
    }

    // ── 3. load ───────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val ch = parseJson<Channel>(url)
        return newLiveStreamLoadResponse(ch.name, url, url) {
            posterUrl = ch.logo ?: ""
            plot      = ch.group
        }
    }

    // ── 4. loadLinks ──────────────────────────────────────────────────────────
    // Iterates every ChannelSource; each callback gets the *source's* stream name.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ch = parseJson<Channel>(data)

        ch.sources.forEachIndexed { index, source ->
            // Label shown in the player source selector = the SourceStream that owns this source
            val urlSuffix = "#_src$index" // for deduplication of multiple sources with the same URL
            val sourceName = source.sourceStreamName ?: this.name
            val headers    = (source.headers ?: emptyMap()).toMutableMap()

            when {
                // ── DASH / DRM ────────────────────────────────────────────────
                source.mpd_url != null -> {
                    val licUrl = source.license_url ?: ""
                    var kid = ""; var key = ""

                    // fetch MPD upfront; capture any Set-Cookie for the player
                    val mpdResponse = app.get(source.mpd_url, headers = headers)
                    val mpdText     = mpdResponse.text
                    mpdResponse.headers.values("set-cookie")
                        .mapNotNull { it.split(";").firstOrNull()?.trim() }
                        .filter     { it.contains("=") }
                        .takeIf     { it.isNotEmpty() }
                        ?.let { cookies ->
                            val existing = headers["cookie"]?.let { "$it; " } ?: ""
                            headers["cookie"] = existing + cookies.joinToString("; ")
                        }

                    when {
                        // JWK set from M3U — fetch MPD for actual KID, match against key list
                        licUrl.startsWith("jwks:") -> {
                            val jwksJson = licUrl.removePrefix("jwks:")
                            val rawKid   = Regex("""cenc:default_KID=["']([0-9a-fA-F\-]{36})["']""")
                                .find(mpdText)?.groupValues?.get(1)
                            if (rawKid != null) {
                                val b64Kid = rawKid.hexToBase64Url()
                                @Suppress("UNCHECKED_CAST")
                                val matched = (parseJson<Map<String, Any>>(jwksJson)["keys"]
                                    as? List<Map<String, String>>)
                                    ?.firstOrNull { it["kid"] == b64Kid }
                                kid = matched?.get("kid") ?: ""
                                key = matched?.get("k")   ?: ""
                            }
                        }
                        // Inline hex keys embedded in a dummy URL: keyid=<hex>&key=<hex>
                        // Produced by the M3U parser for type2 (#KODIPROP hex:hex) and
                        // by CP JSON (sony-token URL already has keyid/key params).
                        licUrl.contains("keyid=") && licUrl.contains("key=") -> {
                            kid = Regex("keyid=([^&]+)").find(licUrl)
                                ?.groupValues?.get(1)?.hexToBase64Url() ?: ""
                            key = Regex("key=([^&]+)").find(licUrl)
                                ?.groupValues?.get(1)?.hexToBase64Url() ?: ""
                        }
                        // Remote licence server — fetch MPD to extract KID, then POST
                        licUrl.isNotEmpty() -> {
                            val rawKid  = Regex("""cenc:default_KID=["']([0-9a-fA-F\-]{36})["']""")
                                .find(mpdText)?.groupValues?.get(1)
                                ?: UUID.randomUUID().toString()
                            kid = rawKid.hexToBase64Url()
                            key = getDRMKeysFromLicenseServer(licUrl, kid)
                        }
                    }

                    callback.invoke(
                        newDrmExtractorLink(
                            sourceName,
                            sourceName,
                            source.mpd_url + urlSuffix,
                            INFER_TYPE,
                            if (kid.isNotEmpty() && key.isNotEmpty()) CLEARKEY_UUID
                            else UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
                        ) {
                            this.headers = headers
                            if (kid.isNotEmpty() && key.isNotEmpty()) {
                                this.kid = kid
                                this.key = key
                            } else if (licUrl.isNotEmpty()) {
                                this.licenseUrl = licUrl
                            }
                        }
                    )
                }

                // ── HLS / plain ───────────────────────────────────────────────
                source.m3u8_url != null -> {
                    val isTs = source.m3u8_url.contains(".ts", ignoreCase = true)
                    callback.invoke(
                        newExtractorLink(
                            sourceName,
                            sourceName,
                            source.m3u8_url + urlSuffix,
                            if (isTs) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                        ) {
                            this.headers = headers
                            headers.entries
                                .firstOrNull { it.key.equals("referer", ignoreCase = true) }
                                ?.let { this.referer = it.value }
                        }
                    )
                }
            }
        }
        return true
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    /**
     * Deserialise a JSON channel list into [Channel] / [ChannelSource] objects.
     * [CloudPlayChannel] is only referenced **here** — nowhere else.
     *
     * user_agent is merged into headers["User-Agent"] so loadLinks sees one map.
     */
    private fun parseCloudPlayChannels(text: String, sourceStreamName: String?, sourceUA: String?): List<Channel> = try {
        val defaultUA = sourceUA ?: "okhttp/4.12.0"
        parseJson<List<CloudPlayChannel>>(text).mapNotNull { raw ->
            val channelName = raw.name?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            val hdrs = mutableMapOf<String, String>()
            raw.headers?.let { hdrs.putAll(it) }
            hdrs["User-Agent"] = raw.user_agent?.takeIf { it.isNotEmpty() } ?: defaultUA

            Channel(
                name    = channelName,
                logo    = raw.logo,
                group   = raw.group?.takeIf { it.isNotEmpty() },
                sources = mutableListOf(
                    ChannelSource(
                        mpd_url          = raw.mpd_url,
                        m3u8_url         = raw.m3u8_url,
                        license_url      = raw.license_url,
                        headers          = hdrs,
                        sourceStreamName = sourceStreamName
                    )
                )
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun parsePremiumPlugChannels(
        text: String,
        sourceStreamName: String?,
        sourceUA: String?
    ): List<Channel> = try {

        val defaultUA = sourceUA ?: "okhttp/4.12.0"

        parseJson<List<PremiumPlugChannel>>(text).mapNotNull { raw ->

            val channelName = raw.name?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val streamUrl = raw.url?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val headers = mutableMapOf<String, String>()

            headers["User-Agent"] = defaultUA

            raw.cookie
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    headers["Cookie"] = it
                }

            val isDash = streamUrl.contains(".mpd", ignoreCase = true)

            Channel(
                name = channelName,
                logo = raw.logo,
                group = raw.category?.takeIf { it.isNotBlank() },
                sources = mutableListOf(
                    ChannelSource(
                        mpd_url = if (isDash) streamUrl else null,
                        m3u8_url = if (!isDash) streamUrl else null,
                        license_url = if (isDash && !raw.keyId.isNullOrBlank() && !raw.key.isNullOrBlank())
                            "https://dummy.ck/?keyid=${raw.keyId}&key=${raw.key}"
                            else null,
                        headers = headers,
                        sourceStreamName = sourceStreamName
                    )
                )
            )
        }

    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Parse an M3U playlist (all type1–type3 variants) into [Channel] / [ChannelSource] objects.
     *
     * Supported directives:
     *   #EXTINF                              → name, tvg-logo, group-title
     *   #KODIPROP …license_key=<url|hex:hex> → license URL / inline ClearKey pair
     *   #EXTVLCOPT                           → http-user-agent, http-referer, http-origin
     *   #EXTHTTP:{"cookie":"…"}              → cookie header
     *
     * Stream type: URL contains ".mpd" → DASH (mpd_url), otherwise HLS (m3u8_url).
     * No #KODIPROP manifest_type check needed.
     */
    private fun parseM3u(text: String, sourceStreamName: String?, sourceUA: String?): List<Channel> {
        val result    = mutableListOf<Channel>()
        val defaultUA = sourceUA ?: "okhttp/4.12.0"

        var name = "";    var logo = "";       var group = ""
        var licUrl = "";  var userAgent = "";  var referer = ""
        var origin = "";  var cookie = ""

        fun reset() {
            name = "";    logo = "";       group = ""
            licUrl = "";  userAgent = "";  referer = ""
            origin = "";  cookie = ""
        }

        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXTINF:") -> {
                    name  = l.substringAfterLast(",").trim()
                    logo  = Regex("""tvg-logo="([^"]+)"""").find(l)?.groupValues?.get(1) ?: ""
                    group = Regex("""group-title="([^"]+)"""").find(l)?.groupValues?.get(1) ?: ""
                }

                // Handles both:
                //   (a) a real URL  → stored as-is
                //   (b) hex:hex pair → encoded into a dummy URL so loadLinks parses uniformly
                l.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                    val v = l.substringAfter("license_key=").trim()
                    licUrl = when {
                        v.startsWith("{")  -> "jwks:$v"
                        v.contains("://") -> v
                        v.contains(":") -> v.split(":", limit = 2).let { p ->
                            if (p.size == 2) "https://dummy.ck/?keyid=${p[0]}&key=${p[1]}" else ""
                        }
                        else -> ""
                    }
                }

                l.startsWith("#EXTVLCOPT:") -> {
                    val opt = l.substringAfter("#EXTVLCOPT:")
                    when {
                        opt.startsWith("http-user-agent=", ignoreCase = true) ->
                            userAgent = opt.substringAfter("=")
                        opt.startsWith("http-referrer=", ignoreCase = true) ||
                        opt.startsWith("http-referer=",  ignoreCase = true) ->
                            referer = opt.substringAfter("=")
                        opt.startsWith("http-origin=", ignoreCase = true) ->
                            origin = opt.substringAfter("=")
                    }
                }

                l.startsWith("#EXTHTTP:") -> try {
                    cookie = parseJson<Map<String, String>>(
                        l.substringAfter("#EXTHTTP:")
                    )["cookie"] ?: ""
                } catch (_: Exception) {}

                // Stream URL line → emit a Channel
                !l.startsWith("#") && l.isNotEmpty() -> {
                    val parts  = l.split("|")
                    val rawUrl = parts[0].trim().replace(Regex("[&?]xxx=[^&]*"), "")
                    val params = parts.getOrElse(1) { "" }

                    // Inline pipe params take priority over accumulated directives
                    val finalUA = Regex("User-Agent=([^|&]+)", RegexOption.IGNORE_CASE)
                        .find(params)?.groupValues?.get(1)?.trim()
                        .takeIf { !it.isNullOrEmpty() } ?: userAgent

                    val finalRef = Regex("Referer=([^|&]+)", RegexOption.IGNORE_CASE)
                        .find(params)?.groupValues?.get(1)?.trim()
                        .takeIf { !it.isNullOrEmpty() } ?: referer

                    val hdrs = mutableMapOf<String, String>()
                    hdrs["User-Agent"] = finalUA.ifEmpty { defaultUA }
                    if (finalRef.isNotEmpty()) hdrs["Referer"] = finalRef
                    if (origin.isNotEmpty())   hdrs["Origin"]  = origin
                    if (cookie.isNotEmpty())   hdrs["cookie"]  = cookie

                    // Type inferred from URL: .mpd → DASH, anything else → HLS
                    val isDash = rawUrl.contains(".mpd", ignoreCase = true)

                    result += Channel(
                        name    = name.ifEmpty { "Unknown" },
                        logo    = logo.ifEmpty { null },
                        group   = group.ifEmpty { null },
                        sources = mutableListOf(
                            ChannelSource(
                                mpd_url          = if (isDash) rawUrl else null,
                                m3u8_url         = if (!isDash) rawUrl else null,
                                license_url      = licUrl.ifEmpty { null },
                                headers          = hdrs,
                                sourceStreamName = sourceStreamName
                            )
                        )
                    )
                    reset()
                }
            }
        }
        return result
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String.hexToBase64Url(): String {
        val hex = trim().replace("-", "")
        if (hex.isEmpty() || hex.length % 2 != 0 ||
            !hex.matches(Regex("^[0-9a-fA-F]+$"))) return this
        return try {
            android.util.Base64.encodeToString(
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                android.util.Base64.URL_SAFE or
                android.util.Base64.NO_PADDING or
                android.util.Base64.NO_WRAP
            )
        } catch (_: Exception) { this }
    }

    private suspend fun getDRMKeysFromLicenseServer(url: String, kid: String): String = try {
        val res = app.post(
            url,
            headers = mapOf(
                "User-Agent"   to "Dalvik/2.1.0 (Linux; U; Android)",
                "Content-Type" to "application/json;charset=UTF-8"
            ),
            json = mapOf("kids" to listOf(kid), "type" to "temporary")
        ).text
        @Suppress("UNCHECKED_CAST")
        (parseJson<Map<String, Any>>(res)["keys"] as? List<Map<String, String>>)
            ?.firstOrNull { it["kid"] == kid }?.get("k") ?: ""
    } catch (_: Exception) { "" }
}
