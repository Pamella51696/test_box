# PR2 plan: NN curb detections → metric distances → existing CurbModel

Based on [PR1 audit](./PR1_CALIBRATION_AUDIT.md) and the working overlay pipeline.

## Objective

```
PR1:  camera frame → curb mask/contour → confidence → panorama overlay (unchanged)
PR2:  camera pixels + calibration → distanceM (valid/invalid) → POST ingest/range
Java: CurbModel (unchanged thresholds) → curb SSE → viewer / Android
```

**Principles**

- Do not retrain the NN in PR2 unless audit shows detection failure.
- Do not replace `CurbModel`; use `source: "VISION"`, `kind: "CURB"`, distinct `id`.
- Fail closed: invalid calibration or projection → **no** `distanceM` ingest.
- Keep PR1 `overlay_only` mode working side-by-side with `metric` mode.

---

## Architecture

```text
Java stitch loop
  VisionFrameTap (unchanged)
       ↓ JPEG + camera metadata
Python vision/service.py
  model_runner + postprocess          [PR1]
  curb_distance_estimator           [PR2 new]
  distance_validator                [PR2 new]
  calibration_loader                [PR2 new]
       ↓
  ┌─────────────────────────────────────┐
  │ mode=overlay_only → /api/v1/vision/detections (PR1)
  │ mode=metric       → /api/v1/ingest/range + optional detections enrich
  └─────────────────────────────────────┘
Java Wp5Service (existing ingest) → CurbModel → SSE
Viewer: magenta = vision API; red/yellow/green = curb SSE (may include VISION zones)
```

Optional later: `VisionRangeAdapter.java` only if you want Java-side validation; **not required** if Python is the sole publisher of vision range.

---

## Distance method (PR2 default)

**Method A — ground-plane ray intersection** (recommended first)

1. Pick representative curb point in **camera pixels** (see below).
2. Undistort if needed (for PR1 frames, use pinhole K of rectified 640×360).
3. Ray in camera frame → rotate to vehicle frame via extrinsics.
4. Intersect with plane `z = ground_plane_z_m`.
5. Compute scalar distance per product definition (e.g. lateral offset `|y|` or Euclidean in ground plane from reference point on vehicle).

Reject: `d_z ≈ 0`, λ < 0, point behind camera, distance outside `[min_valid, max_valid]`.

**Method B — learned depth:** out of scope unless Task 1 proves A unusable.

### Representative point policy (document in code)

For each `(camera, pos)` e.g. `left` → `LEFT_REAR`:

- Use **lowest row** of curb mask in bottom 40% of image (road contact heuristic), or
- Median of contour points in camera space.

Do not use nearest pixel to image centre without documentation.

---

## Data contracts

### Extended detection (overlay + metric metadata)

```json
{
  "frameId": 12840,
  "timestampMonotonicNs": 123456789000,
  "frameWidth": 2320,
  "frameHeight": 360,
  "detections": [{
    "class": "CURB",
    "camera": "left",
    "pos": "LEFT_REAR",
    "confidence": 0.82,
    "coordinateSpace": "PANORAMA_PIXELS",
    "contour": [[1710, 220], [1750, 205]],
    "contourCameraPixels": [[120, 300], [140, 290]],
    "imageWidth": 640,
    "imageHeight": 360,
    "distanceM": 0.75,
    "distanceValid": true,
    "distanceUncertaintyM": 0.08
  }]
}
```

`distanceM` appears only when `distanceValid` is true.

### Range ingest (feeds CurbModel)

```json
POST /api/v1/ingest/range
{
  "sensors": [{
    "id": "VISION_LEFT_REAR",
    "pos": "LEFT_REAR",
    "distanceM": 0.75,
    "kind": "CURB",
    "source": "VISION",
    "panoX": 1700,
    "panoW": 120
  }]
}
```

- Publish at ≥ 10 Hz while valid; stop sending on invalid (let stale → grey for **vision id only**).
- Do **not** send `distanceM: 0` for “no curb”; omit sensor or use clear policy documented in validator.

---

## File plan

### Python (`vision/`)

| File | PR2 role |
| ---- | -------- |
| `calibration_loader.py` | Load `config/calibration/*.yaml`, `valid` flag, pinhole K, extrinsics |
| `curb_distance_estimator.py` | Ray–plane intersection, distance scalar |
| `distance_validator.py` | Confidence floor, projection checks, range limits, age |
| `vision_ingest_client.py` | POST ingest/range with timeouts, no blocking Java |
| `detection_schema.py` | Add metric + camera-pixel fields |
| `coordinate_mapper.py` | Unchanged for overlay; estimator uses camera pixels |
| `service.py` | `mode: metric \| overlay_only \| both` |
| `config.yaml` | PR2 blocks from spec (calibration dir, distance, validation, ingest) |
| `tests/test_projection.py` | Synthetic ray ↔ known point |
| `tests/test_distance.py` | Ground-truth distances |
| `tests/test_calibration.py` | Missing/invalid calib → no publish |

