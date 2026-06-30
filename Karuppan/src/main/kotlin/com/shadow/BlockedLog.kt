package com.shadow

object BlockedLog {
    @JvmStatic
    fun record(url: String, caller: String) {
        try {
            AllowlistStore.recordBlocked(url, caller)
        } catch (_: Throwable) {

        }
    }

    @JvmStatic
    fun all(): List<AllowlistStore.BlockedEntry> =
        try { AllowlistStore.blockedLog() } catch (_: Throwable) { emptyList() }

    @JvmStatic
    fun clear() {
        try { AllowlistStore.clearBlockedLog() } catch (_: Throwable) {}
    }
}
