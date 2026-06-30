package com.shadow

object AdBlockList {

    val BLOCKED_HOSTS: Set<String> = setOf(
        "omg10.com",
        "omg1.com", "omg2.com", "omg3.com", "omg4.com", "omg5.com",
        "omg6.com", "omg7.com", "omg8.com", "omg9.com",

        "propellerads.com", "propeller-tracking.com",
        "monetag.com", "monetag-handhub.com",

        "adsterra.com", "adsterra.network", "prohitshere.com",

        "hilltopads.com", "hilltopads.net", "hilltopads-delivery.com",

        "popads.net", "popcash.net", "popmyads.com",

        "ad-maven.com", "a-mo.net", "mvn.link",

        "bit.ly", "t.ly", "cutt.ly", "shorturl.at", "tinyurl.com",
        "shrtco.de", "soo.gd", "s.id", "is.gd", "v.gd",
        "linkvertise.com", "linkvertise.net",

        "onclickperformance.com", "onclickprediction.com",
        "onclickscript.com", "highperformanceformat.com",
        "pushmonetization.com", "realtimepush.com",
        "highratecpm.com", "profithighrate.com",
        "crazypushsub.com", "push-notification.tools",
        "bemobtrcks.com", "bemobpath.com",
        "go2cloud.org", "go2affise.com",
        "trackings.selfpublishing.com",
        "xl-trail.com", "trail_trk.com",
    )

    val SAFE_HOSTS: Set<String> = setOf(
        "cs.repo", "cloudstream.on.fleek.co",
        "recloudstream.github.io", "github.com", "raw.githubusercontent.com",

        "t.me", "telegram.me",

        "discord.com", "discord.gg", "patreon.com", "ko-fi.com",
        "buymeacoffee.com",

        "www.google.com", "duckduckgo.com",

        "wikipedia.org", "imdb.com"
    )

    val NON_MEDIA_PATH_PATTERNS: List<Regex> = listOf(
        Regex("/\\d+/(\\d{6,})"),         // /4/11104489 style ad zone IDs
        Regex("/(popunder|popunderinit)"),
        Regex("/(redirect|go|visit|jump)/[a-zA-Z0-9]+"),
        Regex("/watch\\?key="),
        Regex("click\\?"),
        Regex("/aff(_)?id=")
    )

    fun isHostBlocked(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trim()

        if (h in BLOCKED_HOSTS) return true

        for (blocked in BLOCKED_HOSTS) {
            if (h == blocked || h.endsWith(".$blocked")) return true
        }
        return false
    }

    fun isHostSafe(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trim()

        if (h in SAFE_HOSTS) return true
        for (safe in SAFE_HOSTS) {
            if (h == safe || h.endsWith(".$safe")) return true
        }

        return try {
            AllowlistStore.isAllowed(h)
        } catch (_: Throwable) {
            false
        }
    }

    fun looksLikeAdPath(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return NON_MEDIA_PATH_PATTERNS.any { it.containsMatchIn(url) }
    }
}
