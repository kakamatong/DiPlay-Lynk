package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Build

/**
 * Detects the ECARX (亿咖通) XUI AdaptAPI on the head unit.
 *
 * The DIM classes live in the head unit's system image and are never packaged in this APK, so
 * every probe here is a `Class.forName` against the running platform, and every call site must
 * tolerate their absence. The contract was recovered from the bytecode of the two shipped
 * ECARX apps on this head unit (see 适配文件/亿咖通DIM-HUD接口规格-可吸收进DiPlay.md and
 * 盒子app分析/脱壳/HUD-反编译分析报告.md); nothing here is inferred from documentation.
 */
object EcarxCapabilities {
    const val DIM_INTERACTION = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"
    const val NAVI_INTERACTION = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction"
    const val NAVI_INFO = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$INavigationInfo"
    const val LANE_INFO = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$ILaneInfo"
    const val MEDIA_INTERACTION = "com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction"
    const val PHONE_INTERACTION = "com.ecarx.xui.adaptapi.diminteraction.IPhoneCallInteraction"
    const val CALL_INFO = "com.ecarx.xui.adaptapi.diminteraction.IPhoneCallInteraction\$ICallInfo"
    const val DIM_PROTOCOL_MANAGER = "ecarx.dimprotocol.DIMProtocolManager"
    const val CAR = "com.ecarx.xui.adaptapi.car.Car"
    const val DEVICE = "com.ecarx.xui.adaptapi.device.Device"

    // Microphone arbitration lives on the SDK policy API, not the XUI adaptapi. The reference app
    // uses `com.ecarx.sdk.policy.PolicyAPI`; the EAS one is the
    // SDK-side alias that also surfaces IAudioPolicy. Both are probed, the first match wins.
    const val POLICY_API = "com.ecarx.sdk.policy.PolicyAPI"
    const val EAS_POLICY_API = "com.ecarx.eas.sdk.policy.api.PolicyAPI"
    const val AUDIO_POLICY = "com.ecarx.sdk.policy.IAudioPolicy"
    const val MIC_CALLBACK = "com.ecarx.sdk.policy.IMicResourceCallback"

    /** Packages that only exist on an ECARX-built head unit. */
    private val ecarxPackages = listOf(
        "com.ecarx.xui.service",
        "com.ecarx.eas.service",
        "com.ecarx.assistant",
        "com.ecarx.xiaoka",
        "ecarx.naviservice",
    )

    /** Build fields that identify the vendor image when no package survives de-bloating. */
    private val ecarxBuildMarkers = listOf(
        Build.BRAND, Build.MANUFACTURER, Build.PRODUCT, Build.DEVICE, Build.HARDWARE, Build.FINGERPRINT,
    )

    fun classPresent(name: String): Boolean = runCatching { Class.forName(name) }.isSuccess

    /** The DIM interaction entry point — the only class the whole feature depends on. */
    fun dimPresent(): Boolean = classPresent(DIM_INTERACTION)

    /**
     * Whether this head unit exposes the ECARX DIM interface at all. A `null` from
     * `DimInteraction.create()` still means the platform has it, so the switch stays visible and
     * the diagnostics explain the refusal instead of silently hiding the feature.
     */
    fun available(context: Context): Boolean {
        if (dimPresent()) return true
        if (ecarxBuildMarkers.any { it.contains("ecarx", ignoreCase = true) }) return true
        return ecarxPackages.any { installed(context, it) }
    }

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** One-line state for logs and the diagnostics report. */
    fun describe(context: Context): String = buildString {
        append("dim=").append(classPresent(DIM_INTERACTION))
        append(" navi=").append(classPresent(NAVI_INTERACTION))
        append(" naviInfo=").append(classPresent(NAVI_INFO))
        append(" media=").append(classPresent(MEDIA_INTERACTION))
        append(" phone=").append(classPresent(PHONE_INTERACTION))
        append(" dimProtocol=").append(classPresent(DIM_PROTOCOL_MANAGER))
        append(" car=").append(classPresent(CAR))
        append(" device=").append(classPresent(DEVICE))
        append(" brand=").append(Build.BRAND)
        append(" product=").append(Build.PRODUCT)
        append(" available=").append(available(context))
    }
}
