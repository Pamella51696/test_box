# PR1 calibration & geometry audit (prerequisite for PR2)

This audit records what the **current repo actually has** before PR2 metric distance work. It is the gate for “ground-plane projection” vs “blocked until extrinsics exist.”

## Executive summary

| Requirement for PR2                         | Status in repo today                                      |
| --------------------------------------------- | --------------------------------------------------------- |
| Per-camera intrinsics (real measured)         | **Missing** — synthetic FOV-based K only in Java          |
| Distortion model (real)                       | **Missing** — zeros (`FISHEYE_D = 0`)                     |
| Camera → vehicle extrinsics                   | **Missing** — no files, no API                            |
| Rectified image model matching NN input       | **Partial** — documented 640×360 after Java filters       |
| Camera identity on each detection             | **Yes** — frame tap sends `camera`, `width`, `height`     |
| Camera-pixel geometry preserved for NN output | **Gap** — PR1 contour often only in panorama space        |
| Vehicle frame (+x fwd, +y left, rear axle)    | **Yes** — WP5 steering / guide lines                      |
| Panorama layout                               | **Yes** — `/meta` 2320×360, four strips                   |
| Curb SSE / `CurbModel` ingest                 | **Yes** — `id` + `pos` + `source` + optional `panoX/W`    |
| Vision vs ultrasonic isolation                | **Yes** — sensors keyed by **`id`**, not `pos` only       |

**Conclusion:** PR2 can proceed with **software architecture and ingest integration** immediately, but **metric accuracy** depends on adding real calibration under `config/calibration/` (or measured extrinsics from the vehicle team). Until then, the estimator must **fail closed** (no fake `distanceM`).

---

## 1. What PR1 delivers today

### Java video path

- `VideoStreamingServer` stitch loop: read MP4 → **per-panel filter** → `ready[]` 640×360 → stitch → `/stitch`.
- **Side cameras:** `FisheyeUndistorter` — equidistant fisheye K from assumed **150°** input FOV, rectified to pinhole **90°** on 640×360, **D = 0**.
- **Rear panel:** `RearFeedPipeline` — fisheye + pitch/roll/crop (different geometry from sides).
- **Vision tap:** `VisionFrameTap` → JPEG + `camera`, `frameId`, `timestampMs`, `width`, `height` → Python `:8765/frame`.
- **Overlay API:** `POST/GET /api/v1/vision/detections` — does **not** modify `CurbModel`.

### Python vision path

- Input: **same JPEG** the driver would see from that camera strip (post-Java filter).
- Model: `road-segmentation-adas-0001` (4 classes incl. **curb**).
- Output: panorama contours via `coordinate_mapper.contour_camera_to_panorama` + `/meta`.
- Mode: `vision/config.yaml` → `mode: overlay_only` — **no** `/api/v1/ingest/range`.

### Viewer

- Polls `/api/v1/vision/detections` → **magenta** polylines.
- Curb SSE / ultrasonic zones are **separate** (“waiting for curb message” until ingest sends CURB sensors).

---

## 2. Calibration assets in the repository

### Present

| Asset | Location | Notes |
| ----- | -------- | ----- |
| Synthetic fisheye K | `VideoStreamingServer.FisheyeUndistorter` | `equidistantK(w,h,150°)`, `pinholeK(640,360,90°)` |
| Rear virtual pose | `RearFeedPipeline` | `LOOK_UP_DEG`, `ROLL_DEG`, crop — not exported to Python |
| Panorama geometry | `MetaBuilder` / `/meta` | Strip `x`, `w`, `source_w/h` 640×360 |
| Vehicle dimensions (WP5) | `Wp5Config` / profile | wheelbase, track, width — for **steering**, not cameras |
| GLB wheel pivots | `vehicle-profile` tooling | 3D model only |

### Absent

