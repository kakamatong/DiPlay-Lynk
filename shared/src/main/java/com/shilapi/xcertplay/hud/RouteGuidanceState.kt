package com.shilapi.xcertplay.hud

/**
 * The next maneuver exactly as Apple sent it, for the CarPlay outputs that render it.
 *
 * `type` and `drivingSide` are Apple's own RouteGuidanceManeuverType / DrivingSide values, kept
 * unconverted on purpose: each output maps them to its own icon vocabulary, and a vendor that has
 * no recovered code table must not pretend otherwise.
 */
internal data class AppleManeuver(
    val distanceMeters: Int,
    val type: Int,
    val drivingSide: Int,
    val road: String = "",
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    val arrivalEpochSeconds: Long? = null,
    val laneGuidanceIndex: Int = -1,
    val laneGuidanceShowing: Int = 0,
    val laneGuidanceTotal: Int = 0,
    val laneGuidanceDescription: String = "",
    val laneComplete: Boolean = false,
    val lanes: List<LaneInfo> = emptyList(),
)

/** One lane from Apple's 0x5204 LaneInformations container. */
internal data class LaneInfo(
    val position: Int,
    val status: Int,
    val direction: Int,
    val angles: List<Int>,
    val highlighted: Boolean,
)

/** Raw 0x5204 state for diagnostics, independent of the current maneuver cache. */
internal data class LaneSnapshot(
    val eventIndex: Int,
    val showing: Int,
    val total: Int,
    val description: String,
    val complete: Boolean,
    val lanes: List<LaneInfo>,
    val tlvDump: String,
)

internal enum class RouteGuidanceChange {
    NONE,
    GUIDANCE,
    CLEAR,
}

/**
 * Decodes the iAP2 route-guidance subset (0x5201/0x5202/0x5204) that every CarPlay output needs.
 *
 * This is vendor-neutral: it only turns Apple's frames into [AppleManeuver] and [LaneSnapshot],
 * and it is shared by the ECARX cluster output and by the widget-facing glance.
 */
