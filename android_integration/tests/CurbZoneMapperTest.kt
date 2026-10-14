package com.example.seethrough.wp5.curboverlay

import com.example.seethrough.wp5.curboverlay.mapper.CurbZoneMapper
import com.example.seethrough.wp5.curboverlay.model.CurbSide
import com.example.seethrough.wp5.curboverlay.model.CurbZoneLevel
import com.example.seethrough.wp5.curboverlay.parser.CurbJsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class CurbZoneMapperTest {

    private val parser = CurbJsonParser()
    private val mapper = CurbZoneMapper()
    private val fixtures = Path.of("..", "fixtures").normalize()

    private fun load(name: String) = parser.parseCurbEvent(Files.readString(fixtures.resolve(name)))!!

    @Test
    fun maps_all_sensor_positions() {
        assertEquals(CurbSide.FRONT, mapper.sideForPosition("FRONT_LEFT"))
        assertEquals(CurbSide.FRONT, mapper.sideForPosition("FRONT_CENTER"))
        assertEquals(CurbSide.REAR, mapper.sideForPosition("REAR_RIGHT"))
        assertEquals(CurbSide.LEFT, mapper.sideForPosition("LEFT_FRONT"))
        assertEquals(CurbSide.RIGHT, mapper.sideForPosition("RIGHT_REAR"))
        assertNull(mapper.sideForPosition("ROOF"))
    }

    @Test
    fun four_sides_from_middleware() {
        val state = mapper.toCurbState(load("curb_four_sides.json"))
        assertEquals(CurbZoneLevel.DANGER, state.zone(CurbSide.FRONT).zone)
        assertEquals(0.42, state.zone(CurbSide.FRONT).distanceM!!, 0.001)
        assertEquals(CurbZoneLevel.SAFE, state.zone(CurbSide.REAR).zone)
        assertEquals(CurbZoneLevel.WARNING, state.zone(CurbSide.LEFT).zone)
        assertEquals(CurbZoneLevel.WARNING, state.zone(CurbSide.RIGHT).zone)
    }

    @Test
    fun front_aggregation_worst_and_nearest() {
        val front = mapper.toCurbState(load("curb_multi_front_aggregate.json")).zone(CurbSide.FRONT)
        assertEquals(CurbZoneLevel.DANGER, front.zone)
        assertEquals(0.35, front.distanceM!!, 0.001)
        assertEquals("VISION", front.source)
    }

    @Test
    fun left_maps_from_left_rear_sample() {
        val left = mapper.toCurbState(load("curb_wp5_sample.json")).zone(CurbSide.LEFT)
        assertEquals(CurbZoneLevel.WARNING, left.zone)
        assertNotNull(left.distanceM)
    }

    @Test
    fun none_zone_invalid_not_safe() {
        val rear = mapper.toCurbState(load("curb_stale_none.json")).zone(CurbSide.REAR)
        assertEquals(CurbZoneLevel.NONE, rear.zone)
        assertFalse(rear.valid)
        assertNull(rear.distanceM)
    }

    @Test
    fun client_stale_greys_sides() {
        val event = load("curb_four_sides.json")
        val oldTs = event.copy(timestampMs = 1)
        val state = mapper.toCurbState(
            oldTs,
            receivedAtMs = 2000,
            applyClientStale = true,
            staleFallbackMs = 500,
        )
        assertEquals(CurbZoneLevel.NONE, state.zone(CurbSide.FRONT).zone)
        assertFalse(state.zone(CurbSide.FRONT).fresh)
    }
}
