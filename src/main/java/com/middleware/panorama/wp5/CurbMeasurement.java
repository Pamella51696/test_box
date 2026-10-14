package com.middleware.panorama.wp5;

/**
 * Normalized curb range reading from any ingest source (PR3).
 */
public record CurbMeasurement(
        String id,
        String source,
        String position,
        String kind,
        double distanceM,
        double confidence,
        long receivedMs,
        boolean distanceValid,
        boolean syntheticCalibration,
        int panoX,
        int panoW,
        boolean panoOverride
) {
    public static final String SOURCE_VISION = "VISION";
    public static final String SOURCE_ULTRASONIC = "ULTRASONIC";
    public static final String SOURCE_TEST_UI = "TEST_UI";

    public static final String CHANNEL_VISION = "VISION";
    public static final String CHANNEL_ULTRASONIC = "ULTRASONIC";
    public static final String CHANNEL_TEST_UI = "TEST_UI";

    public String channel() {
        if (SOURCE_TEST_UI.equalsIgnoreCase(source)) return CHANNEL_TEST_UI;
        if (SOURCE_VISION.equalsIgnoreCase(source)) return CHANNEL_VISION;
        return CHANNEL_ULTRASONIC;
    }

    public boolean isClear(double maxRangeM) {
        return distanceM < 0 || distanceM >= maxRangeM;
    }
}
