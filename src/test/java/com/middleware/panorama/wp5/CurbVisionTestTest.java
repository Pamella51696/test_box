package com.middleware.panorama.wp5;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PR6: TEST_UI curb-in-frame ingest isolated from VISION / ULTRASONIC. */
class CurbVisionTestTest {

    private static final String[] CAMS = {"front", "rear", "left", "right"};

    @BeforeAll
    static void profile() {
        System.setProperty("wp5.profile", "none");
    }

    @BeforeEach
    void enableTest() {
        System.setProperty("wp5.visionTest.enabled", "true");
        System.setProperty("wp5.visionTest.ttlMs", "500");
    }

    @AfterEach
    void clearProps() {
        System.clearProperty("wp5.visionTest.enabled");
        System.clearProperty("wp5.visionTest.ttlMs");
        System.clearProperty("wp5.fusion.enabled");
    }

    private CurbModel model(boolean fusion) {
        if (fusion) System.setProperty("wp5.fusion.enabled", "true");
        Wp5Config cfg = Wp5Config.load();
        return new CurbModel(cfg, FusionConfig.load(), VisionTestConfig.load(), CAMS, 640, 360, 80);
    }

    private static String visionTest(String side, boolean detected, double d) {
        if (!detected) {
            return "{\"sides\":[{\"side\":\"" + side + "\",\"detected\":false,\"distanceM\":null,\"confidence\":0}]}";
        }
        return "{\"sides\":[{\"side\":\"" + side + "\",\"detected\":true,\"distanceM\":" + d
                + ",\"confidence\":1.0}]}";
    }

    @Test
    void testUi_showsDangerZone_withoutFusion() {
        CurbModel m = model(false);
        assertEquals(1, m.ingestVisionTest(visionTest("left", true, 0.4), 1000));
        String j = m.snapshot(1, 1000).json;
        assertTrue(j.contains("\"source\":\"TEST_UI\""), j);
        assertTrue(j.contains("\"zone\":\"DANGER\""), j);
        assertTrue(j.contains("\"pos\":\"LEFT_REAR\""), j);
    }

    @Test
    void clearingSide_doesNotRemoveVisionChannel() {
        System.setProperty("wp5.fusion.enabled", "true");
        CurbModel m = model(true);
        m.ingestJson("{\"sensors\":[{\"id\":\"V\",\"pos\":\"LEFT_REAR\",\"distanceM\":1.5,\"kind\":\"CURB\","
                + "\"source\":\"VISION\",\"distanceValid\":true}]}", 1000);
        m.ingestVisionTest(visionTest("left", true, 0.35), 1000);
        String during = m.snapshot(1, 1000).json;
        assertTrue(during.contains("\"selectedSource\":\"TEST_UI\"") || during.contains("\"source\":\"TEST_UI\""), during);

        m.ingestVisionTest(visionTest("left", false, 0), 1100);
        String after = m.snapshot(2, 1100).json;
        assertTrue(after.contains("\"source\":\"VISION\""), after);
    }

    @Test
    void testExpiresAfterTtl() {
        CurbModel m = model(false);
        m.ingestVisionTest(visionTest("right", true, 0.5), 1000);
        assertTrue(m.snapshot(1, 1000).json.contains("TEST_UI"));
        String stale = m.snapshot(2, 2000).json;
        assertFalse(stale.contains("TEST_UI_RIGHT_REAR"), stale);
    }

    @Test
    void invalidSide_returnsZeroAccepted() {
        CurbModel m = model(false);
        assertThrows(IllegalArgumentException.class, () ->
                m.ingestVisionTest("{\"sides\":[{\"side\":\"top\",\"detected\":true,\"distanceM\":0.5,\"confidence\":1}]}", 0));
    }

    @Test
    void disabledTestIngest_rejects() {
        System.setProperty("wp5.visionTest.enabled", "false");
        CurbModel m = model(false);
        assertThrows(IllegalStateException.class, () -> m.ingestVisionTest(visionTest("left", true, 0.7), 0));
    }
}
