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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated publisher thread: sends latest frames from {@link VisionFrameTap} to the Python sidecar.
 * Never called from the stitch loop except {@link #signalNewFrame()}.
 */
public final class VisionFramePublisher {

    private static final String URL_PROP = System.getProperty("wp5.vision.url", "").trim();
    private static final boolean ENABLED = !URL_PROP.isEmpty();
    private static final Object WAKE = new Object();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private VisionFramePublisher() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    static void signalNewFrame() {
        if (!ENABLED) return;
        synchronized (WAKE) {
            WAKE.notify();
        }
    }

    static byte[] encodeJpeg(Mat bgr) {
        MatOfByte buf = new MatOfByte();
        MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85);
        if (!Imgcodecs.imencode(".jpg", bgr, buf, params)) {
            return new byte[0];
        }
        return buf.toArray();
    }

    static void startPublisher() {
        if (!ENABLED || !RUNNING.compareAndSet(false, true)) return;
        Thread t = new Thread(VisionFramePublisher::runLoop, "vision-frame-publisher");
        t.setDaemon(true);
        t.start();
    }

    private static void runLoop() {
        while (true) {
            Map<String, VisionFrameTap.FrameSlot> batch = VisionFrameTap.snapshotLatest();
            for (VisionFrameTap.FrameSlot slot : batch.values()) {
                postFrame(slot);
            }
            synchronized (WAKE) {
                try {
                    WAKE.wait(250);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static void postFrame(VisionFrameTap.FrameSlot slot) {
        try {
            String boundary = "----wp5vision" + System.nanoTime();
            URL url = new URL(URL_PROP);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(300);
            conn.setReadTimeout(800);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            ByteArrayOutputStream body = new ByteArrayOutputStream(slot.jpeg.length + 640);
            writePart(body, boundary, "camera", slot.camera, null);
            writePart(body, boundary, "frameId", Long.toString(slot.frameId), null);
            writePart(body, boundary, "timestampMs", Long.toString(slot.timestampMs), null);
            writePart(body, boundary, "width", Integer.toString(slot.width), null);
            writePart(body, boundary, "height", Integer.toString(slot.height), null);
            writePart(body, boundary, "image", null, "image/jpeg");
            body.write(slot.jpeg);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            conn.setFixedLengthStreamingMode(body.size());
            try (OutputStream out = conn.getOutputStream()) {
                body.writeTo(out);
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignored) {
            // sidecar optional
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
            System.out.println("[WP5 vision] frame tap → " + URL_PROP);
            startPublisher();
        }
    }
}
