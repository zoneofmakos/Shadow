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
    private val sharedPref = activity?.getSharedPreferences("ShadowTVExtensions", Context.MODE_PRIVATE)
    private val sourceUrl = "https://raw.githubusercontent.com/zoneofmakos/data/main/my.json"

    private val sourceData = runBlocking {
        app.get(sourceUrl).text.trim()
    }

    val sourceStreamsFromJson = parseJson<List<SourceStream>>(sourceData)
    val sourceStreams = sourceStreamsFromJson.associateBy { it.name }

    override fun load(context: Context) {
        val sourceStreamSettings = sourceStreams.keys.associateWith {
            sharedPref?.getBoolean(it, false) ?: false
        }
        val selectedSources = sourceStreamSettings.filter { it.value }.keys
        val selectedStreams = selectedSources.mapNotNull { sourceStreams[it] }

        selectedStreams.forEach { stream ->
            registerMainAPI(
                ShadowTV(
                    "📺 ${stream.name}",
                    listOf(stream)
                )
            )
        }

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = Settings(this, sharedPref, sourceStreams.keys.toList())
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}
