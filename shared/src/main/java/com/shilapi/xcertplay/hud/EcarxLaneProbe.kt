package com.shilapi.xcertplay.hud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Diagnostic probe for the opaque `ILaneInfo` contract.
 *
 * ECARX ships no SDK docs, so the only definition of `ILaneInfo` is the system image itself. The
 * Settings button first dumps the interface as the runtime sees it, then runs several spy passes
 * through `updateLaneInfo(ILaneInfo[])`. Every call the platform makes on a spy is recorded with
 * the value the spy returned, which names the fields the car actually reads.
 *
 * The aggregate `INavigationInfo.getLaneInfo()` path is not part of this probe: one run showed no
 * calls at all, and setting it up pushes a fake navigation card that obscures the lane area.
 *
 * Nothing here is used by the live guidance path. A failure only affects the diagnostic report.
 */
internal object EcarxLaneProbe {
    private const val TAG = "DiPlay-ECARX-Lane"
    private const val SPY_LANES = 3

    /** The values one spy lane answers with. */
    private data class LaneValue(val id: Int, val number: Int, val icon: Bitmap?)

    /** One lane-read attempt: source is null when no getter exists, text null when it is empty. */
    internal data class LaneRead(val source: String?, val text: String?)

    /**
     * Tries the read paths a DIM navigation object may expose: `getLaneInfo()` on the navi object,
     * or `getNavigationInfo().getLaneInfo()`. A missing getter returns `source == null`; an existing
     * getter with no lanes returns `text == null`.
     */
    fun readLaneInfo(navi: Any): LaneRead {
        val direct = findNoArg(navi, "getLaneInfo")
        if (direct != null) {
            val value = runCatching { direct.invoke(navi) }.getOrNull()
            return LaneRead("getLaneInfo", formatLaneInfo(value))
        }
        val navigationInfo = findNoArg(navi, "getNavigationInfo")
            ?: findNoArg(navi, "getCurrentNavigationInfo")
            ?: return LaneRead(null, null)
        val info = runCatching { navigationInfo.invoke(navi) }.getOrNull()
            ?: return LaneRead(navigationInfo.name, null)
        val lane = findNoArg(info, "getLaneInfo")
            ?: return LaneRead(navigationInfo.name, null)
        val value = runCatching { lane.invoke(info) }.getOrNull()
        return LaneRead(navigationInfo.name + ".getLaneInfo", formatLaneInfo(value))
    }

    /** No-arg methods plus anything named Lane/Navigation, so a missing read path is diagnosable. */
    fun naviMethodNames(navi: Any): String = runCatching {
        navi.javaClass.methods
            .filter {
                it.parameterCount == 0 ||
                    it.name.contains("Lane", ignoreCase = true) ||
                    it.name.contains("Navigation", ignoreCase = true)
            }
            .sortedBy { it.name }
            .joinToString(",") { method ->
                method.name + "(" + method.parameterTypes.joinToString(",") { it.simpleName } +
                    "): " + method.returnType.simpleName
            }
    }.getOrDefault("missing")

    /** Every method the navi object exposes, including the update methods with parameters. */
    fun naviAllMethodNames(navi: Any): String = runCatching {
        navi.javaClass.methods
            .sortedBy { it.name }
            .joinToString(",") { signature(it) }
    }.getOrDefault("missing")

    private fun findNoArg(target: Any, name: String): Method? =
        runCatching { target.javaClass.getMethod(name) }
            .recoverCatching { target.javaClass.getDeclaredMethod(name).also { it.isAccessible = true } }
            .getOrNull()

