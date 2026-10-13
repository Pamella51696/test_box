package com.middleware.panorama.vision;

/**
 * Latest vision detection JSON from the Python sidecar (PR 1 overlay channel).
 * Separate from ultrasonic {@code CurbModel} / curb SSE.
 */
public final class VisionDetectionHub {

    private static volatile String latestJson = "";
    private static volatile long receivedMs;
    private static volatile long frameId = -1;

    private VisionDetectionHub() {}

    public static synchronized void publish(String json) {
        latestJson = json == null ? "" : json;
        receivedMs = System.currentTimeMillis();
        frameId = extractFrameId(json);
    }

    public static String latest() {
        return latestJson;
    }

    public static long ageMs() {
        long r = receivedMs;
        return r == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - r;
    }

    public static long frameId() {
        return frameId;
    }

    private static long extractFrameId(String json) {
        if (json == null || json.isBlank()) return -1;
        int i = json.indexOf("\"frameId\"");
        if (i < 0) return -1;
        int c = json.indexOf(':', i);
        if (c < 0) return -1;
        int j = c + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        int k = j;
        while (k < json.length() && (Character.isDigit(json.charAt(k)) || json.charAt(k) == '-')) k++;
        try {
            return Long.parseLong(json.substring(j, k));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
