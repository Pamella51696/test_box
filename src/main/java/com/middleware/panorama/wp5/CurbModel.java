package com.middleware.panorama.wp5;

import com.middleware.panorama.StitchingCore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WP-5 / Package 2 — curb zones (by default ONLY sensors with kind=CURB produce zones; -Dwp5.curbOnly=false = all sensors).
 *
 * Input : distance readings from ultrasonic / radar / lidar (per sensor).
 * Output: per-sensor RED / YELLOW / GREEN zone (with hysteresis, stale handling)
 *         + where that zone sits on the stitched panorama (pixel and 0..1 coordinates)
 *         + left/right/nearest summary for "context-aware emphasis" on Android.
 */
public final class CurbModel {

    /** Where each sensor position sits inside which camera strip: {cameraName, xFrom, xTo} (0..1). */
    static final Map<String, Object[]> DEFAULT_PANO_MAP = new LinkedHashMap<>();
    static {
        DEFAULT_PANO_MAP.put("FRONT_LEFT",   new Object[]{"front", 0.00, 0.34});
        DEFAULT_PANO_MAP.put("FRONT_CENTER", new Object[]{"front", 0.33, 0.67});
        DEFAULT_PANO_MAP.put("FRONT_RIGHT",  new Object[]{"front", 0.66, 1.00});
        // rear camera looks backwards -> vehicle-left appears on the image RIGHT
        DEFAULT_PANO_MAP.put("REAR_LEFT",    new Object[]{"rear",  0.66, 1.00});
        DEFAULT_PANO_MAP.put("REAR_CENTER",  new Object[]{"rear",  0.33, 0.67});
        DEFAULT_PANO_MAP.put("REAR_RIGHT",   new Object[]{"rear",  0.00, 0.34});
        // left camera looks out to the left -> vehicle-front appears on image RIGHT
        DEFAULT_PANO_MAP.put("LEFT_FRONT",   new Object[]{"left",  0.66, 1.00});
        DEFAULT_PANO_MAP.put("LEFT_REAR",    new Object[]{"left",  0.00, 0.34});
        // right camera looks out to the right -> vehicle-front appears on image LEFT
        DEFAULT_PANO_MAP.put("RIGHT_FRONT",  new Object[]{"right", 0.00, 0.34});
        DEFAULT_PANO_MAP.put("RIGHT_REAR",   new Object[]{"right", 0.66, 1.00});
    }

    private static final class Sensor {
        String id, pos, kind, source;
        double distanceM;       // <0 or >=maxRange => "clear"
        double heightM = Double.NaN;
        long receivedMs;
        Zone zone = Zone.NONE;  // remembered for hysteresis
        boolean panoOverride;
        int panoX, panoW;
    }

    /** Result of one evaluation. */
    public static final class CurbState {
        public final String json;
        public final String signature; // changes only when something visible changed
        public final Zone worst;
        CurbState(String json, String signature, Zone worst) {
            this.json = json; this.signature = signature; this.worst = worst;
        }
    }

    private final Wp5Config cfg;
    private final FusionConfig fusionCfg;
    private final String[] cameraNames;
    private final int camW, camH, overlap;
    private final Map<String, Sensor> sensors = new LinkedHashMap<>();
    private final Map<String, Object[]> panoMap = new LinkedHashMap<>(DEFAULT_PANO_MAP);
    private final CurbSourceState sourceState = new CurbSourceState();
    private final Map<String, Zone> fusedZoneMemory = new LinkedHashMap<>();
    private final java.util.Set<String> fusionPositions = new java.util.LinkedHashSet<>();
    private final VisionTestConfig visionTestCfg;
    private final java.util.Set<String> visionTestPositions = new java.util.LinkedHashSet<>();

    public CurbModel(Wp5Config cfg, String[] cameraNames, int camW, int camH, int overlapPx) {
        this(cfg, FusionConfig.load(), VisionTestConfig.load(), cameraNames, camW, camH, overlapPx);
    }

    CurbModel(Wp5Config cfg, FusionConfig fusionCfg, String[] cameraNames, int camW, int camH, int overlapPx) {
        this(cfg, fusionCfg, VisionTestConfig.load(), cameraNames, camW, camH, overlapPx);
    }

