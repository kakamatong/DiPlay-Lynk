package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Publishes CarPlay guidance to an ECARX (亿咖通) instrument cluster through the XUI DIM API.
 *
 * The field order is the one the shipped ECARX toolkit uses, recovered from
 * `tools-apktool/smali/s1/n.smali` — a Runnable the toolkit posts every 1000 ms:
 * ```
 * navi.updateNavigationInfo(info); navi.updateTurnByTurnArrow(v++);
 * ```
 * This port pushes on route updates instead of replaying that loop; the 1 s ticker only maintains
 * the projection watch, retries the DIM handles and retires stale guidance.
 *
 * What is proven vs. what needs calibration on the car:
 *  * **Proven.** `updateNavigationInfo` with the 15-getter `INavigationInfo` payload; the getter
 *    set is exhaustive (`s1/r.smali` implements exactly these); `updateDistanceToDestination`,
 *    `updateDistanceToNextGuidancePoint`, `updateETA`, `updateNextGuidancePointName`,
 *    `updateLaneInfo`, `notifyTurnByTurnStarted/Stopped` take the arguments used below.
 *  * **Needs calibration.** `getNavigationTurnId()` is a bare `int`; the toolkit's counter is a
 *    demo ramp, so this port feeds it the ECARX turn-icon ID (or the last one when a maneuver has
 *    no ID). `getDrivingDirection()` has no recovered scale and stays a documented placeholder.
 *    The turn-ID icon is the arrow channel pushed explicitly; the SVG helper remains for the
 *    Settings calibration test.
 *
 * Every ECARX call is reflection-only (see [EcarxDimApi]): a direct reference would turn a
 * missing system class into a `NoClassDefFoundError` on every non-ECARX head unit.
 */
internal object EcarxHudBridge {
    private const val TAG = "DiPlay-ECARX-HUD"

    /**
     * The arrow tests offered in Settings. [EcarxArrowTestKind.TURN_ID] sweeps 100..130 and
     * [EcarxArrowTestKind.TURN_ID_HIGH] sweeps 131..200 through the numeric channel; the bitmap
     * and SVG tests walk [EcarxArrowShape], one step every [ARROW_TEST_STEP_MILLIS].
     */
    private const val TURN_ID_TEST_FIRST = 100
    private const val TURN_ID_TEST_LAST = 130
    private const val TURN_ID_HIGH_TEST_FIRST = 131
    private const val TURN_ID_HIGH_TEST_LAST = 200
    private const val ARROW_TEST_STEP_MILLIS = 2_000L

    /**
     * `getNavigationStatus()` while guidance is live. The toolkit copies its own value through
     * unchanged, so the two states are the only ones recovered; 0 is used for idle on teardown.
     */
    private const val NAVI_STATUS_ACTIVE = 1

    /** Apple's LaneGuidanceInfoUpdate: per-lane direction + highlight state. */
    private const val LANE_GUIDANCE_UPDATE = 0x5204
    private const val NAVI_STATUS_IDLE = 0

    /**
     * `drivingDirection` is deliberately left at 0. The field is a plain `int` in `s1/r`, its
     * source value is the toolkit's own, and no scale was recovered — passing Apple's
     * left/right-hand-traffic flag here would be a guess dressed up as data.
     */
    private const val DRIVING_DIRECTION_UNKNOWN = 0

    /**
     * The projection watch. `setPresentationToDimSwitch` is a **runtime** call, not a saved
     * setting: the cluster service is torn down with the ignition and comes back with it, so a
     * single call at app start is thrown away on a car that is only on accessory power — which is
     * exactly how a head unit boots. Measured on the car (领克 DCY11): with the engine off the
     * cluster is absent and the call does nothing, and nothing re-sent it once the engine started.
     *
     * So the bridge keeps re-asserting the switch: once every [PRESENTATION_FAST_SECONDS] until
     * [PRESENTATION_FAST_ATTEMPTS] tries are spent (the cluster usually shows up as soon as the
     * engine does), then every [PRESENTATION_SLOW_SECONDS] for as long as the user leaves the
     * switch on. Both are cheap `void` binder calls, and the slow phase is what covers "the driver
     * started the car ten minutes after the head unit woke up".
     */
    private const val PRESENTATION_FAST_SECONDS = 2
    private const val PRESENTATION_FAST_ATTEMPTS = 30
    private const val PRESENTATION_SLOW_SECONDS = 10

    private val lock = Any()
    private val route = RouteGuidanceState(
        keepAcrossGaps = true,
        onManeuver = { index, type, side, road -> recordManeuver(index, type, side, road) },
        onManeuverList = { indices, active -> recordManeuverList(indices, active) },
    )
    private var context: Context? = null
    private var available = false
    private var dim: Any? = null
    private var navi: Any? = null
    private var protocol: Any? = null
    private var tickerStarted = false
    private var displayWatchRegistered = false

    /** The saved projection intent, and how many ticks are left before it is re-asserted. */
    private var presentationWanted = false
    private var presentationCountdown = 0
    private var presentationAttempts = 0

    private var lastPushed: EcarxDimApi.Guidance? = null
    private var guidanceActive = false

