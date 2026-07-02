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

    private val sourceStreams = runBlocking {
        val sourceData = app.get(sourceUrl).text.trim()
        val sourceStreamsFromJson = parseJson<List<SourceStream>>(sourceData)
        sourceStreamsFromJson.map { it.name to it }.toMap()
    }

    override fun load(context: Context) {
        val sourceStreamSettings = sourceStreams.keys.associateWith {
            sharedPref?.getBoolean(it, false) ?: false
        }
        val selectedSources = sourceStreamSettings.filter { it.value }.keys // names
        val selectedStreams = selectedSources.map { sourceStreams[it] } // SourceStream objects

        registerMainAPI(ShadowTV(selectedStreams.filterNotNull()))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = Settings(this, sharedPref, sourceStreams.keys.toList())
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}