    CurbModel(Wp5Config cfg, FusionConfig fusionCfg, VisionTestConfig visionTestCfg,
              String[] cameraNames, int camW, int camH, int overlapPx) {
        this.cfg = cfg;
        this.fusionCfg = fusionCfg;
        this.visionTestCfg = visionTestCfg;
        this.cameraNames = cameraNames;
        this.camW = camW; this.camH = camH;
        this.overlap = StitchingCore.clampOverlap(overlapPx, camW);
        panoMap.putAll(cfg.profile.panoOverrides);   // profile may change/add sensor positions
    }

    // ------------------------------------------------------------------ input

    private static final Pattern OBJ = Pattern.compile("\\{[^{}]*\\}");

    /** Parses {"sensors":[{...},{...}]} and stores the latest readings. Returns #accepted. */
    public synchronized int ingestJson(String body, long nowMs) {
        int ok = 0;
        Matcher m = OBJ.matcher(body.substring(Math.max(0, body.indexOf('['))));
        while (m.find()) {
            String o = m.group();
            String pos = Json.getStr(o, "pos", "").toUpperCase(Locale.ROOT);
            if (!panoMap.containsKey(pos)) continue;       // unknown position -> ignore
            String id = Json.getStr(o, "id", pos);
            String kind = Json.getStr(o, "kind", "UNKNOWN").toUpperCase(Locale.ROOT);
            if (cfg.curbOnly && !"CURB".equals(kind)) {      // ZONES ARE BASED ON CURB SENSORS ONLY
                sensors.remove(id);                          // obstacle / unknown sensor: no zone, not in summary
                continue;
            }
            double d = Json.getNum(o, "distanceM", -1);
            String source = Json.getStr(o, "source", "ULTRASONIC").toUpperCase(Locale.ROOT);
            int ox = (int) Json.getNum(o, "panoX", -1);
            int ow = (int) Json.getNum(o, "panoW", -1);
            if (ox < 0) ox = panoField(o, "x", -1);
            if (ow < 0) ow = panoField(o, "w", -1);
            boolean panoOverride = ox >= 0 && ow > 0;

            if (fusionCfg.enabled) {
                CurbMeasurement cm = new CurbMeasurement(
                        id,
                        source,
                        pos,
                        kind,
                        d,
                        Json.getNum(o, "confidence", 1.0),
                        nowMs,
                        Json.getBool(o, "distanceValid", d >= 0 && d < cfg.maxRangeM),
                        "synthetic".equalsIgnoreCase(Json.getStr(o, "calibrationType", "")),
                        ox,
                        ow,
                        panoOverride
                );
                sourceState.put(cm);
                fusionPositions.add(pos);
                ok++;
                continue;
            }

            Sensor s = sensors.computeIfAbsent(id, k -> new Sensor());
            s.id = id; s.pos = pos;
            s.kind = kind;
            s.source = source;
            s.distanceM = d;
            s.heightM = Json.getNum(o, "heightM", Double.NaN);
            s.receivedMs = nowMs;
            if (panoOverride) {
                s.panoOverride = true;
                s.panoX = ox;
                s.panoW = ow;
            } else {
                s.panoOverride = false;
            }
            ok++;
        }
        return ok;
    }

    /**
     * PR6: browser / fake-vehicle curb-in-frame test input. Never overwrites VISION channel.
     */
    public synchronized int ingestVisionTest(String body, long nowMs) {
        if (!visionTestCfg.enabled) {
            throw new IllegalStateException("vision test ingest disabled");
        }
        java.util.List<CurbVisionTestIngest.SideUpdate> updates = CurbVisionTestIngest.parse(body, visionTestCfg);
        int ok = 0;
        for (CurbVisionTestIngest.SideUpdate u : updates) {
            String pos = CurbVisionTestIngest.SIDE_TO_POS.get(u.side);
            if (!panoMap.containsKey(pos)) continue;
            if (!u.detected) {
                sourceState.remove(pos, CurbMeasurement.CHANNEL_TEST_UI);
                visionTestPositions.remove(pos);
                ok++;
                continue;
            }
            CurbMeasurement cm = new CurbMeasurement(
                    "TEST_UI_" + pos,
                    CurbMeasurement.SOURCE_TEST_UI,
                    pos,
                    "CURB",
                    u.distanceM,
                    u.confidence,
                    nowMs,
                    true,
                    false,
                    -1,
                    -1,
                    false
            );
            sourceState.put(cm);
            visionTestPositions.add(pos);
            if (fusionCfg.enabled) fusionPositions.add(pos);
            ok++;
        }
        return ok;
    }

