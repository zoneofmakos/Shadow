package com.shadow

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method

object InstrumentationHook {

    private const val TAG = "Karuppan"
    @Volatile private var installed = false
    @Volatile private var originalInstrumentation: Instrumentation? = null

        fun install(policyProvider: () -> KaruppanPolicy): Boolean {
        if (installed) {
            Log.i(TAG, "InstrumentationHook already installed — skipping")
            return true
        }
        return try {
            val activityThread = currentActivityThread() ?: run {
                Log.w(TAG, "InstrumentationHook: could not get ActivityThread instance")
                return false
            }
            val mInstrumentationField = findField(activityThread.javaClass, "mInstrumentation")
                ?: findField(Class.forName("android.app.ActivityThread"), "mInstrumentation")
                ?: run {
                    Log.w(TAG, "InstrumentationHook: mInstrumentation field not found")
                    return false
                }
            mInstrumentationField.isAccessible = true

            val original = mInstrumentationField.get(activityThread) as? Instrumentation
            if (original == null) {
                Log.w(TAG, "InstrumentationHook: mInstrumentation is null")
                return false
            }
            if (original is KaruppuInstrumentation) {
                installed = true
                return true
            }

            val karuppu = KaruppuInstrumentation(original, policyProvider)
            mInstrumentationField.set(activityThread, karuppu)

            originalInstrumentation = original
            installed = true
            Log.i(TAG, "✓ InstrumentationHook installed — ALL activity launches in this process now route through Karuppan")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "InstrumentationHook install failed — falling back to per-provider Context wrapping only", t)
            false
        }
    }

        fun uninstall() {
        if (!installed) return
        try {
            val activityThread = currentActivityThread() ?: return
            val field = findField(activityThread.javaClass, "mInstrumentation") ?: return
            field.isAccessible = true
            val current = field.get(activityThread) as? Instrumentation
            if (current is KaruppuInstrumentation) {
                field.set(activityThread, current.original)
                Log.i(TAG, "InstrumentationHook uninstalled — restored original Instrumentation")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "InstrumentationHook uninstall failed: ${t.message}")
        } finally {
            installed = false
            originalInstrumentation = null
        }
    }

    private fun currentActivityThread(): Any? {
        return try {
            val cls = Class.forName("android.app.ActivityThread")
            val method: Method = cls.getDeclaredMethod("currentActivityThread")
            method.isAccessible = true
            method.invoke(null)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not get ActivityThread.currentActivityThread(): ${t.message}")
            null
        }
    }

    private fun findField(klass: Class<*>, name: String): Field? {
        var c: Class<*>? = klass
        while (c != null && c != Any::class.java) {
            try {
                return c.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {

            }
            c = c.superclass
        }
        return null
    }
}

