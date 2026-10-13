package com.middleware.panorama.wp5;

import java.util.Locale;

/**
 * Source-aware selection for curb distance at one logical position (PR3).
 * Does not average incompatible readings.
 */
final class CurbFusionEngine {

    static final class FusionPick {
        final CurbMeasurement selected;
        final boolean conflict;
        final String mode;

        FusionPick(CurbMeasurement selected, boolean conflict, String mode) {
            this.selected = selected;
            this.conflict = conflict;
            this.mode = mode;
        }
    }

    private CurbFusionEngine() {}

    static FusionPick fuse(
            String pos,
            CurbMeasurement vision,
            CurbMeasurement ultrasonic,
            FusionConfig fusion,
            Wp5Config cfg,
            long nowMs
    ) {
        boolean vOk = fusion.visionEnabled && CurbHealthMonitor.isUsable(vision, cfg, fusion, nowMs);
        boolean uOk = fusion.ultrasonicEnabled && CurbHealthMonitor.isUsable(ultrasonic, cfg, fusion, nowMs);

        if (fusion.mode == FusionConfig.Mode.VISION_ONLY) {
            if (vOk) return new FusionPick(vision, false, "vision_only");
            return new FusionPick(null, false, "vision_only");
        }

        boolean conflict = false;
        if (vOk && uOk && fusion.conflictDetection) {
            double delta = Math.abs(vision.distanceM() - ultrasonic.distanceM());
            if (delta > fusion.conflictThresholdM) conflict = true;
        }

        if (fusion.mode == FusionConfig.Mode.FUSION && vOk && uOk && !conflict) {
            // Comparable readings: prefer higher-priority source (same as source_priority when they agree).
            CurbMeasurement pick = pickByPriority(vision, ultrasonic, fusion);
            return new FusionPick(pick, false, "fusion");
        }

        if (vOk && uOk) {
            CurbMeasurement pick = pickByPriority(vision, ultrasonic, fusion);
            return new FusionPick(pick, conflict, "source_priority");
        }
        if (vOk) return new FusionPick(vision, false, "source_priority");
        if (uOk) return new FusionPick(ultrasonic, false, "source_priority");
        return new FusionPick(null, false, fusion.mode.name().toLowerCase(Locale.ROOT));
    }

    private static CurbMeasurement pickByPriority(CurbMeasurement vision, CurbMeasurement ultrasonic, FusionConfig fusion) {
        int vp = fusion.visionPriority;
        int up = fusion.ultrasonicPriority;
        if (up < vp) return ultrasonic;
        if (vp < up) return vision;
        return ultrasonic; // tie -> ultrasonic default
    }
}
