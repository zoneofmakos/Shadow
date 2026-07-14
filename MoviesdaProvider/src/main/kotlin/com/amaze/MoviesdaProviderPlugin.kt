package com.amaze

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MoviesdaProviderPlugin: Plugin() {
    override fun load(context: Context) {
        MoviesdaProvider.context = context
        registerMainAPI(MoviesdaProvider())
    }
}
