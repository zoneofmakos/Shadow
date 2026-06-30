package com.shadow

import android.content.Context
import com.lagradost.cloudstream3.plugins.*

@CloudstreamPlugin
class MichaelPlugin : Plugin() {

    override fun load(context: Context) {
        val prefsName = "CNCVerseSubscription"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        val now = System.currentTimeMillis() / 1000L
        val twentyEightDays = 28L * 24 * 60 * 60
        val renewalThreshold = 2L * 24 * 60 * 60

        val editor = prefs.edit()

        if (!prefs.getBoolean("dont_show_ads_popup", false)) {
            editor.putBoolean("dont_show_ads_popup", true)
        }

        if (prefs.getString("mode", null) != "subscription") {
            editor.putString("mode", "subscription")
        }

        val expiresAt = prefs.getLong("expires_at", 0L)

        if (expiresAt == 0L) {
            editor.putLong("expires_at", now + twentyEightDays)
        } else {
            val remaining = expiresAt - now

            if (remaining <= renewalThreshold) {
                editor.putLong("expires_at", now + twentyEightDays)
            }
        }

        editor.apply()
    }
}
