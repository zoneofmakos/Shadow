package com.shadow

import android.content.Context
import com.lagradost.cloudstream3.plugins.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@CloudstreamPlugin
class MichaelPlugin : Plugin() {

    override fun load(context: Context) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val now = System.currentTimeMillis()

        val cncversePrefs = context.getSharedPreferences(
            "cncverse_donation",
            Context.MODE_PRIVATE
        )

        val phisherPrefs = context.getSharedPreferences(
            "phisher_donation_prefs",
            Context.MODE_PRIVATE
        )

        val cncverseEditor = cncversePrefs.edit()
        val phisherEditor = phisherPrefs.edit()

        cncverseEditor.putString("last_shown_day", today)

        phisherEditor.putLong("phisher_donation_last_shown_v2", now)

        cncverseEditor.apply()
        phisherEditor.apply()
    }
}