internal class RouteGuidanceState(
    private val nanoTime: () -> Long = System::nanoTime,
    private val staleRouteNs: Long = STALE_ROUTE_NS,
    private val emptyListHideNs: Long = EMPTY_LIST_HIDE_NS,
    /**
     * The dashboard overlay keeps the last instruction across a wireless session drop: the iPhone
     * often sends NoRouteSet (0) while the tunnel is tearing down, which is not a real arrival.
     * Arrived (2) still ends the route. The overlay's own stale window retires a truly ended one.
     */
    private val keepAcrossNoRoute: Boolean = false,
    /**
     * Cluster outputs keep the last arrow across sparse route frames. Empty maneuver lists are
     * ignored and only an explicit end (NoRouteSet/Arrived) or a session teardown clears it.
     */
    private val keepAcrossGaps: Boolean = false,
    /** Called for every parsed 0x5202 maneuver, for the diagnostic timeline. */
    private val onManeuver: ((index: Int, type: Int, side: Int, road: String) -> Unit)? = null,
    /** Called for every 0x5201 maneuver list, with the list and the first (current) index. */
    private val onManeuverList: ((indices: List<Int>, active: Int) -> Unit)? = null,
) {
    private data class Maneuver(val type: Int, val drivingSide: Int, val afterRoad: String)

    private val maneuvers = mutableMapOf<Int, Maneuver>()
    private var routeActive = false
    private var activeIndex = -1
    private var distanceMeters = 0
    private var currentRoad = ""
    private var arrivalEpochSeconds: Long? = null
    private var remainingSeconds: Long? = null
    private var remainingMeters: Long? = null
    private var laneGuidanceIndex = -1
    private var laneGuidanceShowing = 0
    private var laneGuidanceTotal = 0
    private var laneGuidanceDescription = ""
    private var laneComplete = false
    private var lanes: List<LaneInfo> = emptyList()
    private var laneTlvDump = ""
    private var emptyListSinceNs: Long? = null
    private var lastRouteUpdateNs: Long? = null

    fun accept(messageId: Int, payload: ByteArray): RouteGuidanceChange {
        if (!validTlvs(payload)) return RouteGuidanceChange.NONE
        return when (messageId) {
            ROUTE_GUIDANCE_UPDATE -> parseRouteUpdate(payload)
            ROUTE_GUIDANCE_MANEUVER_UPDATE -> parseManeuverUpdate(payload)
            LANE_GUIDANCE_UPDATE -> parseLaneUpdate(payload)
            else -> RouteGuidanceChange.NONE
        }
    }

    /** Next maneuver as Apple sent it, or null when there is no live guidance. */
    fun current(): AppleManeuver? {
        val maneuver = activeManeuver() ?: return null
        return AppleManeuver(
            distanceMeters, maneuver.type, maneuver.drivingSide,
            roadFor(maneuver), remainingSeconds, remainingMeters, arrivalEpochSeconds,
            laneGuidanceIndex, laneGuidanceShowing, laneGuidanceTotal,
            laneGuidanceDescription, laneComplete, lanes,
        )
    }

    fun clear(): Boolean {
        val wasActive = routeActive
        routeActive = false
        activeIndex = -1
        distanceMeters = 0
        currentRoad = ""
        arrivalEpochSeconds = null
        remainingSeconds = null
        remainingMeters = null
        laneGuidanceIndex = -1
        laneGuidanceShowing = 0
        laneGuidanceTotal = 0
        laneGuidanceDescription = ""
        laneComplete = false
        lanes = emptyList()
        laneTlvDump = ""
        emptyListSinceNs = null
        lastRouteUpdateNs = null
        maneuvers.clear()
        return wasActive
    }

    private fun activeManeuver(): Maneuver? {
        if (!routeActive || activeIndex < 0) return null
        if (!keepAcrossGaps) {
            val updated = lastRouteUpdateNs ?: return null
            if (nanoTime() - updated >= staleRouteNs) return null
            val emptySince = emptyListSinceNs
            if (emptySince != null && nanoTime() - emptySince >= emptyListHideNs) return null
        }
        return maneuvers[activeIndex]
    }

    // The road the driver turns onto is what the next instruction is about; fall back to the current one.
    private fun roadFor(maneuver: Maneuver): String = maneuver.afterRoad.ifEmpty { currentRoad }

    /** Raw lane data for diagnostics, even when no maneuver is active. */
    fun laneSnapshot(): LaneSnapshot = LaneSnapshot(
        laneGuidanceIndex, laneGuidanceShowing, laneGuidanceTotal,
        laneGuidanceDescription, laneComplete, lanes, laneTlvDump,
    )

    private fun parseRouteUpdate(data: ByteArray): RouteGuidanceChange {
        // A teardown NoRouteSet is not fresh guidance. Do not let repeated teardown frames
        // extend the retained instruction's lifetime or replace its road/arrival metadata.
        if (keepAcrossNoRoute) {
            var noRoute = false
            forEachTlv(data) { type, value, valueLength ->
                if (type == 0x0001 && valueLength >= 1) noRoute = data[value] == 0.toByte()
            }
            if (noRoute) return RouteGuidanceChange.NONE
        }
        lastRouteUpdateNs = nanoTime()
        var state: Int? = null
        var distance: Int? = null
        var firstManeuver: Int? = null
        val maneuverList = mutableListOf<Int>()
        var listPresent = false
        forEachTlv(data) { type, value, valueLength ->
            when {
                type == 0x0001 && valueLength >= 1 -> state = data[value].toInt() and 0xff
                type == 0x0003 -> currentRoad = utf8(data, value, valueLength)
                type == 0x0005 && valueLength >= 8 -> arrivalEpochSeconds = u64(data, value).takeIf { it > 0 }
                type == 0x0006 && valueLength >= 8 -> remainingSeconds = u64(data, value).takeIf { it >= 0 }
                type == 0x0007 && valueLength >= 4 -> remainingMeters = u32(data, value)
                type == 0x000A && valueLength >= 4 -> {
                    distance = u32(data, value).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
                type == 0x000D -> {
                    listPresent = true
                    var offset = 0
                    while (offset + 2 <= valueLength && maneuverList.size < 16) {
                        val index = u16(data, value + offset)
                        maneuverList += index
                        if (firstManeuver == null) firstManeuver = index
                        offset += 2
                    }
                }
                type == 0x0010 && valueLength >= 2 -> laneGuidanceIndex = u16(data, value)
                type == 0x0011 && valueLength >= 2 -> laneGuidanceTotal = u16(data, value)
                type == 0x0012 && valueLength >= 1 -> laneGuidanceShowing = data[value].toInt() and 0xff
            }
        }

        // Only NoRouteSet (0) and Arrived (2) end the route. A wireless handoff often sends
        // NoRouteSet while the session is still coming back — keep the overlay instruction then.
        if (state == 0 || state == 2) {
            return if (clear()) RouteGuidanceChange.CLEAR else RouteGuidanceChange.NONE
        }
        // The iPhone briefly sends an empty current list every few seconds and while rerouting. Keep the last
        // maneuver (and the cached 0x5202 details, which are never resent) and hide it only if the list stays
        // empty; the outputs' 1 s tick clears themselves once current() turns null.
        if (listPresent && firstManeuver == null) {
            onManeuverList?.invoke(emptyList(), activeIndex)
            // An empty current list is not "navigation ended". The ECARX cluster keeps the last
            // arrow until an explicit end or a session teardown clears it.
            if (keepAcrossGaps) return RouteGuidanceChange.NONE
            if (emptyListSinceNs == null) emptyListSinceNs = nanoTime()
            return RouteGuidanceChange.NONE
        }
        if (firstManeuver != null) emptyListSinceNs = null
        if (state != null) routeActive = true
        if (firstManeuver != null) {
            activeIndex = firstManeuver!!
            routeActive = true
            onManeuverList?.invoke(maneuverList, activeIndex)
        }
        if (distance != null) distanceMeters = distance!!.coerceAtLeast(0)
        return if (current() != null) RouteGuidanceChange.GUIDANCE else RouteGuidanceChange.NONE
    }

    private fun parseManeuverUpdate(data: ByteArray): RouteGuidanceChange {
        var index: Int? = null
        var type: Int? = null
        var drivingSide = 0
        var afterRoad = ""
        forEachTlv(data) { id, value, valueLength ->
            when {
                id == 0x0001 && valueLength >= 2 -> index = u16(data, value)
                id == 0x0003 && valueLength >= 1 -> type = data[value].toInt() and 0xff
                id == 0x0004 -> afterRoad = utf8(data, value, valueLength)
                id == 0x0008 && valueLength >= 1 -> drivingSide = data[value].toInt() and 0xff
            }
        }
        val parsedIndex = index
        val parsedType = type
        if (parsedIndex != null && parsedType != null) {
            maneuvers[parsedIndex] = Maneuver(parsedType, drivingSide, afterRoad)
            onManeuver?.invoke(parsedIndex, parsedType, drivingSide, afterRoad)
        }
        return if (current() != null) RouteGuidanceChange.GUIDANCE else RouteGuidanceChange.NONE
    }

    /**
     * Apple 0x5204 LaneGuidanceInformation.
     *
     * Top-level TLVs: 0x0000 component ID, 0x0001 lane-guidance index, 0x0002 LaneInformations,
     * 0x0003 lane-guidance description. LaneInformations contains nested 0x0000 position/index,
     * 0x0001 status, 0x0002 repeated angles, 0x0003 highlighted angle.
     */
    private fun parseLaneUpdate(data: ByteArray): RouteGuidanceChange {
        var eventIndex = -1
        var description = ""
        var complete = true
        val parsed = mutableListOf<LaneInfo>()
        val dump = mutableListOf<String>()
        forEachTlv(data) { id, value, valueLength ->
            dump += "0x%04x:%s".format(id, hex(data, value, valueLength))
            when (id) {
                0x0001 -> eventIndex = when {
                    valueLength >= 2 -> u16(data, value)
                    valueLength >= 1 -> data[value].toInt() and 0xff
                    else -> -1
                }
                0x0002 -> {
                    val (laneList, laneComplete) = parseLaneInformations(data, value, valueLength)
                    parsed += laneList
                    if (!laneComplete) complete = false
                }
                0x0003 -> description = utf8(data, value, valueLength)
            }
        }
        // A 0x5204 without a lane-guidance index is not usable; iOS always sends one.
        if (eventIndex < 0) return RouteGuidanceChange.NONE
        laneGuidanceIndex = eventIndex
        laneGuidanceDescription = description
        lanes = parsed.take(MAX_LANE_GUIDANCE)
        laneComplete = complete && parsed.size <= MAX_LANE_GUIDANCE
        laneTlvDump = dump.joinToString(",").take(1024)
        return if (current() != null) RouteGuidanceChange.GUIDANCE else RouteGuidanceChange.NONE
    }

    private fun parseLaneInformations(data: ByteArray, offset: Int, length: Int): Pair<List<LaneInfo>, Boolean> {
        val end = offset + length
        val result = mutableListOf<LaneInfo>()
        var complete = true
        var position = -1
        var status = -1
        val angles = mutableListOf<Int>()
        var highlight: Int? = null
        var highlighted = false

        fun flush() {
            if (position >= 0 || status >= 0 || angles.isNotEmpty() || highlighted) {
                val direction = highlight ?: angles.firstOrNull() ?: UNKNOWN_LANE_ANGLE
                result += LaneInfo(position, status, direction, angles.toList(), highlighted)
            }
            position = -1
            status = -1
            angles.clear()
            highlight = null
            highlighted = false
        }

        var cursor = offset
        while (cursor + 4 <= end) {
            val subLength = u16(data, cursor)
            val subId = u16(data, cursor + 2)
            if (subLength < 4 || cursor + subLength > end) {
                complete = false
                break
            }
            val value = cursor + 4
            val valueLength = subLength - 4
            when (subId) {
                0x0000 -> {
                    flush()
                    position = when {
                        valueLength >= 2 -> u16(data, value)
                        valueLength >= 1 -> data[value].toInt() and 0xff
                        else -> result.size
                    }
                }
                0x0001 -> {
                    if (valueLength >= 1) status = data[value].toInt() and 0xff else complete = false
                }
                0x0002 -> {
                    var index = 0
                    while (index < valueLength && angles.size < MAX_LANE_ANGLES) {
                        val angle = if (index + 1 < valueLength) {
                            u16(data, value + index).toShort().toInt()
                        } else {
                            data[value + index].toInt()
                        }
                        angles += angle
                        index += if (index + 1 < valueLength) 2 else 1
                    }
                    if (index < valueLength) complete = false
                }
                0x0003 -> {
                    highlight = when {
                        valueLength >= 2 -> u16(data, value).toShort().toInt()
                        valueLength >= 1 -> data[value].toInt()
                        else -> null
                    }
                    highlighted = highlight != null
                }
            }
            cursor += subLength
        }
        flush()
        if (cursor != end) complete = false
        if (result.any { it.position < 0 || it.status < 0 }) complete = false
        return result to complete
    }

    private fun hex(data: ByteArray, offset: Int, length: Int): String = buildString {
        for (index in 0 until length) append("%02x".format(data[offset + index].toInt() and 0xff))
    }

    private inline fun forEachTlv(data: ByteArray, block: (Int, Int, Int) -> Unit) {
        var offset = 0
        while (offset < data.size) {
            val length = u16(data, offset)
            block(u16(data, offset + 2), offset + TLV_HEADER_BYTES, length - TLV_HEADER_BYTES)
            offset += length
        }
    }

    private fun validTlvs(data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            if (offset + TLV_HEADER_BYTES > data.size) return false
            val length = u16(data, offset)
            if (length < TLV_HEADER_BYTES || length > data.size - offset) return false
            offset += length
        }
        return offset == data.size
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)

    private fun u64(data: ByteArray, offset: Int): Long = (u32(data, offset) shl 32) or u32(data, offset + 4)

    // iAP2 utf8 parameters are NUL-terminated.
    private fun utf8(data: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8).trim()
    }

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
        const val LANE_GUIDANCE_UPDATE = 0x5204
        private const val TLV_HEADER_BYTES = 4
        private const val STALE_ROUTE_NS = 30_000_000_000L
        private const val EMPTY_LIST_HIDE_NS = 3_000_000_000L
        private const val MAX_LANE_GUIDANCE = 8
        private const val MAX_LANE_ANGLES = 16
        private const val UNKNOWN_LANE_ANGLE = 1000
    }
}
