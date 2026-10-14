package com.middleware.panorama.wp5;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and validates POST /api/v1/ingest/vision-test (PR6). */
final class CurbVisionTestIngest {

    private static final Pattern SIDE_OBJ = Pattern.compile("\\{[^{}]*\\}");
    private static final String[] VALID_SIDES = {"left", "right", "front", "rear"};

    static final Map<String, String> SIDE_TO_POS = Map.of(
            "left", "LEFT_REAR",
            "right", "RIGHT_REAR",
            "front", "FRONT_CENTER",
            "rear", "REAR_CENTER"
    );

    private CurbVisionTestIngest() {}

    static final class SideUpdate {
        final String side;
        final boolean detected;
        final double distanceM;
        final double confidence;

        SideUpdate(String side, boolean detected, double distanceM, double confidence) {
            this.side = side;
            this.detected = detected;
            this.distanceM = distanceM;
            this.confidence = confidence;
        }
    }

    static java.util.List<SideUpdate> parse(String body, VisionTestConfig cfg) {
        if (body == null || body.isBlank() || !body.contains("sides")) {
            throw new IllegalArgumentException("sides array required");
        }
        int arr = body.indexOf("\"sides\"");
        if (arr < 0) throw new IllegalArgumentException("sides array required");
        int start = body.indexOf('[', arr);
        int end = body.indexOf(']', start);
        if (start < 0 || end < 0) throw new IllegalArgumentException("malformed sides array");
        String slice = body.substring(start, end + 1);
        java.util.List<SideUpdate> out = new java.util.ArrayList<>();
        Matcher m = SIDE_OBJ.matcher(slice);
        while (m.find()) {
            String o = m.group();
            String side = Json.getStr(o, "side", "").toLowerCase(Locale.ROOT);
            if (!SIDE_TO_POS.containsKey(side)) {
                throw new IllegalArgumentException("invalid side: " + side);
            }
            boolean detected = Json.getBool(o, "detected", false);
            double conf = Json.getNum(o, "confidence", detected ? 1.0 : 0.0);
            if (!Double.isFinite(conf) || conf < 0 || conf > 1) {
                throw new IllegalArgumentException("confidence out of range for side " + side);
            }
            if (!detected) {
                out.add(new SideUpdate(side, false, -1, conf));
                continue;
            }
            double d = Json.getNum(o, "distanceM", Double.NaN);
            if (!Double.isFinite(d) || d <= 0) {
                throw new IllegalArgumentException("detected side requires positive finite distanceM: " + side);
            }
            if (d < cfg.minDistanceM || d > cfg.maxDistanceM) {
                throw new IllegalArgumentException("distanceM out of test range for " + side);
            }
            out.add(new SideUpdate(side, true, d, conf));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("empty sides array");
        return out;
    }
}
