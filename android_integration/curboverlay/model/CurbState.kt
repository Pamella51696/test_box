package com.example.seethrough.wp5.curboverlay.model

/**
 * Latest four-side curb overlay state.
 *
 * @param eventTimestampMs middleware `ts` from the curb envelope
 * @param receivedAtMs client wall clock when this state was produced
 */
data class CurbState(
    val seq: Long,
    val eventTimestampMs: Long,
    val receivedAtMs: Long,
    val sides: Map<CurbSide, CurbZone>,
    val dangerThresholdM: Double,
    val warningThresholdM: Double,
) {
    fun zone(side: CurbSide): CurbZone =
        sides[side] ?: CurbZone(
            side = side,
            distanceM = null,
            zone = CurbZoneLevel.NONE,
            colorArgb = CurbColors.NONE_ARGB,
            source = null,
            valid = false,
            fresh = false,
        )

    companion object {
        fun empty(nowMs: Long = System.currentTimeMillis()): CurbState {
            val grey = CurbZoneLevel.NONE
            val z = { s: CurbSide ->
                CurbZone(s, null, grey, CurbColors.NONE_ARGB, null, valid = false, fresh = false)
            }
            return CurbState(
                seq = -1,
                eventTimestampMs = 0,
                receivedAtMs = nowMs,
                sides = CurbSide.entries.associateWith { z(it) },
                dangerThresholdM = 0.5,
                warningThresholdM = 1.0,
            )
        }
    }
}

/** Default middleware colours (profile may override on server; prefer per-zone `color` when present). */
object CurbColors {
    const val DANGER_ARGB = 0xFFFF3B30.toInt()
    const val WARNING_ARGB = 0xFFFFCC00.toInt()
    const val SAFE_ARGB = 0xFF34C759.toInt()
    const val NONE_ARGB = 0xFF9E9E9E.toInt()

    fun forZone(level: CurbZoneLevel): Int = when (level) {
        CurbZoneLevel.DANGER -> DANGER_ARGB
        CurbZoneLevel.WARNING -> WARNING_ARGB
        CurbZoneLevel.SAFE -> SAFE_ARGB
        CurbZoneLevel.NONE -> NONE_ARGB
    }

    fun parseHex(hex: String?): Int {
        if (hex.isNullOrBlank()) return NONE_ARGB
        val h = hex.trim().removePrefix("#")
        return try {
            when (h.length) {
                6 -> (0xFF000000L or h.toLong(16)).toInt()
                8 -> h.toLong(16).toInt()
                else -> NONE_ARGB
            }
        } catch (_: Exception) {
            NONE_ARGB
        }
    }
}
