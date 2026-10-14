package com.example.seethrough.wp5.curboverlay.renderer

import com.example.seethrough.wp5.curboverlay.model.CurbColors
import com.example.seethrough.wp5.curboverlay.model.CurbSide
import com.example.seethrough.wp5.curboverlay.model.CurbState
import com.example.seethrough.wp5.curboverlay.model.CurbZone
import com.example.seethrough.wp5.curboverlay.model.CurbZoneLevel

/** Pure label/colour helpers (unit-testable without Android canvas). */
object CurbOverlayRenderer {

    fun label(zone: CurbZone): String {
        val dist = zone.distanceM?.let { "%.2f m".format(it) }
        return when {
            zone.zone == CurbZoneLevel.NONE || !zone.valid -> "Unknown"
            dist != null -> dist
            else -> zone.zone.name
        }
    }

    fun subtitle(zone: CurbZone): String =
        when {
            zone.zone == CurbZoneLevel.NONE -> "—"
            zone.source != null -> zone.zone.name + " · " + zone.source
            else -> zone.zone.name
        }

    fun indicatorColor(zone: CurbZone): Int =
        if (zone.zone == CurbZoneLevel.NONE || !zone.fresh) CurbColors.NONE_ARGB else zone.colorArgb

    fun sideTitle(side: CurbSide): String = side.name
}