- `config/calibration/` per-camera YAML/JSON (no directory before PR2 design).
- OpenCV `camera_matrix` / `dist_coeffs` from checkerboard or OEM.
- **T_cam_vehicle** (4×4) or `(R, t)` per camera.
- Ground plane height per camera mount (only implicit z=0 in WP5 guides).
- Export of Java remap parameters to Python (maps rebuilt inside JVM only).

---

## 3. Coordinate systems (must stay consistent in PR2)

```
Raw MP4 pixel  →  [Java FisheyeUndistorter or RearFeedPipeline]  →  ready 640×360 BGR
                                                                      ↓
                                                              NN (vision sidecar)
                                                                      ↓
                    Camera pixels (640×360)  →  metric ray  →  vehicle frame
                    (PR2 estimator)              (needs calib)   rear axle: +x fwd, +y left
                                                                      ↓
                    Optional: panorama via /meta  →  overlay (PR1)
```

**Rule:** Metric projection must use the **640×360 rectified pinhole model**, not raw fisheye MP4 pixels and not panorama x/y alone.

**PR1 gap:** `detection_schema` should carry **`contourCameraPixels`** (and `frameWidth`/`frameHeight` per detection) alongside panorama contours. PR2 must not invert panorama → camera without seam handling.

---

## 4. CurbModel / ingest (PR2 integration points)

- Ingest: `POST /api/v1/ingest/range` with `{ id, pos, distanceM, kind, source }`.
- Sensors stored in `Map<String, Sensor>` by **`id`**.
- `fake_vehicle.py` uses `id == pos` (e.g. `LEFT_REAR`).
- Vision must use **distinct ids** (e.g. `VISION_LEFT_REAR`) → ultrasonic and vision can coexist; stale vision does **not** remove ultrasonic entry.
- `summary.leftWorst` / `rightWorst` take **worst across all sensor ids** — for PR2, document that two zones at same `pos` may both appear in `zones[]`; fusion policy is a follow-up (PR2 keeps streams distinguishable via `source` + `id`).
- Optional overlay: `panoX` / `panoW` on ingest (already in `CurbModel`) for vision-aligned strips.
- Stale: `wp5.staleMs` default **500 ms** — vision ingest must run ≥ ~10 Hz while active.

---

## 5. PR1 gaps to close before / during PR2 Task 1

| # | Gap | Action |
| - | --- | ------ |
| G1 | No real calibration files | Add `config/calibration/*.yaml` from vehicle measurement |
| G2 | Java K not exposed | Duplicate **documented** pinhole K for 640×360 90° in calibration YAML **or** add JVM export endpoint later |
| G3 | Camera pixels not in detection JSON | Extend `detection_schema` + `service.py` |
| G4 | Rear vs side geometry | Separate calibration entries: `left`, `right`, `rear`, `front` |
| G5 | NN confidence ≠ distance quality | PR2 schema: `distanceValid`, optional `distanceUncertaintyM` |
| G6 | No ground-truth dataset | Capture frames + tape measure for acceptance (PR2 Task 6) |

---

## 6. Recommended calibration capture (vehicle team)

For each camera role (`front`, `right`, `rear`, `left`):

1. Mounting position and orientation relative to **rear axle centre** (metres, degrees).
2. Checkerboard or OEM intrinsics on **rectified** 640×360 (or raw + full chain).
3. Height of lens centre above ground (m).
4. One photo dump from `-Dwp5.vision.url` path with `/meta` snapshot.

Place files in `config/calibration/<camera>.yaml` (see `config/calibration/README.md`).

---

## 7. Audit sign-off checklist

- [ ] Stakeholder accepts synthetic Java K is **not** production metric calibration.
- [ ] Extrinsics owner assigned (mechanical + CV).
- [ ] PR1 detection JSON includes camera-space contours (G3).
- [ ] Distinct vision sensor ids agreed (`VISION_*`).
- [ ] Physical distance tolerance target set after first ground-truth session (e.g. evaluate ±10 cm in 0.3–2.0 m).

When G1 and G3 are satisfied, PR2 implementation tasks 2–5 can run in parallel with calibration refinement.
