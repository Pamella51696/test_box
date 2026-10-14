package com.example.seethrough.wp5.curboverlay.model

/**
 * Normalized curb state for one vehicle side after aggregation.
 * Immutable; safe to observe on the UI thread.
 */
data class CurbZone(
    val side: CurbSide,
    val distanceM: Double?,
    val zone: CurbZoneLevel,
    val colorArgb: Int,
    val source: String?,
    val valid: Boolean,
    val fresh: Boolean,
    val sensorPositions: List<String> = emptyList(),
    val pano: PanoramaCoords? = null,
)