class KaruppuInstrumentation(
    @JvmField val original: Instrumentation,
    private val policyProvider: () -> KaruppanPolicy
) : Instrumentation() {

    companion object {
        private const val TAG = "Karuppan"
    }

    fun execStartActivity(
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): android.app.Instrumentation.ActivityResult? {
        return maybeBlock(who, target, intent) {
            try {
                val method = android.app.Instrumentation::class.java.getDeclaredMethod(
                    "execStartActivity",
                    Context::class.java,
                    IBinder::class.java,
                    IBinder::class.java,
                    Activity::class.java,
                    Intent::class.java,
                    Int::class.javaPrimitiveType,
                    Bundle::class.java
                )
                method.isAccessible = true
                method.invoke(original, who, contextThread, token, target, intent, requestCode, options) as? android.app.Instrumentation.ActivityResult
            } catch (t: Throwable) {
                null
            }
        }
    }

    fun execStartActivity(
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int
    ): android.app.Instrumentation.ActivityResult? {
        return maybeBlock(who, target, intent) {
            try {
                val method = android.app.Instrumentation::class.java.getDeclaredMethod(
                    "execStartActivity",
                    Context::class.java,
                    IBinder::class.java,
                    IBinder::class.java,
                    Activity::class.java,
                    Intent::class.java,
                    Int::class.javaPrimitiveType
                )
                method.isAccessible = true
                method.invoke(original, who, contextThread, token, target, intent, requestCode) as? android.app.Instrumentation.ActivityResult
            } catch (t: Throwable) {
                null
            }
        }
    }

    fun execStartActivity(
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: String?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): android.app.Instrumentation.ActivityResult? {
        return maybeBlock(who, target, intent) {
            try {
                val method = android.app.Instrumentation::class.java.getDeclaredMethod(
                    "execStartActivity",
                    Context::class.java,
                    IBinder::class.java,
                    IBinder::class.java,
                    String::class.java,
                    Intent::class.java,
                    Int::class.javaPrimitiveType,
                    Bundle::class.java
                )
                method.isAccessible = true
                method.invoke(original, who, contextThread, token, target, intent, requestCode, options) as? android.app.Instrumentation.ActivityResult
            } catch (t: Throwable) {
                null
            }
        }
    }

    fun execStartActivity(
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: String?,
        intent: Intent,
        requestCode: Int
    ): android.app.Instrumentation.ActivityResult? {
        return maybeBlock(who, target, intent) {
            try {
                val method = android.app.Instrumentation::class.java.getDeclaredMethod(
                    "execStartActivity",
                    Context::class.java,
                    IBinder::class.java,
                    IBinder::class.java,
                    String::class.java,
                    Intent::class.java,
                    Int::class.javaPrimitiveType
                )
                method.isAccessible = true
                method.invoke(original, who, contextThread, token, target, intent, requestCode) as? android.app.Instrumentation.ActivityResult
            } catch (t: Throwable) {
                null
            }
        }
    }

    fun execStartActivity(
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: String?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?,
        user: android.os.UserHandle?
    ): android.app.Instrumentation.ActivityResult? {
        return maybeBlock(who, target, intent) {
            try {
                val method = android.app.Instrumentation::class.java.getDeclaredMethod(
                    "execStartActivity",
                    Context::class.java,
                    IBinder::class.java,
                    IBinder::class.java,
                    String::class.java,
                    Intent::class.java,
                    Int::class.javaPrimitiveType,
                    Bundle::class.java,
                    android.os.UserHandle::class.java
                )
                method.isAccessible = true
                method.invoke(original, who, contextThread, token, target, intent, requestCode, options, user) as? android.app.Instrumentation.ActivityResult
            } catch (t: Throwable) {
                null
            }
        }
    }

    private inline fun maybeBlock(
        who: Context?,
        target: Any?,
        intent: Intent,
        proceed: () -> android.app.Instrumentation.ActivityResult?
    ): android.app.Instrumentation.ActivityResult? {
        val policy = policyProvider()

        val stackTrace = Thread.currentThread().stackTrace
        val matchedProvider = try {
            com.lagradost.cloudstream3.APIHolder.allProviders.find { provider ->
                val providerClassName = provider.javaClass.name
                stackTrace.any { it.className.startsWith(providerClassName) }
            }
        } catch (_: Throwable) { null }

        val providerName = matchedProvider?.name

        if (policy.shouldBlock(intent, providerName)) {
            val url = intent.data?.toString() ?: "(no data)"
            val callerName = if (target is Activity) target::class.java.simpleName else if (target is String) target else who?.let { it::class.java.simpleName } ?: "unknown"
            Log.w(TAG, "⛔ [Instrumentation] BLOCKED external launch from ${providerName ?: callerName} → $url")

            if (policy.showToast) {
                try {
                    val ctx = (target as? Activity ?: who) as? Context
                    if (ctx != null) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                android.widget.Toast.makeText(
                                    ctx,
                                    "Karuppan voided: ${url.take(60)}",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
            }

            try {
                BlockedLog.record(url, callerName)
            } catch (_: Throwable) {}

return null
        }
        return proceed()
    }
}
