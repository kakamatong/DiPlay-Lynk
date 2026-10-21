package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * The reflective face of the ECARX XUI DIM API.
 *
 * Everything here is deliberately reflection-only:
 *  * the DIM classes are head-unit system classes, so a compile-time reference would turn a
 *    missing class into `NoClassDefFoundError` at class-init time on every other head unit;
 *  * ECARX ships no public SDK jar, so the method names and descriptors below are the ones
 *    recovered from the bytecode of the two shipped ECARX apps on this head unit.
 *
 * No call in this file may throw. Each returns [EcarxResult], whose `reason` is written to the
 * session log so a refusal on an unexpected firmware is diagnosable instead of silent.
 *
 * The contract below is the **real device** contract, dumped with `Class.getMethods()` on the car
 * by probe v2 (see 适配文件/亿咖通DIM-真机契约转储.md and 日志分析/v2-run2-1.txt). It supersedes the
 * `s1/r.smali` reading we started from — that one was a *implementation class* of
 * `INavigationInfo`, and it carries two extra public methods the interface never declared
 * (`getDayNightMode`, `getExtensionInfo`). The device interface has 13 getters, not 15.
 *
 * ```
 * DimInteraction.create(Context)                                        static -> DimInteraction (may be null)
 * DimInteraction.getNaviInteraction()                                   -> INaviInteraction
 * DimInteraction.getMediaInteraction()                                  -> IMediaInteraction
 * DimInteraction.getPhoneCallInteraction()                              -> IPhoneCallInteraction
 * DimInteraction.getShowPresentationOption()                            -> int   (the only read-back we found)
 * DimInteraction.setPresentationToDimSwitch(int,String,String,boolean)  <- 仪表投屏开关, returns void
 * INaviInteraction.updateNavigationInfo(INavigationInfo)
 * INaviInteraction.updateTurnByTurnArrow(int) | (String) | (Bitmap)
 *   (the live path uses String; int and Bitmap are calibration-only)
 * INaviInteraction.updateDistanceToNextGuidancePoint(int)
 * INaviInteraction.updateDistanceToDestination(int)
 * INaviInteraction.updateETA(int)
 * INaviInteraction.updateNextGuidancePointName(String)
 * INaviInteraction.updateLaneInfo(ILaneInfo[])
 * INaviInteraction.updateDriveDirection(int)   /  updateMuteState(int)
 * INaviInteraction.notifyTurnByTurnStarted()  /  notifyTurnByTurnStopped()
 * INaviInteraction$INavigationInfo getters (13 on the device):
 *        getNavigationTurnId():int         getDistanceToNextGuidancePoint():long
 *        getDistanceToDestination():long   getDrivingDirection():int
 *        getETA():long                     getLaneInfo():ILaneInfo[]
 *        getMuteState():int                getNavigationStatus():int
 *        getNextGuidancePointName():String getNavigationTurnSVG():String
 *        getHighwayExitInfo() / getRoadCameraInfo() / getServiceAreaInfo()
 * ```
 *
 * Not in the contract, because it does not exist on the target head unit:
 *  * `INaviInteraction.notifyNavigationStatus(int)` — absent (probe printed "未找到重载").
 *  * `ecarx.dimprotocol.DIMProtocolManager` — the class is not in the system image at all, so
 *    [setNaviMode] can only ever fail there. See [EcarxOutputSettings.naviModeSupported].
 *
 * **Return values carry no authority.** The probe showed a normal-signed build with every ECARX
 * permission denied gets byte-identical results — `create()`, `setPresentationToDimSwitch()` and
 * `updateNavigationInfo()` all report success. These are `void` methods and the platform does not
 * throw on refusal, so [EcarxResult.ok] means "the call was accepted for dispatch", never "the
 * cluster changed". Only the car can answer the latter.
 */
internal object EcarxDimApi {
    private const val TAG = "DiPlay-Ecarx-DIM"

    /** The screen id the stock ECARX apps pass; both of them hardcode `1`. */
    const val DEFAULT_SCREEN_ID = 1

    data class EcarxResult(val ok: Boolean, val reason: String) {
        companion object {
            fun ok(what: String) = EcarxResult(true, what)
            fun failed(what: String) = EcarxResult(false, what)
        }
    }

