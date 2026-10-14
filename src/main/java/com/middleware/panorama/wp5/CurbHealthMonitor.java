package com.middleware.panorama.wp5;

/** Staleness and validity checks for fusion inputs (PR3). */
final class CurbHealthMonitor {

    private CurbHealthMonitor() {}

    static boolean isFresh(CurbMeasurement m, long nowMs, long staleMs) {
        return m != null && nowMs - m.receivedMs() <= staleMs;
    }

    static boolean isUsable(CurbMeasurement m, Wp5Config cfg, FusionConfig fusion, long nowMs) {
        if (m == null || !isFresh(m, nowMs, fusion.staleTimeoutMs > 0 ? fusion.staleTimeoutMs : cfg.staleMs)) {
            return false;
        }
        if (!"CURB".equalsIgnoreCase(m.kind())) return false;
        if (CurbMeasurement.SOURCE_TEST_UI.equalsIgnoreCase(m.source())) {
            return m.distanceValid() && m.distanceM() > 0;
        }
        if (!m.distanceValid()) return false;
        if (m.syntheticCalibration() && !fusion.allowSynthetic) return false;
        return true;
    }

    static boolean isTestUiUsable(CurbMeasurement m, VisionTestConfig testCfg, long nowMs) {
        if (m == null || !CurbMeasurement.SOURCE_TEST_UI.equalsIgnoreCase(m.source())) return false;
        if (!isFresh(m, nowMs, testCfg.ttlMs)) return false;
        return m.distanceValid() && m.distanceM() > 0 && m.distanceM() < 1e6;
    }
}