    // Arrow-channel test state. While one runs the normal push path stays silent, so the cluster
    // shows only the value under test; the route state keeps updating in the background.
    private var testRunning = false
    private var testKind = EcarxArrowTestKind.TURN_ID
    private var testIndex = 0
    private var testTotal = 0
    private var testLastStep: EcarxArrowTestStep? = null
    /** Bumped on every start, so a task queued by an earlier run cannot push into the new one. */
    private var testGeneration = 0
    private var testFuture: ScheduledFuture<*>? = null
    private var testListener: ((EcarxArrowTestStep) -> Unit)? = null
    private val testExecutor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diplay-ecarx-arrow-test").apply { isDaemon = true }
        }
    }

    // Lane-guidance probe. The cluster draws every lane bright because we send no lane data yet;
    // one capture of the 0x5204 payload plus the ILaneInfo method list is enough to map it.
    private var laneFrames = 0
    private var laneHex: String? = null
    /** Last maneuver type with no deterministic arrow mapping, for the next report. */
    private var lastUnmappedArrowType: Int? = null
    private var lastNaviPush = "none"
    private var lastArrowPush = "none"
    private var lastArrowChannel = "none"
    private var lastArrowType = "none"
    private var lastArrowDirection = "none"
    private var lastArrowCode = "none"
    private var lastArrowCodeValue = 0
    /** Parsed 0x5202 maneuvers with a human type label, for the diagnostic report. */
    private val maneuverTimeline = ArrayDeque<String>()
    /** Parsed 0x5201 maneuver lists, only when the active index/list changes. */
    private val maneuverListTimeline = ArrayDeque<String>()
    private var lastManeuverListKey = ""
    /** Last handful of arrow pushes, so a straight segment can be told apart from a cleared route. */
    private val arrowTimeline = ArrayDeque<String>()
    private var lastGuidanceTurnId = "none"
    private var lastGuidanceDrivingDirection = "none"
    private var laneMethodNames: String? = null
    /** The 1 s lane-read probe: idle/empty/data/no-getter, and the last compact readout. */
    private var lanePollState = "idle"
    private var lastLanePoll = "none"

    /** The `ILaneInfo` method signatures, for the diagnostic report. */
    private fun laneMethods(): String {
        laneMethodNames?.let { return it }
        val names = runCatching {
            Class.forName(EcarxCapabilities.LANE_INFO).declaredMethods
                .sortedBy { it.name }
                .joinToString(",") { method ->
                    method.name + "(" + method.parameterTypes.joinToString(",") { it.simpleName } +
                        "):" + method.returnType.simpleName
                }
        }.getOrElse { "missing:" + it.javaClass.simpleName }
        laneMethodNames = names
        return names
    }

    /**
     * A screen appearing or disappearing is the closest thing to "the car was just started" that a
     * third-party app can observe, so it cancels the projection backoff instead of making the
     * cluster wait for the next slow retry. On this head unit the cluster screen is not in
     * `getDisplays()` at all (probe v4), so this is a hint, never the mechanism.
     */
    private val displayWatch = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = nudgeProjection("display $displayId added")
        override fun onDisplayRemoved(displayId: Int) = nudgeProjection("display $displayId removed")
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    /** Resolves the DIM handles once per process. Safe to call from any thread; idempotent. */
    fun initialize(appContext: Context) = synchronized(lock) {
        if (context != null) return@synchronized
        val app = appContext.applicationContext
        context = app
        available = EcarxCapabilities.available(app)
        if (!available) {
            Log.i(TAG, "DIM interface absent, output disabled: ${EcarxCapabilities.describe(app)}")
            return@synchronized
        }
        // Seed the watch from the saved intent before the first attempt, so a head unit that comes
        // back with the engine already running restores the cluster without the user touching it.
        presentationWanted = EcarxOutputSettings.presentationSwitch(app)
        reclaimLocked()
        // A new process must not inherit an arrow left on the cluster by the previous one.
        endLocked()
        watchDisplaysLocked(app)
        Log.i(
            TAG,
            "DIM ready navi=${navi != null} protocol=${protocol != null} " +
                "projection=$presentationWanted ${EcarxCapabilities.describe(app)}",
        )
        // Re-assert the persisted OEM-side switches so a restart restores the cluster state.
        // Only the projection switch: setNaviMode is an unverified call (see NAVI_MODE_UNSET) and
        // is left alone until the user actually asks for it.
        EcarxOutputSettings.naviMode(app)
            .takeIf { it >= 0 }
            ?.let { applyNaviModeLocked(it) }
        if (presentationWanted) requestPresentationLocked()
        startTickerLocked()
    }

    /**
     * (Re)resolves the DIM handles. Retried by the ticker while anything is still missing:
     * `DimInteraction.create()` hands back `null` until the cluster service is up, and on a car
     * that is switched on with the engine off — the state every head unit boots in — that is
     * exactly what happens. Unlike [available], which is a class-presence test and cannot change,
     * these handles can appear later, so the retry is the point.
     */
    private fun reclaimLocked(): Boolean {
        val app = context ?: return false
        if (dim == null) dim = EcarxDimApi.createDimInteraction(app)
        val interaction = dim ?: return false
        if (navi == null) navi = EcarxDimApi.naviInteraction(interaction)
        if (protocol == null) protocol = EcarxDimApi.dimProtocolManager(app)
        return true
    }

    private fun watchDisplaysLocked(app: Context) {
        if (displayWatchRegistered) return
        displayWatchRegistered = runCatching {
            val manager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            manager.registerDisplayListener(displayWatch, Handler(Looper.getMainLooper()))
            true
        }.getOrElse {
            Log.i(TAG, "display watch unavailable: ${it.javaClass.simpleName}")
            false
        }
    }

    private fun nudgeProjection(reason: String) = synchronized(lock) {
        if (!presentationWanted) return@synchronized
        Log.i(TAG, "projection watch: $reason")
        presentationCountdown = 0
    }

    private fun startTickerLocked() {
        if (tickerStarted) return
        tickerStarted = true
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diplay-ecarx-hud").apply { isDaemon = true }
        }.scheduleAtFixedRate(::tick, 1, 1, TimeUnit.SECONDS)
    }

    fun onFrame(frame: Iap2Frame) = synchronized(lock) {
        if (!available) return@synchronized
        if (frame.messageId == LANE_GUIDANCE_UPDATE) {
            laneFrames += 1
            if (laneHex == null) {
                laneHex = frame.payload.take(128).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                Log.i(TAG, "lane guidance frame bytes=${frame.payload.size} head=${laneHex}")
            }
        }
        val change = route.accept(frame.messageId, frame.payload)
        if (frame.messageId == LANE_GUIDANCE_UPDATE) {
            val lane = route.laneSnapshot()
            val laneText = lane.lanes.joinToString(";") { item ->
                "pos=" + item.position + " status=" + item.status + " dir=" + item.direction +
                    " angles=" + item.angles.joinToString("/") + " hl=" + item.highlighted
            }
            Log.i(TAG, "lane guidance index=" + lane.eventIndex + " showing=" + lane.showing +
                " total=" + lane.total + " complete=" + lane.complete +
                " desc=\"" + lane.description + "\" lanes=" + laneText + " tlvs=" + lane.tlvDump)
        }
        // A running test owns the arrow channel; frames are still recorded so the normal path
        // resumes with the right maneuver as soon as it ends.
        if (testRunning) return@synchronized
        when (change) {
            RouteGuidanceChange.GUIDANCE -> pushLocked()
            RouteGuidanceChange.CLEAR -> if (lastPushed != null) endLocked()
            RouteGuidanceChange.NONE -> Unit
        }
    }

    fun clear() = synchronized(lock) {
        route.clear() // Drop retained route state so nothing restores it after cleanup.
        // Send the clear even when this process never pushed: after a restart the cluster may
        // still show the arrow left by the previous process. A running test owns the arrow, so
        // the teardown waits until it ends.
        if (!testRunning) endLocked()
    }

    /**
     * Runtime-only projection off: stop mirroring when a CarPlay session ends without touching
     * the saved setting. [resumePresentation] re-arms it on the next session.
     */
    fun suspendPresentation() = synchronized(lock) {
        presentationWanted = false
        presentationAttempts = 0
        presentationCountdown = 0
        applyPresentationLocked(false)
    }

    /** Re-arm the saved projection intent when a new CarPlay session starts. */
    fun resumePresentation() = synchronized(lock) {
        val app = context ?: return@synchronized
        if (!EcarxOutputSettings.presentationSwitch(app)) return@synchronized
        presentationWanted = true
        if (available) requestPresentationLocked()
    }

    /** Whether guidance is currently on the cluster. */
    fun isActive(): Boolean = synchronized(lock) { lastPushed != null }

    // ---------------------------------------------------------------- arrow-channel tests (diagnostic)

    /**
     * Runs one arrow channel for calibration: [EcarxArrowTestKind.TURN_ID] and
     * [EcarxArrowTestKind.TURN_ID_HIGH] push `updateTurnByTurnArrow(int)` over 100..130 and
     * 131..200, [EcarxArrowTestKind.BITMAP] and [EcarxArrowTestKind.SVG] push the basic maneuvers
     * from [EcarxArrowShape] through their own overload. One step every
     * [ARROW_TEST_STEP_MILLIS], and nothing else on the DIM interface meanwhile: the normal
     * guidance push is suspended until the test ends, so the cluster shows only the value under
     * test. Saved settings and the next session are untouched.
     *
     * Starting again cancels the previous run and starts from the first step.
     * [onStep] runs on the test thread for every pushed step, the first one included.
     * Returns false when the head unit exposes no DIM navigation interface.
     */
    fun startArrowTest(
        kind: EcarxArrowTestKind,
        onStep: ((EcarxArrowTestStep) -> Unit)?,
    ): Boolean = synchronized(lock) {
        if (!available) return@synchronized false
        reclaimLocked()
        if (navi == null) return@synchronized false
        testFuture?.cancel(false)
        testRunning = true
        testKind = kind
        testIndex = 0
        testTotal = when (kind) {
            EcarxArrowTestKind.TURN_ID -> TURN_ID_TEST_LAST - TURN_ID_TEST_FIRST + 1
            EcarxArrowTestKind.TURN_ID_HIGH -> TURN_ID_HIGH_TEST_LAST - TURN_ID_HIGH_TEST_FIRST + 1
            EcarxArrowTestKind.BITMAP, EcarxArrowTestKind.SVG -> EcarxArrowShape.entries.size
        }
        testGeneration += 1
        val generation = testGeneration
        testListener = onStep
        testFuture = testExecutor.scheduleAtFixedRate(
            Runnable { advanceArrowTest(generation) }, 0L, ARROW_TEST_STEP_MILLIS, TimeUnit.MILLISECONDS,
        )
        Log.i(TAG, "arrow test armed: $kind total=$testTotal every ${ARROW_TEST_STEP_MILLIS}ms")
        true
    }

    fun stopArrowTest() = synchronized(lock) {
        testRunning = false
        testFuture?.cancel(false)
        testFuture = null
        testListener = null
        // An explicit stop forgets the run; a test that reached its last step keeps that step, so
        // the readout still names what is on the cluster.
        testLastStep = null
    }

    /** The last step a test pushed, or null when none has run in this process. */
    fun arrowTestStep(): EcarxArrowTestStep? = synchronized(lock) { testLastStep }

    private fun advanceArrowTest(generation: Int) {
        var step: EcarxArrowTestStep? = null
        var listener: ((EcarxArrowTestStep) -> Unit)? = null
        synchronized(lock) {
            if (!testRunning || generation != testGeneration) return
            val naviRef = navi
            if (naviRef == null) {
                stopTestLocked()
                return
            }
            val index = testIndex
            if (index >= testTotal) {
                stopTestLocked()
                return
            }
            val turnIdFirst = when (testKind) {
                EcarxArrowTestKind.TURN_ID -> TURN_ID_TEST_FIRST
                EcarxArrowTestKind.TURN_ID_HIGH -> TURN_ID_HIGH_TEST_FIRST
                else -> -1
            }
            val current = if (turnIdFirst >= 0) {
                EcarxArrowTestStep(testKind, index, testTotal, turnId = turnIdFirst + index)
            } else {
                EcarxArrowTestStep(
                    testKind, index, testTotal, shape = EcarxArrowShape.entries[index],
                )
            }
            pushTestStepLocked(naviRef, current)
            testLastStep = current
            step = current
            listener = testListener
            if (index + 1 >= testTotal) {
                stopTestLocked()
            } else {
                testIndex = index + 1
            }
        }
        // Never call the UI back while holding the lock; the test thread is not the main thread.
        val pushed = step ?: return
        listener?.invoke(pushed)
    }

    /** Cancels the periodic task without touching the saved settings or the last pushed step. */
    private fun stopTestLocked() {
        testRunning = false
        testFuture?.cancel(false)
        testFuture = null
    }

    private fun pushTestStepLocked(naviRef: Any, step: EcarxArrowTestStep): EcarxDimApi.EcarxResult {
        val shape = step.shape
        val result = when (step.kind) {
            EcarxArrowTestKind.TURN_ID, EcarxArrowTestKind.TURN_ID_HIGH ->
                EcarxDimApi.updateTurnByTurnArrow(naviRef, step.turnId)
            EcarxArrowTestKind.BITMAP -> {
                if (shape == null) {
                    EcarxDimApi.EcarxResult.failed("no shape for bitmap")
                } else {
                    val bitmap = turnArrowBitmap(shape.appleType, shape.drivingSide)
                    if (bitmap == null) {
                        EcarxDimApi.EcarxResult.failed("no bitmap for ${shape.name}")
                    } else {
                        EcarxDimApi.updateTurnByTurnArrow(naviRef, bitmap)
                    }
                }
            }
            EcarxArrowTestKind.SVG -> {
                if (shape == null) {
                    EcarxDimApi.EcarxResult.failed("no shape for svg")
                } else {
                    val svg = turnArrowSvg(shape.appleType, shape.drivingSide)
                    if (svg.isEmpty()) {
                        EcarxDimApi.EcarxResult.failed("no svg for ${shape.name}")
                    } else {
                        EcarxDimApi.updateTurnByTurnArrow(naviRef, svg)
                    }
                }
            }
        }
        lastArrowChannel = when (step.kind) {
            EcarxArrowTestKind.TURN_ID, EcarxArrowTestKind.TURN_ID_HIGH -> "test-int"
            EcarxArrowTestKind.BITMAP -> "test-bitmap"
            EcarxArrowTestKind.SVG -> "test-svg"
        }
        lastArrowCode = if (step.kind == EcarxArrowTestKind.TURN_ID ||
            step.kind == EcarxArrowTestKind.TURN_ID_HIGH
        ) {
            step.turnId.toString()
        } else {
            shape?.let { "shape:" + it.name } ?: "shape:none"
        }
        if (step.kind == EcarxArrowTestKind.TURN_ID || step.kind == EcarxArrowTestKind.TURN_ID_HIGH) {
            lastArrowCodeValue = step.turnId
        }
        lastArrowPush = if (result.ok) "test-ok" else "test-failed:" + result.reason
        val label = if (step.kind == EcarxArrowTestKind.TURN_ID ||
            step.kind == EcarxArrowTestKind.TURN_ID_HIGH
        ) {
            step.turnId.toString()
        } else {
            shape?.name ?: "none"
        }
        if (result.ok) {
            Log.i(TAG, "arrow test ${step.kind} $label")
        } else {
            Log.w(TAG, "arrow test ${step.kind} $label failed: ${result.reason}")
        }
        return result
    }

    // ---------------------------------------------------------------- OEM switches

    /**
     * The 仪表投屏 switch. Mirrors the centre screen onto the cluster; both shipped ECARX apps
     * toggle it with `setPresentationToDimSwitch(1, "", "", show)`.
     *
     * Turning it on records the intent and arms the watch. It does **not** report whether the
     * cluster changed, because nothing can: the platform call is `void`, unchecked, and returns the
     * same thing whether or not the cluster is even powered. [EcarxResult.ok] here means "the watch
     * owns delivery now"; the switch reports a failure only when the head unit has no DIM
     * interface at all, which is the one case where retrying is pointless.
     */
    fun applyPresentation(show: Boolean): EcarxDimApi.EcarxResult = synchronized(lock) {
        val app = context ?: return EcarxDimApi.EcarxResult.failed("DIM not initialized")
        EcarxOutputSettings.setPresentationSwitch(app, show)
        presentationWanted = show
        if (!show) {
            presentationAttempts = 0
            presentationCountdown = 0
            return applyPresentationLocked(false)
        }
        if (!available) return EcarxDimApi.EcarxResult.failed("no DIM interface on this head unit")
        requestPresentationLocked()
        EcarxDimApi.EcarxResult.ok("projection watch armed")
    }

    /** One attempt now and a fresh fast phase, so a manual toggle is never throttled by the watch. */
    private fun requestPresentationLocked(): EcarxDimApi.EcarxResult {
        val result = applyPresentationLocked(true)
        presentationAttempts = 1
        presentationCountdown = PRESENTATION_FAST_SECONDS - 1
        Log.i(TAG, "projection armed; first attempt ${result.reason}")
        return result
    }

    private fun applyPresentationLocked(show: Boolean): EcarxDimApi.EcarxResult {
        val interaction = dim ?: return EcarxDimApi.EcarxResult.failed("DimInteraction missing")
        val result = EcarxDimApi.setPresentationToDimSwitch(interaction, show)
        if (!result.ok) Log.w(TAG, "presentation switch failed: ${result.reason}")
        else Log.i(TAG, "presentation switch -> $show")
        return result
    }

    /** `DIMProtocolManager.setNaviMode` — makes the cluster prefer an external navigation source. */
    fun applyNaviMode(mode: Int): EcarxDimApi.EcarxResult = synchronized(lock) {
        val app = context ?: return EcarxDimApi.EcarxResult.failed("DIM not initialized")
        EcarxOutputSettings.setNaviMode(app, mode)
        applyNaviModeLocked(mode)
    }

    private fun applyNaviModeLocked(mode: Int): EcarxDimApi.EcarxResult {
        if (mode < 0) return EcarxDimApi.EcarxResult.failed("navi mode never requested")
        val manager = protocol ?: return EcarxDimApi.EcarxResult.failed("DIMProtocolManager missing")
        val result = EcarxDimApi.setNaviMode(manager, mode)
        if (!result.ok) Log.w(TAG, "setNaviMode($mode) failed: ${result.reason}")
        else Log.i(TAG, "cluster navi mode -> $mode")
        return result
    }

    // ---------------------------------------------------------------- push path

    private fun tick() = synchronized(lock) {
        if (!available) return@synchronized
        // The cluster service is not up when the head unit is only on accessory power, so keep
        // asking for the handles as well as for the projection itself.
        if (dim == null || navi == null) {
            reclaimLocked()
            // The first guidance frames can arrive before the cluster service is up. Push once when
            // the navi handle appears instead of leaving the last maneuver waiting for the next
            // route frame; this is a one-shot recovery, not a keepalive.
            if (navi != null) pushLocked()
        }
        watchProjectionLocked()
        if (testRunning) return@synchronized
        // Guidance can expire without a frame (a list that stays empty), so check every second.
        if (lastPushed != null && route.current() == null) {
            endLocked()
        } else if (lastPushed != null) {
            pollLaneInfoLocked()
        }
    }

    /**
     * Once guidance is live, read the car's lane info once a second and log every non-empty result.
     *
     * The DIM interface we know is write-only, so this first tries the read paths the navi object
     * may expose (`getLaneInfo`, or `getNavigationInfo().getLaneInfo`). If none exists, the method
     * list is logged once and the poll stops; the caller then knows this firmware does not publish
     * lane data through this object.
     */
    private fun pollLaneInfoLocked() {
        if (lanePollState == "no-getter") return
        val naviRef = navi ?: return
        val read = EcarxLaneProbe.readLaneInfo(naviRef)
        val maneuver = "turn=" + lastGuidanceTurnId + " type=" + lastArrowType + " dir=" + lastArrowDirection +
            " laneIdx=" + route.laneSnapshot().eventIndex
        when {
            read.source == null -> {
                lanePollState = "no-getter"
                lastLanePoll = ("no lane getter; no-arg methods=" + EcarxLaneProbe.naviMethodNames(naviRef)).take(600)
                Log.w(TAG, "lane poll: $lastLanePoll")
            }
            read.text == null -> {
                if (lanePollState != "empty") {
                    lanePollState = "empty"
                    lastLanePoll = "$maneuver " + read.source + "=empty"
                    Log.i(TAG, "lane poll: $lastLanePoll")
                }
            }
            else -> {
                lanePollState = "data"
                val text = ("$maneuver " + read.source + " " + read.text).take(600)
                if (text != lastLanePoll) {
                    lastLanePoll = text
                    Log.i(TAG, "lane poll: $text")
                }
            }
        }
    }

    /**
     * Re-asserts the projection switch until the cluster is up, then keeps it alive.
     *
     * Nothing on this platform reports that the projection took: `getShowPresentationOption()` is
     * the only read-back and it is `-1` on this head unit even while the cluster is mirroring
     * (probe v3/v4, nine reads), so a read-back loop would immediately give up. Re-sending the
     * switch is idempotent and harmless, which is why the watch is unconditional instead.
     */
    private fun watchProjectionLocked() {
        if (!presentationWanted) return
        if (presentationCountdown > 0) {
            presentationCountdown -= 1
            return
        }
        val fast = presentationAttempts < PRESENTATION_FAST_ATTEMPTS
        val interval = if (fast) PRESENTATION_FAST_SECONDS else PRESENTATION_SLOW_SECONDS
        // The ticker runs once a second, so the countdown is a plain seconds counter.
        presentationCountdown = interval - 1
        val result = applyPresentationLocked(true)
        presentationAttempts += 1
        // One line at the start of each phase and then once a minute, not once a second.
        if (presentationAttempts == 1 || !fast && presentationAttempts % 6 == 0) {
            Log.i(TAG, "projection re-asserted #$presentationAttempts every ${interval}s (${result.reason})")
        }
    }

    private fun pushLocked() {
        val app = context ?: return
        if (testRunning) return
        if (!EcarxOutputSettings.enabled(app)) {
            if (lastPushed != null) endLocked()
            return
        }
        val naviRef = navi ?: return
        val maneuver = route.current()
        if (maneuver == null) {
            if (lastPushed != null) endLocked()
            return
        }
        val guidance = guidanceFor(maneuver)
        lastGuidanceTurnId = guidance.turnId.toString()
        lastGuidanceDrivingDirection = guidance.drivingDirection.toString()
        if (guidance == lastPushed) return

        // Order matches the toolkit: the aggregate payload first, then the discrete fields.
        val result = EcarxDimApi.updateNavigationInfo(naviRef, guidance)
        lastNaviPush = if (result.ok) "ok" else "failed:" + result.reason
        if (!result.ok) {
            Log.w(TAG, "updateNavigationInfo failed: ${result.reason}")
            return
        }
        val firstFrame = lastPushed == null
        if (firstFrame) EcarxDimApi.notifyTurnByTurnStarted(naviRef)
        EcarxDimApi.updateDistanceToNextGuidancePoint(naviRef, maneuver.distanceMeters.toLong())
        maneuver.remainingMeters?.let { EcarxDimApi.updateDistanceToDestination(naviRef, it) }
        maneuver.arrivalEpochSeconds?.let { EcarxDimApi.updateEta(naviRef, it) }
        maneuver.road.takeIf { it.isNotEmpty() }
            ?.let { EcarxDimApi.updateNextGuidancePointName(naviRef, it) }
        if (EcarxOutputSettings.turnArrowExperimental(app)) {
            // The arrow is an ECARX turn-ID icon now. The ID also rides inside the aggregate
            // payload as Guidance.turnId, so the cluster's own arrow and this explicit call agree.
            lastArrowType = maneuver.type.toString()
            lastArrowDirection = maneuver.drivingSide.toString()
            val id = turnArrowId(maneuver.type, maneuver.drivingSide)
            if (id == null) {
                lastArrowCode = "skip"
                lastArrowChannel = "turn-id-skip"
                lastArrowPush = "turn-id-unmapped"
            } else {
                lastArrowCodeValue = id
                lastArrowCode = id.toString()
                lastArrowChannel = "turn-id"
                val arrow = EcarxDimApi.updateTurnByTurnArrow(naviRef, id)
                lastArrowPush = if (arrow.ok) "turn-id-ok" else "turn-id-failed:" + arrow.reason
            }
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            arrowTimeline.addLast(
                "$stamp ${maneuverLabel(maneuver.type)} t=${maneuver.type} id=${id ?: "skip"} " +
                    "d=${maneuver.distanceMeters} $lastArrowPush",
            )
        }

        lastPushed = guidance
        if (!guidanceActive) {
            guidanceActive = true
            Log.i(TAG, "DIM guidance started turn=${guidance.turnId} road=\"${guidance.nextGuidancePointName}\"")
        }
    }

    private fun endLocked() {
        val naviRef = navi
        if (naviRef != null) {
            EcarxDimApi.notifyTurnByTurnStopped(naviRef)
            // 0 is the "no arrow" value; without it the cluster keeps the last turn icon.
            EcarxDimApi.updateTurnByTurnArrow(naviRef, 0)
            // -1 is the "no distance" value; 0 leaves a "0 m" label behind.
            EcarxDimApi.updateDistanceToNextGuidancePoint(naviRef, -1L)
            EcarxDimApi.updateNextGuidancePointName(naviRef, "")
            EcarxDimApi.updateLaneInfoEmpty(naviRef)
        }
        lastPushed = null
        if (guidanceActive) {
            guidanceActive = false
            Log.i(TAG, "DIM guidance ended")
        }
    }

    /**
     * One [EcarxDimApi.Guidance] per maneuver. Distance, destination distance, ETA, the road name
     * and the live/idle status are recovered data; `drivingDirection` is a documented placeholder
     * (see the class comment), and `turnId` is the ECARX turn-icon ID.
     */
    private fun guidanceFor(maneuver: AppleManeuver): EcarxDimApi.Guidance {
        // When the arrow setting is off, leave turnId at 0 so the cluster keeps its own arrow.
        val turnId = if (context?.let(EcarxOutputSettings::turnArrowExperimental) == true) {
            turnArrowId(maneuver.type, maneuver.drivingSide) ?: lastArrowCodeValue
        } else {
            0
        }
        return EcarxDimApi.Guidance(
            // The turn ID rides in the aggregate payload as well, so the cluster's own arrow and
            // the explicit updateTurnByTurnArrow(int) call use one namespace while the setting is on.
            turnId = turnId,
            distanceToNextMeters = maneuver.distanceMeters.toLong(),
            distanceToDestinationMeters = maneuver.remainingMeters ?: -1L,
            etaEpochSeconds = maneuver.arrivalEpochSeconds ?: -1L,
            drivingDirection = if (maneuver.drivingSide == 1) 1 else 0,
            status = NAVI_STATUS_ACTIVE,
            muteState = 0,
            dayNightMode = dayNightMode(),
            nextGuidancePointName = maneuver.road,
        )
    }

    /**
     * The ECARX turn-icon ID for an Apple maneuver, using the mapping recovered from the car's
     * icon table: 101 start, 102 left, 103 right, 104 left-front, 105 right-front, 108 U-turn,
     * 109 straight, 111 roundabout, 112 roundabout exit, 115 destination, 120 along road, 143
     * roundabout U-turn, 121..130 for roundabout exits 1..10 and 131..139 for exits 11..19.
     *
     * The roundabout-exit block is 131..140; 140 is unused because Apple's maneuver table stops at
     * exit 19. 106/107 (left/right back), 110 (via), 114 (tollgate), 141/142 (roundabout left/
     * right) have no corresponding Apple maneuver type on this platform.
     */
    private fun turnArrowId(type: Int, drivingSide: Int): Int? = when (type) {
        0, 3 -> 109
        1, 20, 47 -> 102
        2, 21, 48 -> 103
        13, 49, 52 -> 104
        14, 50, 53 -> 105
        8, 9 -> if (drivingSide == 1) 104 else 105
        4, 18, 26 -> 108
        19 -> 143
        5, 15, 16, 17, 51 -> 120
        6 -> 111
        7 -> 112
        in 28..37 -> 121 + (type - 28)
        in 38..46 -> 131 + (type - 38)
        10, 12, 24, 25, 27 -> 115
        11 -> 101
        22 -> 104
        23 -> 105
        else -> null
    }

    /** `getDayNightMode()` is 0/1 in `s1/r`; the head unit's own day/night split drives the choice. */
    private fun dayNightMode(): Int {
        val app = context ?: return 0
        val night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) 1 else 0
    }

    /**
     * The SVG arrow used only by the Settings calibration test: a 100×100 canvas with thick
     * shafts and solid triangular heads, so it stays visible when the cluster scales it down.
     *
     * The live arrow path uses [turnArrowId] instead.
     *
     * The mapping follows the iAP2 RouteGuidanceManeuverType table in
     * docs/ROUTE_GUIDANCE_PROTOCOL.md: 1 left, 2 right, 3/5/11 straight, 4/18/19/26 U-turn,
     * 6/7/28–46 roundabout (the exit number is not encoded), 8 off-ramp, 9 on-ramp, 10/12/27
     * destination, 13/14 keep left/right, 15–17 ferry, 20/21 end-of-road left/right, 22/23 highway
     * exit, 24/25 arrival side, 47/48 sharp, 49/50 slight, 51 change highway, 52/53 change highway
     * left/right.
     *
     * A U-turn and an off-/on-ramp follow [drivingSide] (0 = right-hand traffic, 1 = left-hand
     * traffic), which is the side the maneuver actually takes in that country.
     *
     * An unmapped type returns an empty string: nothing is pushed, so the cluster keeps the arrow
     * it already had instead of receiving a guess.
     */
    private fun turnArrowSvg(type: Int, drivingSide: Int): String {
        val leftHandTraffic = drivingSide == 1
        // A U-turn crosses the oncoming lane, so it goes left in right-hand traffic and vice versa.
        val uTurn = if (leftHandTraffic) {
            "<path d='M30 92 V54 A20 20 0 0 1 70 54 V70'/>" + arrowHead(70.0, 90.0, 90.0)
        } else {
            "<path d='M70 92 V54 A20 20 0 0 0 30 54 V70'/>" + arrowHead(30.0, 90.0, 90.0)
        }
        val exitRight = "<path d='M36 92 V20 M36 62 L66 38'/>" +
            arrowHead(82.0, 26.0, -38.7)
        val exitLeft = "<path d='M64 92 V20 M64 62 L34 38'/>" +
            arrowHead(18.0, 26.0, -141.3)
        val mergeRight = "<path d='M44 92 V40 M78 84 L46 56'/>" + arrowHead(44.0, 20.0, -90.0)
        val mergeLeft = "<path d='M56 92 V40 M22 84 L54 56'/>" + arrowHead(56.0, 20.0, -90.0)
        val shape = when (type) {
            1, 20 -> "<path d='M50 92 V44 H36'/>" + arrowHead(10.0, 44.0, 180.0)
            2, 21 -> "<path d='M50 92 V44 H64'/>" + arrowHead(90.0, 44.0, 0.0)
            3, 5, 11 -> "<path d='M50 92 V42'/>" + arrowHead(50.0, 22.0, -90.0)
            4, 18, 19, 26 -> uTurn
            6, 7, in 28..46 ->
                // Circle plus an entry stub: the exit number cannot be encoded anyway, and a
                // triangle on the ring would merge into it at this stroke width.
                "<circle cx='50' cy='50' r='24'/><path d='M50 92 V76'/>"
            8 -> if (leftHandTraffic) exitLeft else exitRight
            9, 51 -> if (leftHandTraffic) mergeLeft else mergeRight
            10, 12, 25, 27 -> "<path d='M44 90 V12 M44 12 H80 V48 H44'/>"
            13 -> "<path d='M50 92 V58 L34 42 M50 58 L72 36'/>" +
                arrowHead(20.0, 28.0, -135.0)
            14 -> "<path d='M50 92 V58 L66 42 M50 58 L28 36'/>" +
                arrowHead(80.0, 28.0, -45.0)
            15, 16, 17 ->
                "<path d='M18 54 H82 L70 80 H30 Z M16 92 Q28 86 40 92 T64 92 T84 92'/>"
            22 -> exitLeft
            23 -> exitRight
            24 -> "<path d='M56 90 V12 M56 12 H20 V48 H56'/>"
            47 -> "<path d='M62 92 V50 L40 56'/>" + arrowHead(15.0, 63.0, 164.8)
            48 -> "<path d='M38 92 V50 L60 56'/>" + arrowHead(85.0, 63.0, 15.2)
            49, 52 -> "<path d='M50 92 V62 L34 46'/>" + arrowHead(20.0, 32.0, -135.0)
            50, 53 -> "<path d='M50 92 V62 L66 46'/>" + arrowHead(80.0, 32.0, -45.0)
            else -> ""
        }
        if (shape.isEmpty()) return ""
        return "<svg xmlns='http://www.w3.org/2000/svg' width='100' height='100' " +
            "viewBox='0 0 100 100'>" +
            "<g fill='none' stroke='#FFFFFF' stroke-width='12' stroke-linecap='round' " +
            "stroke-linejoin='round'>" +
            shape + "</g></svg>"
    }

    /** Solid arrowhead geometry, in the 100-unit icon box. */
    private const val ARROW_HEAD_LENGTH = 26.0
    private const val ARROW_HEAD_HALF_WIDTH = 20.0

    /**
     * A filled triangular arrowhead at ([tipX], [tipY]) pointing along [angleDegrees]
     * (0 = right, 90 = down). A shaft must stop before the tip; the ones in [turnArrowSvg] end
     * inside the triangle, so their round caps stay hidden and never poke past the point.
     */
    private fun arrowHead(tipX: Double, tipY: Double, angleDegrees: Double): String {
        val radians = Math.toRadians(angleDegrees)
        val dx = kotlin.math.cos(radians)
        val dy = kotlin.math.sin(radians)
        val baseX = tipX - ARROW_HEAD_LENGTH * dx
        val baseY = tipY - ARROW_HEAD_LENGTH * dy
        val offsetX = -dy * ARROW_HEAD_HALF_WIDTH
        val offsetY = dx * ARROW_HEAD_HALF_WIDTH
        fun round(value: Double) = Math.round(value).toString()
        return "<path d='M${round(tipX)} ${round(tipY)} " +
            "L${round(baseX + offsetX)} ${round(baseY + offsetY)} " +
            "L${round(baseX - offsetX)} ${round(baseY - offsetY)} Z' " +
            "fill='#FFFFFF' stroke='none'/>"
    }

    /** Lists the updateTurnByTurnArrow parameter signatures the platform exposes. */
    private fun arrowOverloads(): String {
        val naviRef = navi ?: return "no-navi"
        return runCatching {
            naviRef.javaClass.methods
                .filter { it.name == "updateTurnByTurnArrow" }
                .joinToString(";") { method ->
                    method.parameterTypes.joinToString(",") { it.simpleName }
                }
                .ifEmpty { "none" }
        }.getOrDefault("probe-failed")
    }

    /** Human label for an Apple maneuver type, for the diagnostic timelines only. */
    private fun maneuverLabel(type: Int): String = when (type) {
        0 -> "无转向"
        1, 20 -> "左转"
        2, 21 -> "右转"
        3, 5, 11 -> "直行"
        4, 18, 19, 26 -> "掉头"
        6 -> "进入环岛"
        7, in 28..46 -> "驶出环岛"
        8 -> "驶离匝道"
        9 -> "驶入匝道"
        10, 12 -> "到达终点"
        13 -> "靠左"
        14 -> "靠右"
        15, 16, 17 -> "渡口"
        22 -> "高速左出口"
        23 -> "高速右出口"
        24 -> "左侧到达"
        25 -> "右侧到达"
        27 -> "指引结束"
        47 -> "左急转"
        48 -> "右急转"
        49 -> "左前"
        50 -> "右前"
        51 -> "变换高速"
        52 -> "向左变换高速"
        53 -> "向右变换高速"
        else -> "未知($type)"
    }

    /** Records one parsed 0x5202 maneuver for the next diagnostic report. */
    private fun recordManeuver(index: Int, type: Int, side: Int, road: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        maneuverTimeline.addLast(
            "$stamp idx=$index ${maneuverLabel(type)} t=$type s=$side road=$road",
        )
        while (maneuverTimeline.size > 80) maneuverTimeline.removeFirst()
    }

    /** Records a 0x5201 maneuver-list transition; the first index is the current maneuver. */
    private fun recordManeuverList(indices: List<Int>, active: Int) {
        val key = "active=$active list=" + indices.joinToString(",")
        if (key == lastManeuverListKey) return
        lastManeuverListKey = key
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        maneuverListTimeline.addLast("$stamp $key")
        while (maneuverListTimeline.size > 80) maneuverListTimeline.removeFirst()
    }

    /**
     * Draws the next-turn arrow locally and returns it as a bitmap. Used only by the Settings
     * bitmap calibration test; the live path uses [turnArrowId].
     *
     * `type`/`drivingSide` are Apple's `RouteGuidanceManeuverType`/`DrivingSide` values, mapped
     * with the same grouping DiPlay's own dashboard widget uses (see `NavigationWidget.arrow`).
     */
    private fun turnArrowBitmap(type: Int, drivingSide: Int): android.graphics.Bitmap? {
        val kind = when (type) {
            1, 20 -> ArrowKind.LEFT
            2, 21 -> ArrowKind.RIGHT
            47 -> ArrowKind.SHARP_LEFT
            48 -> ArrowKind.SHARP_RIGHT
            13, 22, 49, 52 -> ArrowKind.SLIGHT_LEFT
            14, 23, 50, 53 -> ArrowKind.SLIGHT_RIGHT
            4, 18, 19, 26 -> if (drivingSide == 1) ArrowKind.U_TURN_RIGHT else ArrowKind.U_TURN_LEFT
            6, 7, in 28..46 -> ArrowKind.ROUNDABOUT
            10, 12, 24, 25, 27 -> ArrowKind.DESTINATION
            3, 5, 8, 9, 11, 51 -> ArrowKind.STRAIGHT
            else -> {
                if (type != lastUnmappedArrowType) {
                    lastUnmappedArrowType = type
                    Log.i(TAG, "turn arrow unmapped type=" + type + " side=" + drivingSide)
                }
                return null
            }
        }
        val size = 128
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val center = size / 2f
        val r = size * 0.34f
        canvas.drawPath(arrowPath(kind, center, r), paint)
        return bitmap
    }

    private enum class ArrowKind {
        LEFT, RIGHT, SHARP_LEFT, SHARP_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
        U_TURN_LEFT, U_TURN_RIGHT, ROUNDABOUT, DESTINATION, STRAIGHT,
    }

    /** Builds the arrow outline for [kind] around [center], radius [r]. */
    private fun arrowPath(kind: ArrowKind, center: Float, r: Float): android.graphics.Path {
        val path = android.graphics.Path()
        val c = center
        when (kind) {
            ArrowKind.LEFT -> {
                path.moveTo(c + r, c)
                path.lineTo(c - r, c)
                path.moveTo(c - r, c)
                path.lineTo(c - r + r * 0.4f, c - r * 0.5f)
                path.moveTo(c - r, c)
                path.lineTo(c - r + r * 0.4f, c + r * 0.5f)
            }
            ArrowKind.RIGHT -> {
                path.moveTo(c - r, c)
                path.lineTo(c + r, c)
                path.moveTo(c + r, c)
                path.lineTo(c + r - r * 0.4f, c - r * 0.5f)
                path.moveTo(c + r, c)
                path.lineTo(c + r - r * 0.4f, c + r * 0.5f)
            }
            ArrowKind.SHARP_LEFT -> {
                path.moveTo(c + r, c - r * 0.5f)
                path.lineTo(c - r * 0.6f, c)
                path.lineTo(c + r, c + r * 0.5f)
                path.moveTo(c + r, c - r * 0.5f)
                path.lineTo(c + r * 0.2f, c - r * 0.5f)
                path.moveTo(c + r, c + r * 0.5f)
                path.lineTo(c + r * 0.2f, c + r * 0.5f)
            }
            ArrowKind.SHARP_RIGHT -> {
                path.moveTo(c - r, c - r * 0.5f)
                path.lineTo(c + r * 0.6f, c)
                path.lineTo(c - r, c + r * 0.5f)
                path.moveTo(c - r, c - r * 0.5f)
                path.lineTo(c - r * 0.2f, c - r * 0.5f)
                path.moveTo(c - r, c + r * 0.5f)
                path.lineTo(c - r * 0.2f, c + r * 0.5f)
            }
            ArrowKind.SLIGHT_LEFT -> {
                path.moveTo(c + r, c - r * 0.35f)
                path.lineTo(c - r * 0.4f, c)
                path.lineTo(c + r, c + r * 0.35f)
                path.moveTo(c + r, c - r * 0.35f)
                path.lineTo(c + r * 0.35f, c - r * 0.15f)
                path.moveTo(c + r, c + r * 0.35f)
                path.lineTo(c + r * 0.35f, c + r * 0.15f)
            }
            ArrowKind.SLIGHT_RIGHT -> {
                path.moveTo(c - r, c - r * 0.35f)
                path.lineTo(c + r * 0.4f, c)
                path.lineTo(c - r, c + r * 0.35f)
                path.moveTo(c - r, c - r * 0.35f)
                path.lineTo(c - r * 0.35f, c - r * 0.15f)
                path.moveTo(c - r, c + r * 0.35f)
                path.lineTo(c - r * 0.35f, c + r * 0.15f)
            }
            ArrowKind.U_TURN_LEFT -> {
                path.moveTo(c - r * 0.3f, c + r * 0.7f)
                path.lineTo(c - r * 0.3f, c - r * 0.5f)
                path.arcTo(c - r * 0.8f, c - r * 0.5f, c + r * 0.2f, c + r * 0.5f, 90f, 180f, false)
                path.lineTo(c + r * 0.2f, c - r * 0.5f)
                path.moveTo(c + r * 0.2f, c - r * 0.5f)
                path.lineTo(c + r * 0.5f, c - r * 0.8f)
            }
            ArrowKind.U_TURN_RIGHT -> {
                path.moveTo(c + r * 0.3f, c + r * 0.7f)
                path.lineTo(c + r * 0.3f, c - r * 0.5f)
                path.arcTo(c - r * 0.2f, c - r * 0.5f, c + r * 0.8f, c + r * 0.5f, -90f, -180f, false)
                path.lineTo(c - r * 0.2f, c - r * 0.5f)
                path.moveTo(c - r * 0.2f, c - r * 0.5f)
                path.lineTo(c - r * 0.5f, c - r * 0.8f)
            }
            ArrowKind.ROUNDABOUT -> {
                path.addCircle(c, c, r * 0.55f, android.graphics.Path.Direction.CW)
                path.moveTo(c, c - r)
                path.lineTo(c, c - r * 0.3f)
            }
            ArrowKind.DESTINATION -> {
                path.addCircle(c, c, r * 0.35f, android.graphics.Path.Direction.CW)
                path.moveTo(c, c - r * 0.35f)
                path.lineTo(c, c - r * 0.6f)
            }
            ArrowKind.STRAIGHT -> {
                path.moveTo(c, c + r)
                path.lineTo(c, c - r)
                path.moveTo(c, c - r)
                path.lineTo(c - r * 0.45f, c - r * 0.6f)
                path.moveTo(c, c - r)
                path.lineTo(c + r * 0.45f, c - r * 0.6f)
            }
        }
        return path
    }

    // ---------------------------------------------------------------- lane probe (diagnostic)

    /**
     * Dumps the opaque `ILaneInfo` contract and runs spy passes through `updateLaneInfo` and
     * `updateNavigationInfo`. Runs on a worker thread; [onResult] is called on that thread, so
     * the caller must post to its own UI thread.
     */
    fun probeLaneInfo(appContext: Context, onResult: (String) -> Unit) {
        Thread({
            val report = runCatching {
                initialize(appContext)
                val naviRef = synchronized(lock) {
                    if (available) {
                        reclaimLocked()
                        navi
                    } else {
                        null
                    }
                }
                if (naviRef == null) {
                    "ECARX DIM navigation interface is not ready. Open CarPlay once, then retry."
                } else {
                    // Leave the last spy lanes on the cluster so they can be inspected. The next
                    // live guidance frame (or session end) overwrites/clears them; an active route
                    // is never stopped here.
                    val laneReport = EcarxLaneProbe.probe(naviRef)
                    val callbackReport = EcarxNavCallbackProbe.start(naviRef)
                    laneReport + "\n\nnav callback probe:\n" + callbackReport
                }
            }.getOrElse { error ->
                "lane probe failed: " + error.javaClass.simpleName + ": " + error.message
            }
            onResult(report)
        }, "diplay-ecarx-lane-probe").apply { isDaemon = true }.start()
    }

    /** One-line state for the settings diagnostics block. */
    fun diagnostics(context: Context): String = buildString {
        append("ecarx ").append(EcarxCapabilities.describe(context))
        append(" enabled=").append(EcarxOutputSettings.enabled(context))
        append(" presentation=").append(EcarxOutputSettings.presentationSwitch(context))
        append(" naviMode=").append(
            EcarxOutputSettings.naviMode(context).takeIf { it >= 0 } ?: "unset",
        )
        append(" arrowExperimental=").append(EcarxOutputSettings.turnArrowExperimental(context))
        synchronized(lock) {
            append(" wired=").append(available)
            append(" dim=").append(dim != null)
            append(" navi=").append(navi != null)
            append(" protocol=").append(protocol != null)
            append(" projectionWanted=").append(presentationWanted)
            append(" projectionAttempts=").append(presentationAttempts)
            append(" active=").append(lastPushed != null)
            append(" status=").append(if (lastPushed != null) NAVI_STATUS_ACTIVE else NAVI_STATUS_IDLE)
            append(" laneFrames=").append(laneFrames)
            append(" laneBytes=").append(laneHex?.length?.div(2) ?: 0)
            append(" laneHex=").append(laneHex ?: "none")
            append(" laneMethods=").append(laneMethods())
            append(" lastUnmappedArrowType=").append(lastUnmappedArrowType ?: "none")
            append(" lastNaviPush=").append(lastNaviPush)
            append(" lastArrowPush=").append(lastArrowPush)
            append(" lastArrowChannel=").append(lastArrowChannel)
            append(" lastArrowType=").append(lastArrowType)
            append(" lastArrowDirection=").append(lastArrowDirection)
            append(" lastArrowCode=").append(lastArrowCode)
            append(" arrowTest=").append(testKind).append(" step=").append(testIndex)
                .append('/').append(testTotal).append(" running=").append(testRunning)
                .append(" lastTest=").append(
                    testLastStep?.let { step ->
                        if (step.kind == EcarxArrowTestKind.TURN_ID ||
                            step.kind == EcarxArrowTestKind.TURN_ID_HIGH
                        ) {
                            step.turnId.toString()
                        } else {
                            step.shape?.name
                        }
                    } ?: "none",
                )
            append(" arrowOverloads=").append(arrowOverloads())
            append(" arrowTimeline=").append(arrowTimeline.joinToString("|"))
            append(" maneuverTimeline=").append(maneuverTimeline.joinToString("|"))
            append(" maneuverListTimeline=").append(maneuverListTimeline.joinToString("|"))
            append(" lastGuidanceTurnId=").append(lastGuidanceTurnId)
            append(" lastGuidanceDrivingDirection=").append(lastGuidanceDrivingDirection)
            append(" lanePoll=").append(lastLanePoll)
            append(" navCallback=").append(EcarxNavCallbackProbe.lastEvents())
            val lane = route.laneSnapshot()
            val laneText = lane.lanes.joinToString(";") { item ->
                "pos=" + item.position + ",status=" + item.status + ",dir=" + item.direction +
                    ",angles=" + item.angles.joinToString("/") + ",hl=" + item.highlighted
            }
            append(" laneEvent=").append(lane.eventIndex)
            append(" laneShowing=").append(lane.showing)
            append(" laneTotal=").append(lane.total)
            append(" laneComplete=").append(lane.complete)
            append(" laneDesc=").append(lane.description)
            append(" laneLanes=").append(laneText)
            append(" laneTlvDump=").append(lane.tlvDump)
            // The only read-back the API offers. Uninterpreted on purpose — it stays -1 while the
            // cluster mirrors (probe v3/v4), so it is printed for correlation, never acted on.
            dim?.let { append(" showPresentationOption=").append(EcarxDimApi.showPresentationOption(it)) }
        }
    }
}
