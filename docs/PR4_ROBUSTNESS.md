# PR4 — Curb detection robustness

Python vision sidecar improvements (Java `/stitch` and `/meta` unchanged):

| Module | Role |
|--------|------|
| `vision/frame_adapter.py` | JPEG decode, layout from `/meta`, contour projection (rejects unknown cameras) |
| `vision/curb_detector.py` | ONNX inference + `contour_refine` |
| `vision/temporal_filter.py` | Per-camera smoothing, stability, stale / hold-on-miss |
| `vision/detection_schema.py` | `detectionValid`, `overlayStatus`, `calibrationStatus` |
| `vision/service.py` | Wires adapter + detector + temporal; bounded latest-frame store; `/health` |

## Configuration (`vision/config.yaml`)

- `input.*` — panorama vs single-camera behaviour  
- `detection.*` — confidence, contour area, `max_inference_fps` cap  
- `temporal.*` — smoothing and stale timeout  
- `distance.require_valid_calibration` — metric ingest fail-closed  

## Viewer

Header pill **overlay:** shows `DETECTED`, `NO CURB`, `STALE`, `INFERENCE ERROR`, or `DISTANCE UNAVAILABLE` (contour without valid metres).

## Tests

```bash
cd vision && python -m pytest tests/ -q
```