### Java (`src/main/java/...`)

| File | PR2 role |
| ---- | -------- |
| `VideoStreamingServer.java` | **No change** required for metric path |
| `CurbModel.java` | **Minimal** — optional: ignore ingest if `distanceValid` flag added later; prefer validation in Python first |
| `VisionRangeAdapter.java` | **Optional** — ingest audit logging / rate limit |
| `Wp5Service.java` | **No change** if using existing ingest |

### Config

| Path | Role |
| ---- | ---- |
| `config/calibration/*.yaml` | Measured or dev-synthetic calibration |
| `docs/PR1_CALIBRATION_AUDIT.md` | Gate checklist |

---

## `vision/config.yaml` (PR2 additions)

```yaml
vision:
  mode: metric   # overlay_only | metric | both

  calibration:
    directory: "../config/calibration"
    coordinate_frame: rear_axle
    ground_plane_z_m: 0.0

  distance:
    method: ground_plane_projection
    definition: lateral_m   # document: |y| of ground hit vs Euclidean
    min_valid_distance_m: 0.05
    max_valid_distance_m: 5.0

  validation:
    min_detection_confidence: 0.60
    reject_invalid_projection: true
    max_detection_age_ms: 500

  ingest:
    enabled: true
    url: "http://127.0.0.1:9091/api/v1/ingest/range"
    publish_hz: 10
    sensor_id_prefix: "VISION_"
```

---

## Implementation tasks (review order)

### Task 1 — Calibration audit (done in doc; execute on vehicle)

- [ ] Fill `config/calibration/*.yaml` or mark `valid: false`.
- [ ] Export Java pinhole constants for dev-only YAML (optional script).
- [ ] Confirm NN input is 640×360 rectified (yes per audit).

### Task 2 — Geometry unit tests

- [ ] `curb_distance_estimator.py` with known C, d, plane → point P.
- [ ] Reject behind-camera and parallel-ray cases.

### Task 3 — Distance extraction

- [ ] Representative point from mask/contour in **camera** space.
- [ ] Map `camera` → `pos` (`left`→`LEFT_REAR` / `LEFT_FRONT` table).
- [ ] Attach `distanceUncertaintyM` (simple: function of pixel row + calib flag).

### Task 4 — Ingest integration

- [ ] `vision_ingest_client.py` with distinct ids.
- [ ] `mode=both`: overlay API + ingest.
- [ ] Rate limit / max age aligned with `wp5.staleMs`.

### Task 5 — Zone & UI validation

- [ ] Thresholds 0.5 / 1.0 m produce DANGER/WARNING/SAFE on vision-sourced zones.
- [ ] `panoX`/`panoW` align overlay with SSE zone strip.
- [ ] Viewer shows both magenta (detections) and classified zones.

### Task 6 — Failure & regression

- [ ] Kill Python → video + ultrasonic SSE OK; vision zones go stale.
- [ ] `fake_vehicle` + vision concurrent — ids do not overwrite.
- [ ] `tools/check_signals.py` unchanged pass without vision running.
- [ ] Physical tape-measure campaign → error stats doc.

---

## Acceptance tests (PR2 merge gate)

| Test | Criterion |
| ---- | --------- |
| Synthetic projection | Recover P within 1 mm in double precision unit tests |
| Invalid calib | `valid: false` → zero ingest with `distanceM` |
| Valid ingest | `source=VISION`, correct `pos`, `kind=CURB` |
| Thresholds | 0.35 m → DANGER, 0.8 m → WARNING, 1.5 m → SAFE |
| Stale vision | No update 600 ms → vision id grey; ultrasonic id unaffected |
| Sidecar crash | `/stitch` 200, check_signals pass without vision |
| Physical | Documented mean error vs tape (target TBD after Task 1) |

---

## Fusion (explicitly out of PR2 scope)

- Merging vision + ultrasonic into one `pos` reading.
- Changing `summary.worstZone` semantics when two ids share `pos`.

Document as PR3 if needed.

---

## Definition of done

- [ ] Trace: NN mask → camera pixel → calibrated ray → `distanceM`.
- [ ] Invalid geometry never produces plausible distances.
- [ ] Valid distances on existing curb SSE with `source: "VISION"`.
- [ ] PR1 overlay still works in `overlay_only` / `both`.
- [ ] Calibration and field-test report checked in.

**Critical dependency:** measured **extrinsics** (and preferably intrinsics). Undistortion-only Java math is insufficient for signed-off metric accuracy; use it only for dev harness with `valid: false` until measured.
