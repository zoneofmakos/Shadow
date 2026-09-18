package com.shadow

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.shadow.SourceStream
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class ShadowTVPlugin : Plugin() {
    private val sharedPref = activity?.getSharedPreferences("ShadowTV", Context.MODE_PRIVATE)
    private val sourceUrl = "https://raw.githubusercontent.com/zoneofmakos/data/main/my.json"

    private val sourceData = runBlocking {
        app.get(sourceUrl).text.trim()
    }

    val sourceStreamsFromJson = parseJson<List<SourceStream>>(sourceData)
    val sourceStreams = sourceStreamsFromJson.map { it.name to it }.toMap()

    override fun load(context: Context) {
        val sourceStreamSettings = sourceStreams.keys.associateWith {
            sharedPref?.getBoolean(it, false) ?: false
        }
        val selectedSources = sourceStreamSettings.filter { it.value }.keys // names
        val selectedStreams = selectedSources.map { sourceStreams[it] } // SourceStream objects

        registerMainAPI(ShadowTV("Shadow TV", selectedStreams.filterNotNull()))

        registerMainAPI(ShadowTV("📺 DRM Live", listOf(SourceStream(
            "DRM Live",
            "https://la.drmlive.net/tp/playlist",
            "m3u",
            "OTT Navigator/1.7.1.4 (Linux;Android 13; en; 1fin92n)",
        ))))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = Settings(this, sharedPref, sourceStreams.keys.toList())
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}
