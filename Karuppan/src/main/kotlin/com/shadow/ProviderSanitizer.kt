package com.shadow

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.APIHolder
import java.lang.ref.WeakReference
import java.lang.reflect.Field

object ProviderSanitizer {

    private const val TAG = "Karuppan"
    private const val INTERVAL_MS = 500L  // 0.5 seconds — catches new plugins near-instantly

    private val handler = Handler(Looper.getMainLooper())
    private val wrappedTracker = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<MainAPI, Boolean>()
    )
    @Volatile private var running = false
    @Volatile var policy: KaruppanPolicy = KaruppanPolicy.DEFAULT

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                sanitizeAllProviders()
            } catch (t: Throwable) {
                Log.e(TAG, "Sanitizer poll failed", t)
            }
            if (running) {
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
    }

        fun start(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (running) return
        running = true
        Log.i(TAG, "Karuppan sanitizer started — interval=${INTERVAL_MS}ms")

        handler.post(pollRunnable)
    }

        fun stop() {
        running = false
        handler.removeCallbacks(pollRunnable)
        Log.i(TAG, "Karuppan sanitizer stopped")
    }

        fun sanitizeAllProviders() {
        val providers: List<MainAPI> = try {
            val method = APIHolder::class.java.methods.find { it.name == "getAllProviders" || it.name == "getApis" || it.name == "apis" }
            if (method != null) {
                (method.invoke(APIHolder) as? Iterable<*>)?.filterIsInstance<MainAPI>() ?: emptyList()
            } else {
                APIHolder.allProviders.toList()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read APIHolder.allProviders", t)
            return
        }

        var newlyWrapped = 0
        var alreadyWrapped = 0
        var failed = 0

        for (provider in providers) {

            if (provider is URLInterceptorProvider) continue

            if (wrappedTracker.containsKey(provider)) {
                alreadyWrapped++
                continue
            }

            try {
                val didWrap = wrapAllContextFields(provider)
                if (didWrap) {
                    wrappedTracker[provider] = true
                    newlyWrapped++
                    Log.i(TAG, "✓ Wrapped context for provider: ${provider.name} (${provider.javaClass.simpleName})")
                } else {

wrappedTracker[provider] = true
                }
            } catch (t: Throwable) {
                failed++
                Log.w(TAG, "Failed to wrap provider ${provider.name}: ${t.message}")
            }
        }

        if (newlyWrapped > 0) {
            Log.i(TAG, "Sanitize pass: wrapped=$newlyWrapped already=$alreadyWrapped failed=$failed total=${providers.size}")
        }
    }

        private fun wrapAllContextFields(provider: MainAPI): Boolean {
        var anyWrapped = false
        var klass: Class<*>? = provider.javaClass
        val seenFields = mutableSetOf<String>()

        while (klass != null && klass != Any::class.java) {
            val fields: Array<Field> = try {
                klass.declaredFields
            } catch (t: Throwable) {
                continue
            }

            for (field in fields) {

                val key = "${klass.name}#${field.name}"
                if (key in seenFields) continue
                seenFields.add(key)

                val ft = field.type
                val isContextField = ft == Context::class.java ||
                    ft == android.content.ContextWrapper::class.java ||
                    ft.isAssignableFrom(Context::class.java) ||
                    Context::class.java.isAssignableFrom(ft)
                if (!isContextField) continue

                try {
                    field.isAccessible = true
                    val current = field.get(provider) ?: continue
                    if (current is KaruppaninContext) continue          // already wrapped
                    if (current !is Context) continue
                    val wrapped = KaruppaninContext(current, policy, provider.name)
                    field.set(provider, wrapped)
                    anyWrapped = true
                } catch (t: Throwable) {

                }
            }

            klass = klass.superclass
        }

        return anyWrapped
    }

        fun forget(provider: MainAPI) {
        wrappedTracker.remove(provider)
    }

        fun reset() {
        wrappedTracker.clear()
    }
}
