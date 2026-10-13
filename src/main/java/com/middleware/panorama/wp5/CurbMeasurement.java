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
    public static final String CHANNEL_VISION = "VISION";
    public static final String CHANNEL_ULTRASONIC = "ULTRASONIC";

    public String channel() {
        return CurbMeasurement.CHANNEL_VISION.equalsIgnoreCase(source) ? CHANNEL_VISION : CHANNEL_ULTRASONIC;
    }

    public boolean isClear(double maxRangeM) {
        return distanceM < 0 || distanceM >= maxRangeM;
    }
}