    /** One navigation frame in ECARX vocabulary. `-1` means "this field has no new value". */
    data class Guidance(
        val turnId: Int = 0,
        val distanceToNextMeters: Long = -1,
        val distanceToDestinationMeters: Long = -1,
        val etaEpochSeconds: Long = -1,
        val drivingDirection: Int = 0,
        val status: Int = 0,
        val muteState: Int = 0,
        val dayNightMode: Int = 0,
        val nextGuidancePointName: String = "",
        val navigationTurnSvg: String = "",
        /** Diagnostic-only lane payload for the ILaneInfo probe; live guidance leaves it null. */
        val laneInfo: Any? = null,
    )

    // ---------------------------------------------------------------- DimInteraction

    fun createDimInteraction(context: Context): Any? = runCatching {
        val cls = Class.forName(EcarxCapabilities.DIM_INTERACTION)
        cls.getMethod("create", Context::class.java).invoke(null, context)
    }.getOrElse {
        Log.i(TAG, "DimInteraction.create failed: ${describe(it)}")
        null
    }

    fun naviInteraction(dim: Any): Any? = read(dim, "getNaviInteraction")

    fun mediaInteraction(dim: Any): Any? = read(dim, "getMediaInteraction")

    fun phoneCallInteraction(dim: Any): Any? = read(dim, "getPhoneCallInteraction")

    /**
     * The 仪表投屏 switch. On this head unit both stock ECARX apps call
     * `setPresentationToDimSwitch(1, "", "", true/false)` — the two strings are always empty,
     * so the platform does not use them for identification here.
     */
    fun setPresentationToDimSwitch(
        dim: Any,
        show: Boolean,
        screenId: Int = DEFAULT_SCREEN_ID,
    ): EcarxResult = call(
        dim,
        "setPresentationToDimSwitch",
        arrayOf(
            Int::class.javaPrimitiveType!!,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        ),
        arrayOf<Any?>(screenId, "", "", show),
        "setPresentationToDimSwitch($screenId,\"\",\"\",$show)",
    )

    // ---------------------------------------------------------------- INaviInteraction

    fun updateNavigationInfo(navi: Any, guidance: Guidance): EcarxResult {
        val itf = runCatching { Class.forName(EcarxCapabilities.NAVI_INFO) }.getOrNull()
            ?: return EcarxResult.failed("INavigationInfo class missing")
        val info = runCatching {
            Proxy.newProxyInstance(itf.classLoader, arrayOf(itf), NaviInfoHandler(guidance))
        }.getOrElse { return EcarxResult.failed("INavigationInfo proxy failed: ${describe(it)}") }
        return call(navi, "updateNavigationInfo", arrayOf(itf), arrayOf<Any?>(info), "updateNavigationInfo")
    }

    /**
     * The numeric `updateTurnByTurnArrow(int)` overload. The live guidance push does not use it
     * (the code table is a demo ramp on this firmware); it exists for the Settings calibration
     * sweep only.
     */
    fun updateTurnByTurnArrow(navi: Any, arrow: Int): EcarxResult =
        callInt(navi, "updateTurnByTurnArrow", arrow)

    /**
     * Pushes a drawn arrow bitmap through the `(Bitmap)` overload. This is the code-table-free path:
     * `turnId` has no recovered mapping on this head unit (the shipped apps feed it a demo counter),
     * but the DIM interface also accepts a `Bitmap`, so a locally drawn arrow bypasses the unknown
     * int code table entirely (see 适配文件/亿咖通DIM-真机契约转储.md).
     *
     * No production path uses this overload; it is kept for the Settings arrow test.
     */
    fun updateTurnByTurnArrow(navi: Any, arrow: android.graphics.Bitmap): EcarxResult =
        call(navi, "updateTurnByTurnArrow", arrayOf(android.graphics.Bitmap::class.java), arrayOf<Any?>(arrow),
            "updateTurnByTurnArrow(Bitmap)")

    /** The arrow channel the live guidance push uses: a drawn SVG icon as a String. */
    fun updateTurnByTurnArrow(navi: Any, svg: String): EcarxResult =
        call(navi, "updateTurnByTurnArrow", arrayOf(String::class.java), arrayOf<Any?>(svg),
            "updateTurnByTurnArrow(String)")

    fun updateDistanceToNextGuidancePoint(navi: Any, meters: Long): EcarxResult =
        callInt(navi, "updateDistanceToNextGuidancePoint", meters.toInt())

    fun updateDistanceToDestination(navi: Any, meters: Long): EcarxResult =
        callInt(navi, "updateDistanceToDestination", meters.toInt())

