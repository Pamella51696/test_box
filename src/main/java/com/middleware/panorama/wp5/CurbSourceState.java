package com.middleware.panorama.wp5;

import java.util.LinkedHashMap;
import java.util.Map;

/** Latest measurement per logical position and source channel (vision vs ultrasonic). */
final class CurbSourceState {

    private final Map<String, Map<String, CurbMeasurement>> byPos = new LinkedHashMap<>();

    synchronized void put(CurbMeasurement m) {
        byPos.computeIfAbsent(m.position(), k -> new LinkedHashMap<>())
                .put(m.channel(), m);
    }

    synchronized CurbMeasurement get(String pos, String channel) {
        Map<String, CurbMeasurement> m = byPos.get(pos);
        return m == null ? null : m.get(channel);
    }

    synchronized Iterable<String> positions() {
        return byPos.keySet();
    }

    synchronized void clear() {
        byPos.clear();
    }

    synchronized void remove(String pos, String channel) {
        Map<String, CurbMeasurement> m = byPos.get(pos);
        if (m != null) {
            m.remove(channel);
            if (m.isEmpty()) byPos.remove(pos);
        }
    }

    synchronized CurbMeasurement getTestUi(String pos) {
        return get(pos, CurbMeasurement.CHANNEL_TEST_UI);
    }
}
