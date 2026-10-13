package com.middleware.panorama.wp5;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PR3: source-aware fusion per logical position (deterministic replay). */
class CurbFusionTest {

    private static final String[] CAMS = {"front", "rear", "left", "right"};

    @BeforeAll
    static void defaultsOnly() {
        System.setProperty("wp5.profile", "none");
    }

    @BeforeEach
    void enableFusion() {
        System.setProperty("wp5.fusion.enabled", "true");
        System.setProperty("wp5.fusion.mode", "source_priority");
        System.setProperty("wp5.fusion.ultrasonic.priority", "1");
        System.setProperty("wp5.fusion.vision.priority", "2");
        System.setProperty("wp5.fusion.conflict.thresholdM", "0.30");
        System.setProperty("wp5.fusion.calibration.allowSynthetic", "false");
    }

    @AfterEach
    void clearFusionProps() {
        System.clearProperty("wp5.fusion.enabled");
        System.clearProperty("wp5.fusion.mode");
        System.clearProperty("wp5.fusion.calibration.allowSynthetic");
    }

    private CurbModel model() {
        Wp5Config cfg = Wp5Config.load();
        return new CurbModel(cfg, FusionConfig.load(), CAMS, 640, 360, 80);
    }

    private static String sensor(String id, String pos, double d, String source) {
        return "{\"sensors\":[{\"id\":\"" + id + "\",\"pos\":\"" + pos + "\",\"distanceM\":" + d
                + ",\"kind\":\"CURB\",\"source\":\"" + source + "\",\"distanceValid\":true}]}";
    }

    @Test
    void ultrasonicOnly_oneZonePerPosition() {
        CurbModel m = model();
        m.ingestJson(sensor("U", "LEFT_REAR", 0.8, "ULTRASONIC"), 1000);
        String j = m.snapshot(1, 1000).json;
        assertTrue(j.contains("\"pos\":\"LEFT_REAR\""), j);
        assertTrue(j.contains("\"source\":\"ULTRASONIC\""), j);
        assertTrue(j.contains("\"zone\":\"WARNING\""), j);
        assertFalse(j.contains("\"conflict\":true"), j);
    }

    @Test
    void sourcePriority_prefersUltrasonicWhenBothValid() {
        CurbModel m = model();
        m.ingestJson("{\"sensors\":["
                + "{\"id\":\"V\",\"pos\":\"LEFT_REAR\",\"distanceM\":0.40,\"kind\":\"CURB\",\"source\":\"VISION\",\"distanceValid\":true},"
                + "{\"id\":\"U\",\"pos\":\"LEFT_REAR\",\"distanceM\":1.20,\"kind\":\"CURB\",\"source\":\"ULTRASONIC\",\"distanceValid\":true}"
                + "]}", 1000);
        String j = m.snapshot(1, 1000).json;
        assertTrue(j.contains("\"selectedSource\":\"ULTRASONIC\""), j);
        assertTrue(j.contains("\"conflict\":true"), j);
        assertTrue(j.contains("\"zone\":\"SAFE\""), j); // 1.2 m beyond warning threshold
    }

    @Test
    void visionOnlyMode_usesVision() {
        System.setProperty("wp5.fusion.mode", "vision_only");
        CurbModel m = model();
        m.ingestJson("{\"sensors\":["
                + "{\"id\":\"V\",\"pos\":\"LEFT_REAR\",\"distanceM\":0.35,\"kind\":\"CURB\",\"source\":\"VISION\",\"distanceValid\":true},"
                + "{\"id\":\"U\",\"pos\":\"LEFT_REAR\",\"distanceM\":1.20,\"kind\":\"CURB\",\"source\":\"ULTRASONIC\",\"distanceValid\":true}"
                + "]}", 1000);
        String j = m.snapshot(1, 1000).json;
        assertTrue(j.contains("\"selectedSource\":\"VISION\""), j);
        assertTrue(j.contains("\"zone\":\"DANGER\""), j);
    }

    @Test
    void obstacleNotFusedAsCurb() {
        CurbModel m = model();
        int ok = m.ingestJson("{\"sensors\":["
                + "{\"id\":\"O\",\"pos\":\"REAR_CENTER\",\"distanceM\":0.2,\"kind\":\"OBSTACLE\",\"source\":\"ULTRASONIC\"},"
                + "{\"id\":\"C\",\"pos\":\"LEFT_REAR\",\"distanceM\":0.8,\"kind\":\"CURB\",\"source\":\"ULTRASONIC\",\"distanceValid\":true}"
                + "]}", 0);
        assertEquals(1, ok);
        String j = m.snapshot(1, 0).json;
        assertFalse(j.contains("REAR_CENTER"), j);
        assertTrue(j.contains("LEFT_REAR"), j);
    }

    @Test
    void syntheticVisionRejectedUnlessAllowed() {
        CurbModel m = model();
        m.ingestJson("{\"sensors\":[{\"id\":\"V\",\"pos\":\"LEFT_REAR\",\"distanceM\":0.4,\"kind\":\"CURB\","
                + "\"source\":\"VISION\",\"distanceValid\":true,\"calibrationType\":\"synthetic\"}]}", 1000);
        String j = m.snapshot(1, 1000).json;
        assertTrue(j.contains("\"zone\":\"NONE\""), j);
        System.setProperty("wp5.fusion.calibration.allowSynthetic", "true");
        CurbModel m2 = new CurbModel(Wp5Config.load(), FusionConfig.load(), CAMS, 640, 360, 80);
        m2.ingestJson("{\"sensors\":[{\"id\":\"V\",\"pos\":\"LEFT_REAR\",\"distanceM\":0.4,\"kind\":\"CURB\","
                + "\"source\":\"VISION\",\"distanceValid\":true,\"calibrationType\":\"synthetic\"}]}", 1000);
        j = m2.snapshot(1, 1000).json;
        assertTrue(j.contains("\"zone\":\"DANGER\""), j);
    }

    @Test
    void visionStale_fallsBackToUltrasonic() {
        CurbModel m = model();
        m.ingestJson(sensor("V", "LEFT_REAR", 0.35, "VISION"), 1000);
        m.ingestJson(sensor("U", "LEFT_REAR", 0.90, "ULTRASONIC"), 1000);
        Wp5Config cfg = Wp5Config.load();
        String j = m.snapshot(1, 1000 + cfg.staleMs + 5).json;
        // both stale -> NONE
        assertTrue(j.contains("\"zone\":\"NONE\""), j);
        m.ingestJson(sensor("U", "LEFT_REAR", 0.90, "ULTRASONIC"), 2000);
        j = m.snapshot(2, 2000).json;
        assertTrue(j.contains("\"selectedSource\":\"ULTRASONIC\""), j);
        assertTrue(j.contains("\"zone\":\"WARNING\""), j);
    }
}
