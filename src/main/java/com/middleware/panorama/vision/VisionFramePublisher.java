package com.middleware.panorama.vision;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pushes undistorted camera frames from the stitch loop to a vision sidecar (Python + ONNX).
 * Disabled unless -Dwp5.vision.url is set (e.g. http://127.0.0.1:8765/frame).
 */
public final class VisionFramePublisher {

    private static final String URL_PROP = System.getProperty("wp5.vision.url", "").trim();
    private static final int MAX_FPS = Math.max(1, Integer.parseInt(System.getProperty("wp5.vision.maxFps", "5")));
    private static final Set<String> CAM_FILTER = parseCameras(System.getProperty("wp5.vision.cameras", "left,right,rear"));

    private static final boolean ENABLED = !URL_PROP.isEmpty();
    private static final ExecutorService EXEC = ENABLED
            ? Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "vision-frame-publisher");
                t.setDaemon(true);
                return t;
            })
            : null;

    private static final AtomicLong SEQ = new AtomicLong();
    private static volatile long lastSendMs;
    private static volatile long minIntervalMs = 1000L / MAX_FPS;

    private VisionFramePublisher() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void publish(String[] cameraNames, Mat[] ready) {
        if (!ENABLED || cameraNames == null || ready == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSendMs < minIntervalMs) return;
        lastSendMs = now;

        long seq = SEQ.incrementAndGet();
        EXEC.execute(() -> {
            for (int i = 0; i < cameraNames.length && i < ready.length; i++) {
                if (!CAM_FILTER.isEmpty() && !CAM_FILTER.contains(cameraNames[i])) continue;
                Mat m = ready[i];
                if (m == null || m.empty()) continue;
                byte[] jpeg = encodeJpeg(m);
                if (jpeg.length == 0) continue;
                postFrame(cameraNames[i], seq, jpeg);
            }
        });
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

    private static byte[] encodeJpeg(Mat bgr) {
        MatOfByte buf = new MatOfByte();
        MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85);
        if (!Imgcodecs.imencode(".jpg", bgr, buf, params)) {
            return new byte[0];
        }
        return buf.toArray();
    }

    private static void postFrame(String camera, long seq, byte[] jpeg) {
        try {
            String boundary = "----wp5vision" + System.nanoTime();
            URL url = new URL(URL_PROP);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(200);
            conn.setReadTimeout(500);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            ByteArrayOutputStream body = new ByteArrayOutputStream(jpeg.length + 512);
            writePart(body, boundary, "camera", camera, null);
            writePart(body, boundary, "seq", Long.toString(seq), null);
            writePart(body, boundary, "image", null, "image/jpeg");
            body.write(jpeg);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            conn.setFixedLengthStreamingMode(body.size());
            try (OutputStream out = conn.getOutputStream()) {
                body.writeTo(out);
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignored) {
            // sidecar optional; never break the stitch loop
        }
    }

    private static void writePart(ByteArrayOutputStream body, String boundary,
                                  String name, String text, String contentType) throws IOException {
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        if (contentType == null) {
            body.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(text.getBytes(StandardCharsets.UTF_8));
        } else {
            body.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"frame.jpg\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    static {
        if (ENABLED) {
            System.out.println("[WP5 vision] frame tap -> " + URL_PROP
                    + "  max " + MAX_FPS + " fps  cameras=" + (CAM_FILTER.isEmpty() ? "all" : CAM_FILTER));
        }
    }
}
