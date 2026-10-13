package com.middleware.panorama.vision;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** HTTP endpoints for PR-1 vision overlay (does not touch CurbModel). */
public final class VisionService {

    private VisionService() {}

    public static void attach(HttpServer server) {
        server.createContext("/api/v1/vision/detections", new DetectionsHandler());
        server.createContext("/api/v1/vision/health", new HealthHandler());
        System.out.println("[WP5 vision] overlay API →  GET/POST /api/v1/vision/detections");
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private static final class DetectionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if ("GET".equalsIgnoreCase(method)) {
                String j = VisionDetectionHub.latest();
                if (j == null || j.isBlank()) {
                    send(ex, 204, "application/json", "");
                    return;
                }
                send(ex, 200, "application/json; charset=UTF-8", j);
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                byte[] raw = ex.getRequestBody().readNBytes(512 * 1024);
                String body = new String(raw, StandardCharsets.UTF_8).trim();
                if (body.isEmpty() || !body.startsWith("{")) {
                    send(ex, 400, "application/json", "{\"error\":\"JSON object required\"}");
                    return;
                }
                VisionDetectionHub.publish(body);
                send(ex, 200, "application/json", "{\"ok\":true}");
                return;
            }
            ex.sendResponseHeaders(405, -1);
        }
    }

    private static final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            long age = VisionDetectionHub.ageMs();
            boolean stale = age > 2000;
            String json = "{\"ok\":true,\"lastDetectionAgeMs\":" + (age == Long.MAX_VALUE ? -1 : age)
                    + ",\"frameId\":" + VisionDetectionHub.frameId()
                    + ",\"frameTap\":" + VisionFrameTap.isEnabled()
                    + ",\"stale\":" + stale + "}";
            send(ex, 200, "application/json; charset=UTF-8", json);
        }
    }
}