    fun updateEta(navi: Any, epochSeconds: Long): EcarxResult =
        callInt(navi, "updateETA", epochSeconds.toInt())

    fun updateNextGuidancePointName(navi: Any, name: String): EcarxResult =
        call(navi, "updateNextGuidancePointName", arrayOf(String::class.java), arrayOf<Any?>(name),
            "updateNextGuidancePointName(\"$name\")")

    /** Pushes one reflected `ILaneInfo[]` (used by the empty clear and by the lane probe). */
    fun updateLaneInfo(navi: Any, lanes: Any): EcarxResult {
        val count = runCatching { java.lang.reflect.Array.getLength(lanes) }.getOrDefault(0)
        return call(
            navi,
            "updateLaneInfo",
            arrayOf(lanes.javaClass),
            arrayOf<Any?>(lanes),
            "updateLaneInfo($count lanes)",
        )
    }

    /** Clears the lane strip. An empty typed array is what the stock apps send when none remain. */
    fun updateLaneInfoEmpty(navi: Any): EcarxResult {
        val lane = runCatching { Class.forName(EcarxCapabilities.LANE_INFO) }.getOrNull()
            ?: return EcarxResult.failed("ILaneInfo class missing")
        val empty = runCatching { java.lang.reflect.Array.newInstance(lane, 0) }
            .getOrElse { return EcarxResult.failed("ILaneInfo[] allocation failed: ${describe(it)}") }
        return updateLaneInfo(navi, empty)
    }

    fun notifyTurnByTurnStarted(navi: Any): EcarxResult = call0(navi, "notifyTurnByTurnStarted")

    fun notifyTurnByTurnStopped(navi: Any): EcarxResult = call0(navi, "notifyTurnByTurnStopped")

    // There is deliberately no `notifyNavigationStatus` here: the real device's INaviInteraction
    // does not declare it (probe v2 printed "未找到 notifyNavigationStatus / 1 个参数的重载").

    /**
     * `DimInteraction.getShowPresentationOption()` — the only read-back the probe turned up.
     *
     * The car needs it because every write path returns `void` and is not permission-checked: a
     * normal-signed build with all ECARX permissions denied gets the same "success" as the
     * platform-signed one (probe v2, see [EcarxResult]). So a write proves nothing by itself, and
     * this is the one call that might say what the cluster is actually set to.
     *
     * Nothing tells us how to read the int yet, so it is only surfaced, never interpreted.
     */
    fun showPresentationOption(dim: Any): Int? = runCatching {
        lookup(dim, "getShowPresentationOption", emptyArray())?.invoke(dim) as? Int
    }.getOrNull()

    // ---------------------------------------------------------------- IPhoneCallInteraction

    /** One call record in ECARX vocabulary. `-1`/empty means "no value", matching the stock apps. */
    data class CallInfo(
        val callId: Int = 0,
        val activeCallId: Int = 0,
        val callCount: Int = 0,
        val callStatus: Int = 0,
        val contactName: String = "",
        val contactNumber: String = "",
    )

    /**
     * Reports the current CarPlay call to the cluster's phone interaction. The reference app on this
     * head unit builds the same `ICallInfo` and calls `updateCallInfo` whenever a call's state
     * changes, so the instrument cluster can show the number / name / status.
     */
    fun updateCallInfo(phone: Any, info: CallInfo): EcarxResult {
        val itf = runCatching { Class.forName(EcarxCapabilities.CALL_INFO) }.getOrNull()
            ?: return EcarxResult.failed("ICallInfo class missing")
        val call = runCatching {
            Proxy.newProxyInstance(itf.classLoader, arrayOf(itf), CallInfoHandler(info))
        }.getOrElse { return EcarxResult.failed("ICallInfo proxy failed: ${describe(it)}") }
        return call(phone, "updateCallInfo", arrayOf(itf), arrayOf<Any?>(call), "updateCallInfo")
    }

