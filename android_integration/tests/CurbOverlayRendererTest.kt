package com.example.seethrough.wp5.curboverlay

import com.example.seethrough.wp5.curboverlay.mapper.CurbZoneMapper
import com.example.seethrough.wp5.curboverlay.model.CurbColors
import com.example.seethrough.wp5.curboverlay.model.CurbSide
import com.example.seethrough.wp5.curboverlay.model.CurbZoneLevel
import com.example.seethrough.wp5.curboverlay.parser.CurbJsonParser
import com.example.seethrough.wp5.curboverlay.renderer.CurbOverlayRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class CurbOverlayRendererTest {

    @Test
    fun label_unknown_for_none() {
        val mapper = CurbZoneMapper()
        val event = CurbJsonParser().parseCurbEvent(
            Files.readString(Path.of("..", "fixtures", "curb_stale_none.json").normalize()),
        )!!
        val zone = mapper.toCurbState(event).zone(CurbSide.REAR)
        assertEquals("Unknown", CurbOverlayRenderer.label(zone))
        assertEquals(CurbColors.NONE_ARGB, CurbOverlayRenderer.indicatorColor(zone))
    }

    @Test
    fun label_formats_distance() {
        val mapper = CurbZoneMapper()
        val event = CurbJsonParser().parseCurbEvent(
            Files.readString(Path.of("..", "fixtures", "curb_four_sides.json").normalize()),
        )!!
        val label = CurbOverlayRenderer.label(mapper.toCurbState(event).zone(CurbSide.FRONT))
        assertTrue(label.contains("0.42"))
    }
}
