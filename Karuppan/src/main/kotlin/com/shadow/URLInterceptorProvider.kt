package com.shadow

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.ErrorLoadingException

// Karuppan
class URLInterceptorProvider : MainAPI() {

    override var name = NAME              // unique; tracked in APIHolder.apiMap
    override var mainUrl = "https://"     // ← claims ALL https URLs (first match wins)
    override val supportedTypes = TvType.values().toSet()
    override val hasMainPage = false
    override val hasQuickSearch = false
    override var lang = "en"

    companion object {
        const val NAME = "Karuppan-Interceptor"
    }

    private fun realProviderFor(url: String): MainAPI? {
        return APIHolder.allProviders.firstOrNull { p ->
            p !== this &&
                p !is URLInterceptorProvider &&
                p.mainUrl.length > "https://".length &&   // skip other root claimers
                url.startsWith(p.mainUrl)
        }
    }

    override suspend fun load(url: String): LoadResponse? {

        if (AdBlockList.isHostBlocked(android.net.Uri.parse(url).host) ||
            AdBlockList.looksLikeAdPath(url)
        ) {
            android.util.Log.w(
                "Karuppan",
                "URLInterceptor blocked load() → $url"
            )
            return null
        }

        val real = realProviderFor(url) ?: return null
        return try {
            real.load(url)
        } catch (e: ErrorLoadingException) {
            null
        } catch (e: Throwable) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

val real = realProviderFor(data) ?: return false

        var sawLinks = false
        val wrappedCallback: (ExtractorLink) -> Unit = cb@{ link ->
            val host = try { android.net.Uri.parse(link.url).host } catch (_: Throwable) { null }
            if (AdBlockList.isHostBlocked(host) || AdBlockList.looksLikeAdPath(link.url)) {
                android.util.Log.w("Karuppan", "URLInterceptor dropped ExtractorLink → ${link.url}")
                return@cb    // drop silently
            }
            sawLinks = true
            callback(link)
        }

        return try {
            real.loadLinks(data, isCasting, subtitleCallback, wrappedCallback)
        } catch (e: Throwable) {
            sawLinks
        }
    }

override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = null
    override suspend fun search(query: String): List<SearchResponse>? = null
    override suspend fun search(query: String, page: Int): com.lagradost.cloudstream3.SearchResponseList? = null
    override suspend fun quickSearch(query: String): List<SearchResponse>? = null
}
