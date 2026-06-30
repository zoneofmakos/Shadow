package com.shadow

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object AllowlistStore {
    private const val TAG = "Karuppan"
    private const val PREFS_NAME = "Karuppan_allowlist"
    private const val KEY_ALWAYS = "always_allow_hosts"
    private const val KEY_BLOCKED_PROVIDERS = "blocked_providers"
    private const val KEY_BLOCKED = "blocked_attempts_log"
    private const val MAX_BLOCKED_LOG = 200

    @Volatile private var prefs: SharedPreferences? = null

    private val sessionAllowOnce = java.util.Collections.synchronizedSet(HashSet<String>())

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.i(TAG, "AllowlistStore initialized — alwaysAllow=${alwaysAllow().size} hosts")
    }

    fun alwaysAllow(): Set<String> {
        val raw = prefs?.getString(KEY_ALWAYS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it).lowercase().trim() }.toSet()
        } catch (_: Throwable) { emptySet() }
    }

    fun addAlwaysAllow(host: String): Boolean {
        val normalized = host.lowercase().trim()
        if (normalized.isEmpty()) return false
        val current = alwaysAllow().toMutableSet()
        if (!current.add(normalized)) return false
        prefs?.edit()?.putString(KEY_ALWAYS, JSONArray(current.toList()).toString())?.apply()
        Log.i(TAG, "AllowlistStore: added always-allow → $normalized")
        return true
    }

    fun removeAlwaysAllow(host: String): Boolean {
        val normalized = host.lowercase().trim()
        val current = alwaysAllow().toMutableSet()
        if (!current.remove(normalized)) return false
        prefs?.edit()?.putString(KEY_ALWAYS, JSONArray(current.toList()).toString())?.apply()
        Log.i(TAG, "AllowlistStore: removed always-allow → $normalized")
        return true
    }

    fun blockedProviders(): Set<String> {
        val raw = prefs?.getString(KEY_BLOCKED_PROVIDERS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it).trim() }.toSet()
        } catch (_: Throwable) { emptySet() }
    }

    fun addBlockedProvider(providerName: String): Boolean {
        val normalized = providerName.trim()
        if (normalized.isEmpty()) return false
        val current = blockedProviders().toMutableSet()
        if (!current.add(normalized)) return false
        prefs?.edit()?.putString(KEY_BLOCKED_PROVIDERS, JSONArray(current.toList()).toString())?.apply()
        Log.i(TAG, "AllowlistStore: added blocked provider → $normalized")
        return true
    }

    fun removeBlockedProvider(providerName: String): Boolean {
        val normalized = providerName.trim()
        val current = blockedProviders().toMutableSet()
        if (!current.remove(normalized)) return false
        prefs?.edit()?.putString(KEY_BLOCKED_PROVIDERS, JSONArray(current.toList()).toString())?.apply()
        Log.i(TAG, "AllowlistStore: removed blocked provider → $normalized")
        return true
    }

    fun allowOnce(host: String) {
        sessionAllowOnce.add(host.lowercase().trim())
        Log.i(TAG, "AllowlistStore: allow-once (session) → $host")
    }

    fun clearSessionAllowOnce() {
        sessionAllowOnce.clear()
    }

    fun isAllowed(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trim()
        if (h in sessionAllowOnce) return true
        val always = alwaysAllow()
        if (h in always) return true

        for (allowed in always) {
            if (h.endsWith(".$allowed")) return true
        }
        return false
    }

    data class BlockedEntry(
        val url: String,
        val caller: String,
        val timestamp: Long
    )

    fun blockedLog(): List<BlockedEntry> {
        val raw = prefs?.getString(KEY_BLOCKED, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { idx ->
                val obj = arr.getJSONObject(idx)
                BlockedEntry(
                    url = obj.optString("url"),
                    caller = obj.optString("caller"),
                    timestamp = obj.optLong("ts")
                )
            }
        } catch (_: Throwable) { emptyList() }
    }

    fun recordBlocked(url: String, caller: String) {
        val current = blockedLog().toMutableList()
        current.add(0, BlockedEntry(url, caller, System.currentTimeMillis()))

        val trimmed = current.take(MAX_BLOCKED_LOG)
        val arr = JSONArray()
        for (entry in trimmed) {
            val obj = JSONObject()
            obj.put("url", entry.url)
            obj.put("caller", entry.caller)
            obj.put("ts", entry.timestamp)
            arr.put(obj)
        }
        prefs?.edit()?.putString(KEY_BLOCKED, arr.toString())?.apply()
    }

    fun clearBlockedLog() {
        prefs?.edit()?.remove(KEY_BLOCKED)?.apply()
    }
}
