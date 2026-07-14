package com.amaze

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class IsaiDubPlugin: Plugin() {
    override fun load(context: Context) {
        IsaiDubProvider.context = context
        registerMainAPI(IsaiDubProvider())
    }
}
