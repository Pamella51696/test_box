package com.middleware.panorama.vision;

import org.opencv.core.Mat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking latest-frame buffer between the stitch loop and {@link VisionFramePublisher}.
 * The stitch thread never waits on HTTP or inference.
 */
public final class VisionFrameTap {

    public static final class FrameSlot {
        public final String camera;
        public final long frameId;
        public final long timestampMs;
        public final int width;
        public final int height;
        /** JPEG bytes; encoded on the stitch thread only when the slot is accepted. */
        public final byte[] jpeg;

        FrameSlot(String camera, long frameId, long timestampMs, int width, int height, byte[] jpeg) {
            this.camera = camera;
            this.frameId = frameId;
            this.timestampMs = timestampMs;
            this.width = width;
            this.height = height;
            this.jpeg = jpeg;
        }
    }

    private static final ConcurrentHashMap<String, FrameSlot> LATEST = new ConcurrentHashMap<>();
    private static final AtomicLong FRAME_ID = new AtomicLong();
    private static final Set<String> CAM_FILTER = parseCameras(System.getProperty("wp5.vision.cameras", "left,right,rear"));
    private static final int MAX_FPS = Math.max(1, Integer.parseInt(System.getProperty("wp5.vision.maxFps", "8")));
    private static volatile long minIntervalMs = 1000L / MAX_FPS;
    private static final ConcurrentHashMap<String, Long> LAST_OFFER_MS = new ConcurrentHashMap<>();

    private VisionFrameTap() {}

    static boolean isEnabled() {
        return VisionFramePublisher.isEnabled();
    }

    /**
     * Called from the stitch loop after undistortion. Drops frames when rate-limited or when
     * {@link VisionFramePublisher} is not keeping up (latest-only per camera).
     */
    public static void offer(String[] cameraNames, Mat[] ready) {
        if (!VisionFramePublisher.isEnabled() || cameraNames == null || ready == null) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < cameraNames.length && i < ready.length; i++) {
            String cam = cameraNames[i];
            if (!CAM_FILTER.isEmpty() && !CAM_FILTER.contains(cam)) continue;
            Mat m = ready[i];
            if (m == null || m.empty()) continue;
            Long last = LAST_OFFER_MS.get(cam);
            if (last != null && now - last < minIntervalMs) continue;
            byte[] jpeg = VisionFramePublisher.encodeJpeg(m);
            if (jpeg.length == 0) continue;
            LAST_OFFER_MS.put(cam, now);
            long fid = FRAME_ID.incrementAndGet();
            LATEST.put(cam, new FrameSlot(cam, fid, now, m.cols(), m.rows(), jpeg));
            VisionFramePublisher.signalNewFrame();
        }
    }

    /** Drain latest slots (snapshot; publisher may replace entries immediately after). */
    static ConcurrentHashMap<String, FrameSlot> snapshotLatest() {
        return new ConcurrentHashMap<>(LATEST);
    }

    private static Set<String> parseCameras(String spec) {
        if (spec == null || spec.isBlank()) return Set.of();
        Set<String> s = new HashSet<>();
        for (String p : spec.split(",")) {
            String t = p.trim().toLowerCase();
            if (!t.isEmpty()) s.add(t);
        }
        return s;
    }
}
