package com.example.seethrough.wp5.curboverlay.parser

import com.example.seethrough.wp5.curboverlay.model.CurbZoneLevel
import com.example.seethrough.wp5.curboverlay.model.PanoramaCoords
import org.json.JSONObject

/**
 * Pure JSON parser for WP5 `curb` SSE events.
 * No networking, no Android UI, no distance re-classification when `zone` is present.
 */
class CurbJsonParser {

    fun parseCurbEvent(json: String): ParsedCurbEvent? = try {
        parseCurbEvent(JSONObject(json))
    } catch (_: Exception) {
        null
    }

    fun parseCurbEvent(root: JSONObject): ParsedCurbEvent? {
        if (root.optString("type") != "curb" && !root.has("zones")) return null

        val thresholds = root.optJSONObject("thresholds")
        val danger = thresholds?.optDouble("dangerM", 0.5) ?: 0.5
        val warning = thresholds?.optDouble("warningM", 1.0) ?: 1.0

        val zonesArray = root.optJSONArray("zones") ?: return null
        val readings = mutableListOf<MiddlewareZoneReading>()
        for (i in 0 until zonesArray.length()) {
            val z = zonesArray.optJSONObject(i) ?: continue
            parseZone(z, danger, warning)?.let { readings.add(it) }
        }

        return ParsedCurbEvent(
            seq = root.optLong("seq", -1),
            timestampMs = root.optLong("ts", 0),
            dangerThresholdM = danger,
            warningThresholdM = warning,
            zones = readings,
        )
    }

    private fun parseZone(z: JSONObject, dangerM: Double, warningM: Double): MiddlewareZoneReading? {
        val kind = z.optString("kind", "CURB")
        if (kind != "CURB") return null

        val pos = z.optString("pos", "")
        if (pos.isBlank()) return null

        val id = z.optString("id", pos)
        val source = z.optString("source", null)?.takeIf { it.isNotBlank() }

        val distanceM = parseDistance(z.opt("distanceM"))
        val zoneName = z.optString("zone", null)
        val zone = when {
            !zoneName.isNullOrBlank() -> CurbZoneLevel.fromName(zoneName)
            distanceM != null -> classifyFallback(distanceM, dangerM, warningM)
            else -> CurbZoneLevel.NONE
        }

        val level = if (z.has("level")) z.optInt("level", zone.level) else zone.level
        val colorHex = z.optString("color", null)?.takeIf { it.isNotBlank() }

        val pano = z.optJSONObject("pano")?.let { p ->
            PanoramaCoords(
                x = if (p.has("x")) p.optInt("x") else null,
                w = if (p.has("w")) p.optInt("w") else null,
                xNorm = if (p.has("xNorm")) p.optDouble("xNorm").toFloat() else null,
                wNorm = if (p.has("wNorm")) p.optDouble("wNorm").toFloat() else null,
            )
        }

        return MiddlewareZoneReading(
            id = id,
            pos = pos,
            source = source,
            distanceM = distanceM,
            zone = zone,
            level = level,
            colorHex = colorHex,
            pano = pano,
        )
    }

    private fun parseDistance(raw: Any?): Double? {
        if (raw == null || raw == JSONObject.NULL) return null
        val d: Double = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull() ?: return null
            else -> return null
        }
        if (d.isNaN() || d.isInfinite()) return null
        if (d < 0) return null // no echo / clear — zone field carries SAFE from middleware
        return d
    }

    private fun classifyFallback(distanceM: Double, dangerM: Double, warningM: Double): CurbZoneLevel {
        if (distanceM <= dangerM) return CurbZoneLevel.DANGER
        if (distanceM <= warningM) return CurbZoneLevel.WARNING
        return CurbZoneLevel.SAFE
    }
}
