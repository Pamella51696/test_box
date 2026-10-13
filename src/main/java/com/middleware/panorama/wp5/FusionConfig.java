package com.middleware.panorama.wp5;

import java.util.Locale;

/**
 * Java-side curb sensor fusion (PR3). Configure with {@code -Dwp5.fusion.*} system properties.
 */
public final class FusionConfig {

    public enum Mode {
        VISION_ONLY,
        SOURCE_PRIORITY,
        FUSION
    }

    public final boolean enabled;
    public final Mode mode;
    public final boolean visionEnabled;
    public final int visionPriority;
    public final boolean ultrasonicEnabled;
    public final int ultrasonicPriority;
    public final boolean conflictDetection;
    public final double conflictThresholdM;
    public final long staleTimeoutMs;
    public final boolean allowSynthetic;

    private FusionConfig() {
        enabled = Boolean.parseBoolean(System.getProperty("wp5.fusion.enabled", "false"));
        mode = parseMode(System.getProperty("wp5.fusion.mode", "source_priority"));
        visionEnabled = !"false".equalsIgnoreCase(System.getProperty("wp5.fusion.vision.enabled", "true"));
        ultrasonicEnabled = !"false".equalsIgnoreCase(System.getProperty("wp5.fusion.ultrasonic.enabled", "true"));
        visionPriority = (int) parseDouble("wp5.fusion.vision.priority", 2);
        ultrasonicPriority = (int) parseDouble("wp5.fusion.ultrasonic.priority", 1);
        conflictDetection = !"false".equalsIgnoreCase(System.getProperty("wp5.fusion.conflict.enabled", "true"));
        conflictThresholdM = parseDouble("wp5.fusion.conflict.thresholdM", 0.30);
        staleTimeoutMs = (long) parseDouble("wp5.fusion.stale.timeoutMs", 500);
        allowSynthetic = Boolean.parseBoolean(System.getProperty("wp5.fusion.calibration.allowSynthetic", "false"));
    }

    public static FusionConfig load() {
        return new FusionConfig();
    }

    private static Mode parseMode(String raw) {
        if (raw == null) return Mode.SOURCE_PRIORITY;
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "vision_only", "vision" -> Mode.VISION_ONLY;
            case "fusion", "combine" -> Mode.FUSION;
            default -> Mode.SOURCE_PRIORITY;
        };
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