    /**
     * Reports the connected mobile device state. The stock app calls
     * `updateConnectedMobileDeviceState(String, int, int)`; the first int is the device state and
     * the second a feature bitmap, both passed as zero by the shell for the plain connected case.
     */
    fun updateConnectedMobileDeviceState(phone: Any, name: String, state: Int = 0, feature: Int = 0): EcarxResult =
        call(
            phone,
            "updateConnectedMobileDeviceState",
            arrayOf(String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            arrayOf<Any?>(name, state, feature),
            "updateConnectedMobileDeviceState(\"$name\",$state,$feature)",
        )

    // ---------------------------------------------------------------- DIMProtocolManager

    fun dimProtocolManager(context: Context): Any? = runCatching {
        val cls = Class.forName(EcarxCapabilities.DIM_PROTOCOL_MANAGER)
        val getInstance = cls.getMethod("getInstance", Context::class.java)
        if (Modifier.isStatic(getInstance.modifiers)) {
            getInstance.invoke(null, context)
        } else {
            val ctor = cls.getDeclaredConstructor().also { it.isAccessible = true }
            getInstance.invoke(ctor.newInstance(), context)
        }
    }.getOrElse {
        Log.i(TAG, "DIMProtocolManager.getInstance failed: ${describe(it)}")
        null
    }

    /** `setNaviMode` — the switch that makes the cluster accept an external navigation source. */
    fun setNaviMode(manager: Any, mode: Int): EcarxResult = callInt(manager, "setNaviMode", mode)

    // ---------------------------------------------------------------- plumbing

    private fun call0(target: Any, name: String): EcarxResult =
        call(target, name, emptyArray(), emptyArray(), name)

    private fun callInt(target: Any, name: String, value: Int): EcarxResult =
        call(target, name, arrayOf(Int::class.javaPrimitiveType!!), arrayOf<Any?>(value), "$name($value)")

    private fun call(target: Any, name: String, params: Array<Class<*>>, args: Array<Any?>, label: String): EcarxResult {
        val method = lookup(target, name, params) ?: return EcarxResult.failed("$label: method not found")
        return runCatching { method.invoke(target, *args) }
            .fold({ EcarxResult.ok(label) }, { EcarxResult.failed("$label threw ${describe(it)}") })
    }

    private fun read(target: Any, name: String): Any? {
        val method = lookup(target, name, emptyArray()) ?: return null
        return runCatching { method.invoke(target) }.getOrElse {
            Log.i(TAG, "$name failed: ${describe(it)}")
            null
        }
    }

    private fun lookup(target: Any, name: String, params: Array<Class<*>>): Method? =
        runCatching { target.javaClass.getMethod(name, *params) }
            .recoverCatching {
                target.javaClass.getDeclaredMethod(name, *params).also { it.isAccessible = true }
            }
            .getOrNull()

    /** Unwraps the reflection wrapper so the log line names the real failure. */
    private fun describe(error: Throwable): String {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    /** Answers the getter set the platform reads off [EcarxCapabilities.NAVI_INFO]. */
    private class NaviInfoHandler(private val g: Guidance) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getNavigationTurnId" -> g.turnId
            "getDistanceToNextGuidancePoint" -> g.distanceToNextMeters.coerceAtLeast(0L)
            "getDistanceToDestination" -> g.distanceToDestinationMeters.coerceAtLeast(0L)
            "getETA" -> g.etaEpochSeconds.coerceAtLeast(0L)
            "getDrivingDirection" -> g.drivingDirection
            "getNavigationStatus" -> g.status
            "getMuteState" -> g.muteState
            "getDayNightMode" -> g.dayNightMode
            "getNextGuidancePointName" -> g.nextGuidancePointName
            "getLaneInfo" -> g.laneInfo
            "getExtensionInfo" -> null
            "getHighwayExitInfo" -> null
            "getRoadCameraInfo" -> null
            "getServiceAreaInfo" -> null
            "getNavigationTurnSVG" -> g.navigationTurnSvg
            // toString/hashCode/equals must stay sane so the platform can log the object.
            "toString" ->
                "EcarxNavigationInfo(turn=${g.turnId} next=${g.distanceToNextMeters} dest=${g.distanceToDestinationMeters})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> defaultValue(method.returnType)
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
    }

    /** Answers the getter set the platform reads off [EcarxCapabilities.CALL_INFO]. */
    private class CallInfoHandler(private val c: CallInfo) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getCallId" -> c.callId
            "getActiveCallId" -> c.activeCallId
            "getCallCount" -> c.callCount
            "getCallStatus" -> c.callStatus
            "getContactName" -> c.contactName
            "getContactNumber" -> c.contactNumber
            "getAvatar" -> null
            "toString" ->
                "EcarxCallInfo(id=${c.callId} status=${c.callStatus} name=${c.contactName} number=${c.contactNumber})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> defaultValue(method.returnType)
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
    }
}
