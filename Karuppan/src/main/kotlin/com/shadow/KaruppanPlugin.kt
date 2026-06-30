package com.shadow

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.lang.reflect.Field

@CloudstreamPlugin
class KaruppanPlugin : Plugin() {

    companion object {
        private const val TAG = "Karuppan"
    }

    private var activity: Activity? = null
    private var lifecycleHook: Application.ActivityLifecycleCallbacks? = null

    override fun load(context: Context) {
        Log.i(TAG, "═══════════════════════════════════════════════════════════════")
        Log.i(TAG, "  Karuppan — AGGRESSIVE mode loading")
        Log.i(TAG, "  Strategy: Instrumentation hook + Activity wrap + Provider wrap")
        Log.i(TAG, "  Policy: STRICT (block-all-except-allowlist), silent void")
        Log.i(TAG, "═══════════════════════════════════════════════════════════════")

        activity = context as? Activity

        try {
            AllowlistStore.init(context)
            Log.i(TAG, "✓ AllowlistStore initialized (alwaysAllow=${AllowlistStore.alwaysAllow().size}, blockedLog=${AllowlistStore.blockedLog().size})")
        } catch (t: Throwable) {
            Log.e(TAG, "AllowlistStore init failed — persistent allowlist will be unavailable", t)
        }

try {
            val ok = InstrumentationHook.install { ProviderSanitizer.policy }
            if (ok) {
                Log.i(TAG, "✓ Instrumentation hook ACTIVE — all startActivity calls now route through Karuppan")
            } else {
                Log.w(TAG, "⚠ Instrumentation hook FAILED — falling back to per-Activity / per-Provider Context wrapping only")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Instrumentation hook install threw — continuing with fallback defenses", t)
        }

try {
            val app = context.applicationContext as? Application
            if (app != null) {
                lifecycleHook = ActivityContextHook { ProviderSanitizer.policy }
                app.registerActivityLifecycleCallbacks(lifecycleHook)
                Log.i(TAG, "✓ ActivityLifecycleCallbacks registered — every Activity's mBase will be wrapped on create")
            } else {
                Log.w(TAG, "Could not get Application instance — Activity mBase wrapping disabled")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ActivityLifecycleCallbacks registration failed", t)
        }

        try {
            registerMainAPI(URLInterceptorProvider())
            Log.i(TAG, "✓ Registered URLInterceptorProvider (mainUrl='https://')")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register URLInterceptorProvider", t)
        }

        try {
            sanitizeVideoClickActions()
        } catch (t: Throwable) {
            Log.w(TAG, "VideoClickAction sanitization failed", t)
        }

try {
            ProviderSanitizer.policy = KaruppanPolicy.STRICT
            ProviderSanitizer.start(context)
            Log.i(TAG, "✓ Started ProviderSanitizer (interval=5s, policy=STRICT)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start ProviderSanitizer", t)
        }

        openSettings = {
            activity?.let { act ->
                try {
                    KaruppuSettingsDialog(act, ProviderSanitizer.policy) { newPolicy ->
                        ProviderSanitizer.policy = newPolicy
                        Log.i(TAG, "Policy updated: $newPolicy")
                    }.show()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to show settings dialog", t)
                }
            }
        }

        Log.i(TAG, "Karuppan AGGRESSIVE mode loaded — all external-browser launches now go to the void.")
        Log.i(TAG, "To allow a specific host through, add it via Settings → Karuppan → Allowlist.")
    }

    override fun beforeUnload() {
        Log.i(TAG, "Karuppan unloading — uninstalling hooks")
        try { ProviderSanitizer.stop() } catch (_: Throwable) {}
        try { InstrumentationHook.uninstall() } catch (_: Throwable) {}
        try {
            val app = activity?.application
            lifecycleHook?.let { app?.unregisterActivityLifecycleCallbacks(it) }
        } catch (_: Throwable) {}
        super.beforeUnload()
    }

    private fun sanitizeVideoClickActions() {
        val actions = VideoClickActionHolder.allVideoClickActions
        val maliciousActionClasses = setOf(
            "com.MaliciousPlugin.browser.BrowserAdAction",
            "com.ad.RedirectAction",
        )
        var removed = 0
        val toRemove = mutableListOf<VideoClickAction>()
        for (action in actions.toList()) {
            val srcPlugin = try { action.sourcePlugin ?: "" } catch (_: Throwable) { "" }
            val className = action::class.java.name
            if (srcPlugin.contains("MaliciousPlugin", ignoreCase = true) ||
                srcPlugin.contains("AdNetwork", ignoreCase = true) ||
                className in maliciousActionClasses
            ) {
                toRemove.add(action)
                Log.w(TAG, "Flagging malicious VideoClickAction: $className (src=$srcPlugin)")
            }
        }
        for (a in toRemove) {
            try { actions.remove(a); removed++ } catch (_: Throwable) {}
        }
        if (removed > 0) Log.i(TAG, "✓ Removed $removed malicious VideoClickAction(s)")
    }
}

class ActivityContextHook(
    private val policyProvider: () -> KaruppanPolicy
) : Application.ActivityLifecycleCallbacks {

    companion object {
        private const val TAG = "Karuppan"
        private val mBaseField: Field? by lazy {
            try {
                val f = ContextWrapper::class.java.getDeclaredField("mBase")
                f.isAccessible = true
                f
            } catch (t: Throwable) {
                Log.w(TAG, "Could not access ContextWrapper.mBase — Activity wrapping disabled: ${t.message}")
                null
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
        wrapActivity(activity)
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    private fun wrapActivity(activity: Activity) {
        val field = mBaseField ?: return
        try {
            val current = field.get(activity) as? Context ?: return
            if (current is KaruppaninContext) return  // already wrapped
            val wrapped = KaruppaninContext(current, policyProvider())
            field.set(activity, wrapped)
            Log.i(TAG, "✓ Wrapped Activity mBase: ${activity.javaClass.simpleName}")
        } catch (t: Throwable) {

}
    }
}
