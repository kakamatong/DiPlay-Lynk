package com.shilapi.xcertplay.hud

import android.content.Context

/**
 * The ECARX instrument-cluster output: one switch plus the two OEM-side toggles it needs.
 *
 * Kept in its own preferences file so the ECARX switches never alias another head unit's switches
 * by accident, and so a build that ships the same code on a different head unit has an inert,
 * empty store.
 */
object EcarxOutputSettings {
    private const val PREFS = "diplay_ecarx_outputs"
    private const val KEY_ENABLED = "dim_navigation_enabled"
    private const val KEY_PRESENTATION = "dim_presentation_switch"
    private const val KEY_NAVI_MODE = "dim_navi_mode"
    private const val KEY_ARROW_EXPERIMENTAL = "dim_turn_arrow_experimental"

    /**
     * NAVI_MODE_OFF leaves the cluster's own navigation source alone. Every other value asks the
     * platform to prefer the external source; the stock ECARX apps only ever use "off"/"on", and
     * the exact meaning of the remaining ids is unverified, so only 0 and 2 are offered in the UI.
     */
    const val NAVI_MODE_OFF = 0
    const val NAVI_MODE_ON = 2

    /**
     * "The user has never asked for anything." Distinct from [NAVI_MODE_OFF] on purpose:
     * `setNaviMode` comes from `ecarx.dimprotocol.DIMProtocolManager`, which is referenced only by
     * the reference app that is *not* installed on the target head unit — weaker evidence than the DIM
     * path itself. So an untouched install never calls it at all, and no value we cannot explain
     * is ever written to the platform.
     */
    const val NAVI_MODE_UNSET = -1

    /** Whether this head unit exposes the ECARX DIM interface (and therefore the settings block). */
    fun available(context: Context): Boolean = EcarxCapabilities.available(context)

    /**
     * Whether the head unit ships `ecarx.dimprotocol.DIMProtocolManager` at all.
     *
     * The target unit does not: probe v2 found 23 of 26 ECARX classes but this one (and its
     * `IDIMProtocolManager`) is absent from the system image, so [requestNaviMode] can only ever
     * fail there. The settings screen hides the switch instead of offering one that cannot work.
     */
    fun naviModeSupported(): Boolean =
        EcarxCapabilities.classPresent(EcarxCapabilities.DIM_PROTOCOL_MANAGER)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()) {
            "Could not persist the ECARX navigation output setting"
        }
    }

    /** The 仪表投屏 switch: mirrors the centre screen's presentation onto the cluster. */
    fun presentationSwitch(context: Context): Boolean = prefs(context).getBoolean(KEY_PRESENTATION, true)

    fun setPresentationSwitch(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_PRESENTATION, enabled).commit()) {
            "Could not persist the ECARX instrument-cluster projection setting"
        }
    }

    /** `DIMProtocolManager.setNaviMode`. Applies at once and survives a reconnect. */
    fun naviMode(context: Context): Int = prefs(context).getInt(KEY_NAVI_MODE, NAVI_MODE_UNSET)

    fun setNaviMode(context: Context, mode: Int) {
        check(prefs(context).edit().putInt(KEY_NAVI_MODE, mode).commit()) {
            "Could not persist the ECARX cluster navigation mode"
        }
    }

    /**
     * Whether the CarPlay maneuver is also pushed as an ECARX turn-ID icon. On by default: it is
     * the only arrow channel this port uses now, so without it the cluster falls back to whatever
     * arrow it derives from [EcarxHudBridge]'s navigation payload.
     */
    fun turnArrowExperimental(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ARROW_EXPERIMENTAL, true)

    fun setTurnArrowExperimental(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_ARROW_EXPERIMENTAL, enabled).commit()) {
            "Could not persist the ECARX turn-arrow setting"
        }
    }

    // ---------------------------------------------------------------- live apply

    /**
     * The settings screen lives in the `common` module, so it cannot reach the `internal`
     * [EcarxHudBridge] directly. These delegates wire a switch straight to the head unit and
     * return whether the platform accepted the call.
     */
    fun requestPresentation(context: Context, show: Boolean): Boolean {
        EcarxHudBridge.initialize(context)
        return EcarxHudBridge.applyPresentation(show).ok
    }

    fun requestNaviMode(context: Context, mode: Int): Boolean {
        EcarxHudBridge.initialize(context)
        return EcarxHudBridge.applyNaviMode(mode).ok
    }

    fun diagnosticReport(context: Context): String = EcarxHudBridge.diagnostics(context)

    // ---------------------------------------------------------------- arrow-channel tests (diagnostic)

    /**
     * Runs one arrow channel for calibration: turn IDs 100..200 (in two ranges), or the basic
     * maneuvers through the bitmap or SVG overload, one step every 2 seconds. Applies at once,
     * leaves the saved settings alone, and normal guidance resumes when the test ends. [onStep]
     * runs on a worker thread.
     */
    fun startArrowTest(
        context: Context,
        kind: EcarxArrowTestKind,
        onStep: (EcarxArrowTestStep) -> Unit,
    ): Boolean {
        EcarxHudBridge.initialize(context)
        return EcarxHudBridge.startArrowTest(kind, onStep)
    }

    fun stopArrowTest() = EcarxHudBridge.stopArrowTest()

    /** The last step a test pushed, or null when none has run in this process. */
    fun arrowTestStep(): EcarxArrowTestStep? = EcarxHudBridge.arrowTestStep()

    // ---------------------------------------------------------------- lane probe (diagnostic)

    /**
     * Dumps the `ILaneInfo` contract and runs spy passes through both lane entry points.
     * [onResult] runs on a worker thread.
     */
    fun probeLaneInfo(context: Context, onResult: (String) -> Unit) {
        EcarxHudBridge.probeLaneInfo(context, onResult)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
