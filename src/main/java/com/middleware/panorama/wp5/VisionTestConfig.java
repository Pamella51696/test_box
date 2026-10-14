package com.middleware.panorama.wp5;

/**
 * Browser / fake-vehicle curb-in-frame test controls (PR6). Disabled by default in production.
 */
public final class VisionTestConfig {

    public final boolean enabled;
    public final long ttlMs;
    public final double minDistanceM;
    public final double maxDistanceM;

    private VisionTestConfig() {
        enabled = Boolean.parseBoolean(System.getProperty("wp5.visionTest.enabled", "false"));
        ttlMs = (long) parseDouble("wp5.visionTest.ttlMs", 800);
        minDistanceM = parseDouble("wp5.visionTest.minDistanceM", 0.1);
        maxDistanceM = parseDouble("wp5.visionTest.maxDistanceM", 2.4);
    }

    public static VisionTestConfig load() {
        return new VisionTestConfig();
    }

    private static double parseDouble(String key, double def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