    private void purgeExpiredVisionTest(long nowMs) {
        if (!visionTestCfg.enabled) return;
        for (String pos : new java.util.ArrayList<>(visionTestPositions)) {
            CurbMeasurement t = sourceState.getTestUi(pos);
            if (!CurbHealthMonitor.isTestUiUsable(t, visionTestCfg, nowMs)) {
                sourceState.remove(pos, CurbMeasurement.CHANNEL_TEST_UI);
                visionTestPositions.remove(pos);
            }
        }
    }

    // ------------------------------------------------------------ classification

    /**
     * Pure function with hysteresis. Getting WORSE happens immediately at the threshold;
     * getting BETTER needs an extra hysteresisM so the colour does not flicker.
     */
    Zone classify(double d, Zone previous) {
        if (d < 0 || d >= cfg.maxRangeM) return Zone.SAFE;          // nothing in range
        Zone raw = d <= cfg.dangerM ? Zone.DANGER : (d <= cfg.warningM ? Zone.WARNING : Zone.SAFE);
        if (previous == Zone.NONE || raw.level >= previous.level) return raw;
        // improving: require margin beyond the threshold we are leaving
        double leaving = previous == Zone.DANGER ? cfg.dangerM : cfg.warningM;
        return d > leaving + cfg.hysteresisM ? raw : previous;
    }

    // ------------------------------------------------------------------ output

    public synchronized CurbState snapshot(long seq, long nowMs) {
        purgeExpiredVisionTest(nowMs);
        if (fusionCfg.enabled) {
            return snapshotFused(seq, nowMs);
        }
        int n = cameraNames.length;
        int panoWidth = StitchingCore.panoramaWidth(n, camW, overlap);

        StringBuilder sb = new StringBuilder(1024);
        StringBuilder sig = new StringBuilder();
        Zone worst = Zone.NONE, wLeft = Zone.NONE, wRight = Zone.NONE;
        double nearest = Double.MAX_VALUE; String nearestId = null;

        sb.append("{\"type\":\"curb\",\"v\":1,\"seq\":").append(seq).append(",\"ts\":").append(nowMs)
          .append(",\"panorama\":{\"width\":").append(panoWidth).append(",\"height\":").append(camH).append('}')
          .append(",\"thresholds\":{\"dangerM\":").append(Json.num(cfg.dangerM))
          .append(",\"warningM\":").append(Json.num(cfg.warningM)).append('}')
          .append(",\"zones\":[");

        java.util.Set<String> positions = new java.util.LinkedHashSet<>();
        java.util.Map<String, Sensor> byPos = new LinkedHashMap<>();
        for (Sensor s : sensors.values()) {
            positions.add(s.pos);
            byPos.put(s.pos, s);
        }
        positions.addAll(visionTestPositions);

        boolean first = true;
        for (String posKey : positions) {
            Sensor s = byPos.get(posKey);
            CurbMeasurement testUi = sourceState.getTestUi(posKey);
            boolean useTest = visionTestCfg.enabled && CurbHealthMonitor.isTestUiUsable(testUi, visionTestCfg, nowMs);
            if (s == null && !useTest) continue;

            boolean stale;
            Zone z;
            boolean clear;
            String outId, outSource, outKind;
            double distanceM;
            long receivedMs;
            boolean panoOverride;
            int stripX, stripW;
            double heightM = Double.NaN;

            if (useTest) {
                stale = false;
                distanceM = testUi.distanceM();
                z = classify(distanceM, Zone.NONE);
                clear = testUi.isClear(cfg.maxRangeM);
                outId = testUi.id();
                outSource = testUi.source();
                outKind = testUi.kind();
                receivedMs = testUi.receivedMs();
                panoOverride = false;
                stripX = 0;
                stripW = 0;
            } else {
                stale = nowMs - s.receivedMs > cfg.staleMs;
                z = stale ? Zone.NONE : classify(s.distanceM, s.zone);
                s.zone = z;
                clear = s.distanceM < 0 || s.distanceM >= cfg.maxRangeM;
                outId = s.id;
                outSource = s.source;
                outKind = s.kind;
                distanceM = s.distanceM;
                receivedMs = s.receivedMs;
                panoOverride = s.panoOverride;
                stripX = s.panoX;
                stripW = s.panoW;
                heightM = s.heightM;
            }

            Object[] map = panoMap.get(posKey);
            if (map == null) continue;
            int camIdx = indexOf((String) map[0]);
            int x = 0, w = 0;
            if (panoOverride) {
                x = stripX;
                w = Math.min(stripW, panoWidth - x);
            } else if (camIdx >= 0) {
                x = StitchingCore.cameraX(camIdx, camW, overlap) + (int) Math.round(camW * (double) map[1]);
                w = (int) Math.round(camW * ((double) map[2] - (double) map[1]));
                if (x + w > panoWidth) w = panoWidth - x;
            }

            if (!first) sb.append(','); first = false;
            sb.append("{\"id\":").append(Json.str(outId))
              .append(",\"pos\":").append(Json.str(posKey))
              .append(",\"kind\":").append(Json.str(outKind))
              .append(",\"source\":").append(Json.str(outSource))
              .append(",\"distanceM\":").append(clear || stale ? "null" : Json.num(distanceM))
              .append(",\"heightM\":").append(Double.isNaN(heightM) ? "null" : Json.num(heightM))
              .append(",\"zone\":").append(Json.str(z.name()))
              .append(",\"level\":").append(z.level)
              .append(",\"color\":").append(Json.str(cfg.color(z)))
              .append(",\"camera\":").append(Json.str((String) map[0]))
              .append(",\"pano\":{\"x\":").append(x).append(",\"w\":").append(w)
              .append(",\"xNorm\":").append(Json.num(panoWidth == 0 ? 0 : (double) x / panoWidth))
              .append(",\"wNorm\":").append(Json.num(panoWidth == 0 ? 0 : (double) w / panoWidth)).append("}}");

            sig.append(outId).append(':').append(z.level).append(':')
               .append(clear || stale ? "-" : Long.toString(Math.round(distanceM * 50))).append(';');

            worst = Zone.worst(worst, z);
            if (posKey.contains("LEFT"))  wLeft  = Zone.worst(wLeft, z);
            if (posKey.contains("RIGHT")) wRight = Zone.worst(wRight, z);
            if (!clear && !stale && distanceM < nearest) { nearest = distanceM; nearestId = outId; }
        }
        sb.append("],\"summary\":{\"worstZone\":").append(Json.str(worst.name()))
          .append(",\"worstColor\":").append(Json.str(cfg.color(worst)))
          .append(",\"nearestDistanceM\":").append(nearestId == null ? "null" : Json.num(nearest))
          .append(",\"nearestSensorId\":").append(nearestId == null ? "null" : Json.str(nearestId))
          .append(",\"leftWorst\":").append(Json.str(wLeft.name()))
          .append(",\"rightWorst\":").append(Json.str(wRight.name()))
          .append("}}");
        return new CurbState(sb.toString(), sig.toString(), worst);
    }

