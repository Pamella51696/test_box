package com.example.seethrough.wp5.curboverlay.parser

import com.example.seethrough.wp5.curboverlay.model.CurbZoneLevel
import com.example.seethrough.wp5.curboverlay.model.PanoramaCoords

/** One `zones[]` entry after JSON validation (CURB kind only). */
data class MiddlewareZoneReading(
    val id: String,
    val pos: String,
    val source: String?,
    val distanceM: Double?,
    val zone: CurbZoneLevel,
    val level: Int,
    val colorHex: String?,
    val pano: PanoramaCoords?,
)

/** Parsed curb SSE payload (middleware envelope). */
data class ParsedCurbEvent(
    val seq: Long,
    val timestampMs: Long,
    val dangerThresholdM: Double,
    val warningThresholdM: Double,
    val zones: List<MiddlewareZoneReading>,
)
