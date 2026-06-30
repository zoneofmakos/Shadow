package com.shadow

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.content.ContextWrapper

class KaruppaninContext(
    base: Context,
    private val policy: KaruppanPolicy = KaruppanPolicy.DEFAULT,
    private val providerName: String? = null
) : ContextWrapper(base) {
    companion object {
        private const val TAG = "Karuppan"
    }

    override fun startActivity(intent: Intent?) {
        if (policy.shouldBlock(intent, providerName)) {
            handleBlocked(intent)
            return  // ⛔ THE VOID — super.startActivity() is never called
        }
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        if (policy.shouldBlock(intent, providerName)) {
            handleBlocked(intent)
            return  // ⛔ THE VOID
        }
        super.startActivity(intent, options)
    }

    override fun startActivities(intents: Array<out Intent>?) {
        intents?.forEach { if (policy.shouldBlock(it, providerName)) handleBlocked(it) }
        val filtered = intents?.filterNot { policy.shouldBlock(it, providerName) }?.toTypedArray()
            ?: return
        if (filtered.isNotEmpty()) super.startActivities(filtered)
    }

    override fun startActivities(intents: Array<out Intent>?, options: Bundle?) {
        intents?.forEach { if (policy.shouldBlock(it, providerName)) handleBlocked(it) }
        val filtered = intents?.filterNot { policy.shouldBlock(it, providerName) }?.toTypedArray()
            ?: return
        if (filtered.isNotEmpty()) super.startActivities(filtered, options)
    }

    private fun handleBlocked(intent: Intent?) {
        val url = intent?.data?.toString() ?: "(no data)"
        Log.w(TAG, "⛔ BLOCKED external browser launch → $url")
        Log.w(TAG, "   action=${intent?.action} flags=0x${intent?.flags?.toString(16)}")
        if (policy.showToast) {
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(
                        this,
                        "Karuppan blocked: ${shortUrl(url)}",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Throwable) { /* never throw on UI */ }
            }
        }
    }

    private fun shortUrl(url: String): String =
        if (url.length > 80) url.take(77) + "..." else url

    private val isStrictlyBlocked: Boolean
        get() = providerName != null && (policy.blockAllUnknown || AllowlistStore.blockedProviders().contains(providerName))

    override fun getSystemService(name: String): Any? {
        if (isStrictlyBlocked) {
            when (name) {
                Context.WINDOW_SERVICE,
                Context.CLIPBOARD_SERVICE,
                Context.NOTIFICATION_SERVICE,
                Context.VIBRATOR_SERVICE,
                Context.LOCATION_SERVICE,
                Context.AUDIO_SERVICE -> return null
            }
        }
        return super.getSystemService(name)
    }

    override fun sendBroadcast(intent: Intent?) {
        if (isStrictlyBlocked) return
        super.sendBroadcast(intent)
    }

    override fun startService(service: Intent?): android.content.ComponentName? {
        if (isStrictlyBlocked) return null
        return super.startService(service)
    }

    override fun bindService(service: Intent, conn: android.content.ServiceConnection, flags: Int): Boolean {
        if (isStrictlyBlocked) return false
        return super.bindService(service, conn, flags)
    }
}

data class KaruppanPolicy(
        val blockKnownAdHosts: Boolean = true,
        val blockAdPaths: Boolean = true,
        val blockAllUnknown: Boolean = true,
        val showToast: Boolean = false,
) {
    companion object {
        val DEFAULT get() = STRICT

        val PERMISSIVE = KaruppanPolicy(
            blockKnownAdHosts = true,
            blockAdPaths = true,
            blockAllUnknown = false,
            showToast = true,
        )

        val STRICT = KaruppanPolicy(
            blockKnownAdHosts = true,
            blockAdPaths = true,
            blockAllUnknown = true,
            showToast = false,    // silent void
        )

        val STRICT_VERBOSE = KaruppanPolicy(
            blockKnownAdHosts = true,
            blockAdPaths = true,
            blockAllUnknown = true,
            showToast = true,
        )
    }

    fun shouldBlock(intent: Intent?, providerName: String? = null): Boolean {
        if (intent == null) return false
        val action = intent.action
        if (action != Intent.ACTION_VIEW) return false

        val uri: Uri = intent.data ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false

        if (scheme != "http" && scheme != "https") return false

        val host = uri.host
        val url = uri.toString()

        val isProviderBlocked = providerName != null && AllowlistStore.blockedProviders().contains(providerName)

        if (isProviderBlocked && !AdBlockList.isHostSafe(host)) {
            return true
        }

        if (blockKnownAdHosts && AdBlockList.isHostBlocked(host)) {
            return true
        }

        if (blockAdPaths && AdBlockList.looksLikeAdPath(url)) {
            return true
        }

        if (blockAllUnknown && !AdBlockList.isHostSafe(host)) {
            return true
        }
        return false
    }
}