    private int indexOf(String cam) {
        for (int i = 0; i < cameraNames.length; i++) if (cameraNames[i].equals(cam)) return i;
        return -1;
    }

    public synchronized boolean hasSensors() {
        if (!visionTestPositions.isEmpty()) return true;
        return fusionCfg.enabled ? !fusionPositions.isEmpty() : !sensors.isEmpty();
    }

    private CurbState snapshotFused(long seq, long nowMs) {
        int n = cameraNames.length;
        int panoW = StitchingCore.panoramaWidth(n, camW, overlap);
        StringBuilder sb = new StringBuilder(2048);
        StringBuilder sig = new StringBuilder();
        Zone worst = Zone.NONE, wLeft = Zone.NONE, wRight = Zone.NONE;
        double nearest = Double.MAX_VALUE;
        String nearestId = null;

        sb.append("{\"type\":\"curb\",\"v\":1,\"seq\":").append(seq).append(",\"ts\":").append(nowMs)
          .append(",\"panorama\":{\"width\":").append(panoW).append(",\"height\":").append(camH).append('}')
          .append(",\"thresholds\":{\"dangerM\":").append(Json.num(cfg.dangerM))
          .append(",\"warningM\":").append(Json.num(cfg.warningM)).append('}')
          .append(",\"fusion\":{\"enabled\":true,\"mode\":").append(Json.str(fusionCfg.mode.name().toLowerCase(Locale.ROOT)))
          .append("},\"zones\":[");

        java.util.Set<String> positions = new java.util.LinkedHashSet<>(fusionPositions);
        positions.addAll(visionTestPositions);

        boolean first = true;
        for (String pos : positions) {
            CurbMeasurement vision = sourceState.get(pos, CurbMeasurement.CHANNEL_VISION);
            CurbMeasurement ultrasonic = sourceState.get(pos, CurbMeasurement.CHANNEL_ULTRASONIC);
            CurbMeasurement testUi = sourceState.getTestUi(pos);
            CurbFusionEngine.FusionPick pick = CurbFusionEngine.fuse(
                    pos, vision, ultrasonic, testUi, fusionCfg, cfg, visionTestCfg, nowMs);
            CurbMeasurement sel = pick.selected;
            Zone prev = fusedZoneMemory.getOrDefault(pos, Zone.NONE);
            boolean stale = sel == null;
            Zone z = stale ? Zone.NONE : classify(sel.distanceM(), prev);
            fusedZoneMemory.put(pos, z);

            boolean clear = stale || sel.isClear(cfg.maxRangeM);
            Object[] map = panoMap.get(pos);
            int camIdx = indexOf((String) map[0]);
            int x = 0, w = 0;
            if (sel != null && sel.panoOverride()) {
                x = sel.panoX();
                w = Math.min(sel.panoW(), panoW - x);
            } else if (camIdx >= 0) {
                x = StitchingCore.cameraX(camIdx, camW, overlap) + (int) Math.round(camW * (double) map[1]);
                w = (int) Math.round(camW * ((double) map[2] - (double) map[1]));
                if (x + w > panoW) w = panoW - x;
            }

            String outId = sel != null ? sel.id() : (vision != null ? vision.id() : ultrasonic != null ? ultrasonic.id() : pos);
            String outSource = sel != null ? sel.source() : "NONE";
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(Json.str(outId))
              .append(",\"pos\":").append(Json.str(pos))
              .append(",\"kind\":\"CURB\"")
              .append(",\"source\":").append(Json.str(outSource))
              .append(",\"distanceM\":").append(clear || stale ? "null" : Json.num(sel.distanceM()))
              .append(",\"heightM\":null")
              .append(",\"zone\":").append(Json.str(z.name()))
              .append(",\"level\":").append(z.level)
              .append(",\"color\":").append(Json.str(cfg.color(z)))
              .append(",\"camera\":").append(Json.str((String) map[0]))
              .append(",\"pano\":{\"x\":").append(x).append(",\"w\":").append(w)
              .append(",\"xNorm\":").append(Json.num(panoW == 0 ? 0 : (double) x / panoW))
              .append(",\"wNorm\":").append(Json.num(panoW == 0 ? 0 : (double) w / panoW))
              .append("},\"fusion\":{\"mode\":").append(Json.str(pick.mode))
              .append(",\"selectedSource\":").append(Json.str(sel != null ? sel.source() : "NONE"))
              .append(",\"conflict\":").append(pick.conflict).append("}}");

            sig.append(pos).append(':').append(z.level).append(':')
               .append(clear || stale ? "-" : Long.toString(Math.round(sel.distanceM() * 50))).append(';');

            worst = Zone.worst(worst, z);
            if (pos.contains("LEFT")) wLeft = Zone.worst(wLeft, z);
            if (pos.contains("RIGHT")) wRight = Zone.worst(wRight, z);
            if (!clear && !stale && sel.distanceM() < nearest) {
                nearest = sel.distanceM();
                nearestId = sel.id();
            }
        }
        sb.append("],\"summary\":{\"worstZone\":").append(Json.str(worst.name()))
          .append(",\"worstColor\":").append(Json.str(cfg.color(worst)))
          .append(",\"nearestDistanceM\":").append(nearestId == null ? "null" : Json.num(nearest))
          .append(",\"nearestSensorId\":").append(nearestId == null ? "null" : Json.str(nearestId))
          .append(",\"leftWorst\":").append(Json.str(wLeft.name()))
          .append(",\"rightWorst\":").append(Json.str(wRight.name()))
          .append("}}");
        return new CurbState(sb.toString(), sig.toString(), worst);
    }

    /** Reads integer field inside a nested "pano":{...} object on one sensor JSON blob. */
    private static int panoField(String sensorJson, String key, int def) {
        int i = sensorJson.indexOf("\"pano\"");
        if (i < 0) return def;
        int end = sensorJson.indexOf('}', i);
        if (end < 0) return def;
        String block = sensorJson.substring(i, end + 1);
        return (int) Json.getNum(block, key, def);
    }
}
