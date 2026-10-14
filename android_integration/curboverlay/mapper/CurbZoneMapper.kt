package com.example.seethrough.wp5.curboverlay.mapper

import com.example.seethrough.wp5.curboverlay.model.CurbColors
import com.example.seethrough.wp5.curboverlay.model.CurbSide
import com.example.seethrough.wp5.curboverlay.model.CurbState
import com.example.seethrough.wp5.curboverlay.model.CurbZone
import com.example.seethrough.wp5.curboverlay.model.CurbZoneLevel
import com.example.seethrough.wp5.curboverlay.parser.MiddlewareZoneReading
import com.example.seethrough.wp5.curboverlay.parser.ParsedCurbEvent

/**
 * Maps middleware sensor positions to four vehicle sides with deterministic aggregation.
 *
 * Policy (see [android_integration/ARCHITECTURE.md]):
 * 1. Worst zone level wins.
 * 2. Nearest non-null distance wins for the label.
 * 3. Source tie-break: VISION > TEST_UI > ULTRASONIC > other.
 */
class CurbZoneMapper {

    fun toCurbState(
        event: ParsedCurbEvent,
        receivedAtMs: Long = System.currentTimeMillis(),
        applyClientStale: Boolean = false,
        staleFallbackMs: Long = 800L,
    ): CurbState {
        val grouped = event.zones.groupBy { sideForPosition(it.pos) }.filterKeys { it != null }
            .mapKeys { it.key!! }

        val sides = CurbSide.entries.associateWith { side ->
            val list = grouped[side] ?: emptyList()
            if (list.isEmpty()) {
                emptySide(side, fresh = !isEventStale(event, receivedAtMs, staleFallbackMs))
            } else {
                aggregate(side, list, event, receivedAtMs, applyClientStale, staleFallbackMs)
            }
        }

        return CurbState(
            seq = event.seq,
            eventTimestampMs = event.timestampMs,
            receivedAtMs = receivedAtMs,
            sides = sides,
            dangerThresholdM = event.dangerThresholdM,
            warningThresholdM = event.warningThresholdM,
        )
    }

    fun sideForPosition(pos: String): CurbSide? = when (pos.uppercase()) {
        "FRONT_LEFT", "FRONT_CENTER", "FRONT_RIGHT" -> CurbSide.FRONT
        "REAR_LEFT", "REAR_CENTER", "REAR_RIGHT" -> CurbSide.REAR
        "LEFT_FRONT", "LEFT_REAR" -> CurbSide.LEFT
        "RIGHT_FRONT", "RIGHT_REAR" -> CurbSide.RIGHT
        else -> null
    }

    private fun aggregate(
        side: CurbSide,
        readings: List<MiddlewareZoneReading>,
        event: ParsedCurbEvent,
        receivedAtMs: Long,
        applyClientStale: Boolean,
        staleFallbackMs: Long,
    ): CurbZone {
        var worst = CurbZoneLevel.NONE
        var labelDistance: Double? = null

        for (r in readings) {
            worst = CurbZoneLevel.worst(worst, r.zone)
            val d = r.distanceM
            if (d != null && (labelDistance == null || d < labelDistance!!)) {
                labelDistance = d
            }
        }

        val atWorstLevel = readings.filter { it.zone.level == worst.level }
        val win = (if (atWorstLevel.isNotEmpty()) atWorstLevel else readings)
            .sortedWith(
                compareBy<MiddlewareZoneReading> { it.distanceM ?: Double.MAX_VALUE }
                    .thenByDescending { sourceRank(it.source) },
            )
            .first()
        var zone = worst
        var distance = labelDistance
        var color = CurbColors.parseHex(win.colorHex).let {
            if (win.colorHex != null) it else CurbColors.forZone(zone)
        }

        val eventStale = applyClientStale && isEventStale(event, receivedAtMs, staleFallbackMs)
        if (eventStale) {
            zone = CurbZoneLevel.NONE
            distance = null
            color = CurbColors.NONE_ARGB
        }

        val valid = zone != CurbZoneLevel.NONE && (zone == CurbZoneLevel.SAFE || distance != null)

        return CurbZone(
            side = side,
            distanceM = distance,
            zone = zone,
            colorArgb = color,
            source = win.source,
            valid = valid,
            fresh = !eventStale && zone != CurbZoneLevel.NONE,
            sensorPositions = readings.map { it.pos }.distinct(),
            pano = win.pano,
        )
    }

    private fun sourceRank(source: String?): Int = when (source?.uppercase()) {
        "VISION" -> 3
        "TEST_UI" -> 2
        "ULTRASONIC" -> 1
        else -> 0
    }

    private fun emptySide(side: CurbSide, fresh: Boolean): CurbZone =
        CurbZone(
            side = side,
            distanceM = null,
            zone = CurbZoneLevel.NONE,
            colorArgb = CurbColors.NONE_ARGB,
            source = null,
            valid = false,
            fresh = fresh,
        )

    private fun isEventStale(event: ParsedCurbEvent, receivedAtMs: Long, staleFallbackMs: Long): Boolean {
        if (event.timestampMs <= 0) return false
        return receivedAtMs - event.timestampMs > staleFallbackMs
    }
}