    private fun formatLaneInfo(value: Any?): String? {
        if (value == null) return null
        val items = when {
            value.javaClass.isArray -> (0 until ReflectArray.getLength(value))
                .mapNotNull { ReflectArray.get(value, it) }
            value is Iterable<*> -> value.filterNotNull()
            else -> listOf(value)
        }
        if (items.isEmpty()) return null
        return items.take(8).mapIndexed { index, lane ->
            "[$index] number=" + readInt(lane, "getLaneNumber") +
                " id=" + readInt(lane, "getLaneBackIconId") +
                " icon=" + readIcon(lane, "getLaneBackIcon")
        }.joinToString("; ")
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

    fun probe(navi: Any): String {
        val lane = runCatching { Class.forName(EcarxCapabilities.LANE_INFO) }.getOrNull()
            ?: return "ILaneInfo class not found on this head unit: ${EcarxCapabilities.LANE_INFO}"
        val report = buildString {
            appendLine("navi: " + navi.javaClass.name)
            appendLine("navi methods (no-arg):")
            appendLine("  " + naviMethodNames(navi))
            appendLine("navi methods (all):")
            appendLine("  " + naviAllMethodNames(navi))
            appendLine("class: " + lane.name)
            appendLine("interface: " + lane.isInterface)
            appendLine("modifiers: " + Modifier.toString(lane.modifiers))
            appendLine("super: " + (lane.superclass?.name ?: "none"))
            appendLine("interfaces: " + lane.interfaces.joinToString { it.name }.ifEmpty { "none" })
            appendLine()
            appendLine("declared methods (" + lane.declaredMethods.size + "):")
            lane.declaredMethods.sortedBy { it.name }.forEach { appendLine("  " + signature(it)) }
            val inherited = lane.methods
                .filter { it.declaringClass != lane && it.declaringClass != Any::class.java }
                .sortedBy { it.name }
            if (inherited.isNotEmpty()) {
                appendLine()
                appendLine("inherited methods (" + inherited.size + "):")
                inherited.forEach {
                    appendLine("  " + signature(it) + "  [" + it.declaringClass.simpleName + "]")
                }
            }
            if (lane.declaredFields.isNotEmpty()) {
                appendLine()
                appendLine("fields:")
                lane.declaredFields.forEach {
                    appendLine("  " + Modifier.toString(it.modifiers) + " " + it.type.simpleName + " " + it.name)
                }
            }
            if (!lane.isInterface) {
                appendLine()
                appendLine("constructors:")
                lane.declaredConstructors.forEach { constructor ->
                    appendLine("  (" + constructor.parameterTypes.joinToString(",") { it.simpleName } + ")")
                }
            }
            appendLine()
            appendLine("spy:")
            appendLine(spy(navi, lane))
        }.trimEnd()
        Log.i(TAG, "lane probe:\n$report")
        return report
    }

    private fun spy(navi: Any, lane: Class<*>): String {
        if (!lane.isInterface) {
            return "skipped: ILaneInfo is a class, not an interface; inspect the constructors above"
        }
        val arrowIds = intArrayOf(102, 103, 109)
        return buildString {
            appendLine(
                runDirect(navi, lane, "updateLaneInfo, id=-1 + Bitmap + laneNumber") { index ->
                    LaneValue(-1, index + 1, laneBitmap(index))
                },
            )
            appendLine(
                runDirect(navi, lane, "updateLaneInfo, id=0 + Bitmap + laneNumber") { index ->
                    LaneValue(0, index + 1, laneBitmap(index))
                },
            )
            appendLine(
                runDirect(navi, lane, "updateLaneInfo, id=102/103/109 + Bitmap + laneNumber") { index ->
                    LaneValue(arrowIds[index % arrowIds.size], index + 1, laneBitmap(index))
                },
            )
            // Last pass: only the three turn IDs, no Bitmap, zero-based lane numbers. It runs last
            // so the cluster keeps this exact state visible after the probe returns.
            appendLine(
                runDirect(navi, lane, "updateLaneInfo, id=102/103/109, laneNumber=0/1/2, no Bitmap") { index ->
                    LaneValue(arrowIds[index % arrowIds.size], index, null)
                },
            )
        }.trimEnd()
    }

    private fun runDirect(
        navi: Any,
        lane: Class<*>,
        label: String,
        value: (Int) -> LaneValue,
    ): String {
        val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
        val array = runCatching { spyArray(lane, value, calls) }
            .getOrElse { return "$label:\n  -> proxy failed: ${describe(it)}" }
        val result = EcarxDimApi.updateLaneInfo(navi, array)
        return renderRun(label, result, calls)
    }

    private fun spyArray(
        lane: Class<*>,
        value: (Int) -> LaneValue,
        calls: MutableList<String>,
    ): Any {
        val array = ReflectArray.newInstance(lane, SPY_LANES)
        for (index in 0 until SPY_LANES) {
            val laneValue = value(index)
            val handler = InvocationHandler { proxy, method, args ->
                val result = when (method.name) {
                    "getLaneBackIcon" -> laneValue.icon
                    "getLaneBackIconId" -> laneValue.id
                    "getLaneNumber" -> laneValue.number
                    "toString" -> "ILaneInfoProbe[$index]"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
                calls += "lane$index." + method.name + "(" + (args?.joinToString(",") ?: "") +
                    ") -> " + render(result)
                result
            }
            val spy = Proxy.newProxyInstance(lane.classLoader, arrayOf(lane), handler)
            ReflectArray.set(array, index, spy)
        }
        return array
    }

    private fun renderRun(
        label: String,
        result: EcarxDimApi.EcarxResult,
        calls: MutableList<String>,
    ): String {
        // The client normally marshals the array before the binder call returns; this sleep is
        // only for a firmware that reads the getters on a worker thread.
        if (calls.isEmpty()) Thread.sleep(100)
        val captured = synchronized(calls) { calls.toList() }
        return buildString {
            appendLine("$label:")
            appendLine("  -> " + if (result.ok) "accepted" else "failed: " + result.reason)
            if (captured.isEmpty()) {
                appendLine("  no interface call was observed")
            } else {
                captured.forEach { appendLine("  $it") }
            }
        }.trimEnd()
    }

    /** A 64x64 coloured tile with the lane number, so a rendered lane is easy to spot on the car. */
    private fun laneBitmap(index: Int): Bitmap {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = when (index) {
            0 -> Color.RED
            1 -> Color.GREEN
            else -> Color.BLUE
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.color = Color.WHITE
        paint.textSize = 36f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText((index + 1).toString(), size / 2f, size * 0.68f, paint)
        return bitmap
    }

    private fun render(value: Any?): String {
        if (value == null) return "null"
        if (value is Bitmap) return "Bitmap(" + value.width + "x" + value.height + ")"
        if (!value.javaClass.isArray) return value.toString()
        val length = ReflectArray.getLength(value)
        return (0 until length).joinToString(",", "[", "]") {
            ReflectArray.get(value, it)?.toString() ?: "null"
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private fun signature(method: Method): String {
        val flags = mutableListOf<String>()
        if (method.isDefault) flags += "default"
        if (method.isSynthetic) flags += "synthetic"
        if (method.isBridge) flags += "bridge"
        val suffix = if (flags.isEmpty()) "" else " [" + flags.joinToString(",") + "]"
        return method.name + "(" +
            method.parameterTypes.joinToString(",") { it.simpleName } + "): " +
            method.returnType.simpleName + suffix
    }

    private fun describe(error: Throwable): String {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        return cause.javaClass.simpleName + ": " + cause.message
    }
}
