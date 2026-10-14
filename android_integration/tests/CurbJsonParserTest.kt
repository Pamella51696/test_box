package com.example.seethrough.wp5.curboverlay

import com.example.seethrough.wp5.curboverlay.model.CurbZoneLevel
import com.example.seethrough.wp5.curboverlay.parser.CurbJsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class CurbJsonParserTest {

    private val parser = CurbJsonParser()
    private val fixtures = Path.of("..", "fixtures").normalize()

    private fun fixture(name: String): String =
        Files.readString(fixtures.resolve(name))

    @Test
    fun parses_wp5_sample() {
        val event = parser.parseCurbEvent(fixture("curb_wp5_sample.json"))
        assertNotNull(event)
        assertEquals(12, event!!.seq)
        assertEquals(1, event.zones.size)
        assertEquals("LEFT_REAR", event.zones[0].pos)
        assertEquals(CurbZoneLevel.WARNING, event.zones[0].zone)
        assertEquals(0.73, event.zones[0].distanceM!!, 0.001)
    }

    @Test
    fun ignores_obstacle_kind() {
        val event = parser.parseCurbEvent(fixture("curb_obstacle_ignored.json"))!!
        assertEquals(1, event.zones.size)
        assertEquals("LEFT_REAR", event.zones[0].pos)
    }

    @Test
    fun malformed_returns_null() {
        assertNull(parser.parseCurbEvent("{not json"))
        assertNull(parser.parseCurbEvent("{\"type\":\"steering\"}"))
    }

    @Test
    fun negative_distance_treated_as_clear_null() {
        val json = """{"type":"curb","zones":[{"pos":"REAR_CENTER","kind":"CURB","distanceM":-1,"zone":"SAFE"}]}"""
        val z = parser.parseCurbEvent(json)!!.zones.single()
        assertNull(z.distanceM)
        assertEquals(CurbZoneLevel.SAFE, z.zone)
    }

    @Test
    fun nan_distance_skipped_or_none() {
        val json = """{"type":"curb","zones":[{"pos":"REAR_CENTER","kind":"CURB","distanceM":"NaN","zone":"DANGER"}]}"""
        val event = parser.parseCurbEvent(json)
        assertNotNull(event)
        val z = event!!.zones.single()
        assertNull(z.distanceM)
    }

    @Test
    fun fallback_classify_when_zone_missing() {
        val json = """{"type":"curb","thresholds":{"dangerM":0.5,"warningM":1.0},
            "zones":[{"pos":"FRONT_CENTER","kind":"CURB","distanceM":0.42}]}"""
        val z = parser.parseCurbEvent(json)!!.zones.single()
        assertEquals(CurbZoneLevel.DANGER, z.zone)
    }

    @Test
    fun preserves_pano_norms() {
        val z = parser.parseCurbEvent(fixture("curb_wp5_sample.json"))!!.zones[0]
        assertNotNull(z.pano)
        assertEquals(0.483f, z.pano!!.xNorm!!, 0.001f)
    }

    @Test
    fun test_ui_source_preserved() {
        val z = parser.parseCurbEvent(fixture("curb_test_ui_left.json"))!!.zones[0]
        assertEquals("TEST_UI", z.source)
        assertTrue(z.pos.contains("LEFT"))
    }
}
