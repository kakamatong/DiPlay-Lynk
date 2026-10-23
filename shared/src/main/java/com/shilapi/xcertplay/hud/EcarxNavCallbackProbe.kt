package com.shilapi.xcertplay.hud

import android.graphics.Bitmap
import android.util.Log
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayDeque

/**
 * Diagnostic probe for `INaviInteraction.registerNavigationInteractionCallback`.
 *
 * The 0.3.25 lane report showed the navi object has no `getLaneInfo`/`getNavigationInfo` getter;
 * the only callback-shaped API is `registerNavigationInteractionCallback`. This probe registers a
 * proxy implementation, logs every callback with its arguments, and unregisters after a timeout.
 * A callback that carries `INavigationInfo`/`ILaneInfo` is formatted down to lane number, ID and
 * icon size, so the lane read path can be identified from the log alone.
 *
 * Nothing here is used by the live guidance path; it is a diagnostic, not a lane implementation.
 */
internal object EcarxNavCallbackProbe {
    private const val TAG = "DiPlay-ECARX-Lane"
    private const val LISTEN_MILLIS = 180_000L
    private const val MAX_EVENTS = 40

    private val lock = Any()
    private var navi: Any? = null
    private var callback: Any? = null
    @Volatile private var generation = 0
    private val events = ArrayDeque<String>()

    /** Registers the callback proxy and returns the interface dump + listening status. */
    fun start(navi: Any, durationMillis: Long = LISTEN_MILLIS): String =
        runCatching { startInternal(navi, durationMillis) }
            .getOrElse { "navigation callback probe failed: ${describe(it)}" }

    private fun startInternal(navi: Any, durationMillis: Long): String {
        stop()
        synchronized(lock) { events.clear() }
        val register = navi.javaClass.methods.firstOrNull {
            it.name == "registerNavigationInteractionCallback" && it.parameterTypes.size == 1
        } ?: return "registerNavigationInteractionCallback not found on ${navi.javaClass.name}"
        val callbackClass = register.parameterTypes[0]
        if (!callbackClass.isInterface) {
            return "navigation callback is not an interface: ${callbackClass.name}"
        }
        val proxy = runCatching {
            val handler = InvocationHandler { proxy, method, args ->
                val text = "cb." + method.name + "(" +
                    (args?.joinToString(",") { formatArg(it) } ?: "") + ")"
                record(text)
                Log.i(TAG, text)
                when (method.name) {
                    "toString" -> "EcarxNavCallbackProbe"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
            }
            Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass), handler)
        }.getOrElse { return "navigation callback proxy failed: ${describe(it)}" }
        val registered = runCatching { register.invoke(navi, proxy) }
        if (registered.isFailure) {
            return "registerNavigationInteractionCallback failed: ${describe(registered.exceptionOrNull()!!)}"
        }
        synchronized(lock) {
            this.navi = navi
            callback = proxy
            generation += 1
            val current = generation
            Thread({
                try {
                    Thread.sleep(durationMillis)
                } catch (_: InterruptedException) {
                    // fall through to the generation check
                }
                if (current == generation) stop()
            }, "diplay-nav-callback-probe").apply { isDaemon = true }.start()
        }
        return buildString {
            appendLine("callback: ${callbackClass.name}")
            appendLine("registered=true; listening ${durationMillis / 1000}s")
            appendLine("callback methods:")
            callbackClass.methods.sortedBy { it.name }.forEach {
                appendLine("  " + signature(it))
            }
        }.trimEnd()
    }

    /** Unregisters the proxy, if one is active. Safe to call repeatedly. */
    fun stop() {
        val active = synchronized(lock) {
            val pair = if (navi != null && callback != null) navi!! to callback!! else null
            navi = null
            callback = null
            generation += 1
            pair
        } ?: return
        val unregister = runCatching {
            active.first.javaClass.methods.firstOrNull {
                it.name == "unregisterNavigationInteractionCallback" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isInstance(active.second)
            }
        }.getOrNull()
        if (unregister != null) {
            runCatching { unregister.invoke(active.first, active.second) }
                .onFailure { Log.w(TAG, "nav callback unregister failed: ${describe(it)}") }
        }
        Log.i(TAG, "nav callback probe stopped")
    }

    /** Compact last events for the diagnostic report; the full stream is in logcat. */
    fun lastEvents(limit: Int = 12): String = synchronized(lock) {
        events.toList().takeLast(limit).joinToString("|").take(600).ifEmpty { "none" }
    }

    private fun record(text: String) {
        synchronized(lock) {
            events.addLast(text)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    private fun formatArg(value: Any?): String {
        if (value == null) return "null"
        if (value is Bitmap) return "Bitmap(" + value.width + "x" + value.height + ")"
        if (value.javaClass.isArray) {
            val length = ReflectArray.getLength(value)
            return (0 until length).joinToString(",", "[", "]") {
                formatArg(ReflectArray.get(value, it))
            }
        }
        if (value is Iterable<*>) {
            return value.joinToString(",", "[", "]") { formatArg(it) }
        }
        val name = value.javaClass.name
        if (name.contains("LaneInfo", ignoreCase = true)) {
            return "Lane(number=" + readInt(value, "getLaneNumber") +
                ",id=" + readInt(value, "getLaneBackIconId") +
                ",icon=" + readIcon(value, "getLaneBackIcon") + ")"
        }
        if (name.contains("NavigationInfo", ignoreCase = true)) {
            val lane = readObject(value, "getLaneInfo")
            if (lane != null) return "NavigationInfo(lane=" + formatArg(lane) + ")"
        }
        return value.toString().take(200)
    }

    private fun readInt(target: Any, name: String): String {
        val method = findNoArg(target, name) ?: return "?"
        return runCatching { method.invoke(target)?.toString() ?: "null" }.getOrDefault("err")
    }

    private fun readIcon(target: Any, name: String): String {
        val method = findNoArg(target, name) ?: return "?"
        val value = runCatching { method.invoke(target) }.getOrNull() ?: return "null"
        return if (value is Bitmap) "Bitmap(" + value.width + "x" + value.height + ")" else value.toString()
    }

    private fun readObject(target: Any, name: String): Any? {
        val method = findNoArg(target, name) ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun findNoArg(target: Any, name: String): Method? =
        runCatching { target.javaClass.getMethod(name) }
            .recoverCatching { target.javaClass.getDeclaredMethod(name).also { it.isAccessible = true } }
            .getOrNull()

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        String::class.java -> ""
        else -> null
    }

    private fun signature(method: Method): String =
        method.name + "(" + method.parameterTypes.joinToString(",") { it.simpleName } + "): " +
            method.returnType.simpleName

    private fun describe(error: Throwable): String {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        return cause.javaClass.simpleName + ": " + cause.message
    }
}
