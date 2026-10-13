# PR5 — Calibration, accuracy validation and generalization

PR5 makes the **metric path** measurable, testable, and safe. Overlay (PR4) and Java streaming stay unchanged.

## Modes (`vision/config.yaml` → `metric.mode`)

| Mode | Overlay | Metric ingest |
|------|---------|----------------|
| `disabled` | Yes | No |
| `development` | Yes | Only `SYNTHETIC` calibration when `allow_synthetic` / `allow_synthetic_calibration` |
| `calibrated` | Yes | Only `VALIDATED` measured profiles |

**Never** treat `SYNTHETIC` as field calibration. AI-generated footage remains overlay-only unless explicit dev flags are on.

## Calibration contract

Per-camera YAML (`config/calibration/`) supports:

- `camera_model` (type, intrinsics, distortion)
- `extrinsics` (`t_m` / `translation_vehicle_m`, `r_ypr_deg`)
- `ground_plane` (normal + offset)
- `calibration.status`: `UNVERIFIED` \| `SYNTHETIC` \| `VALIDATED`
- `distance_type`: `GROUND_PLANE_RANGE` \| `LATERAL` \| `LONGITUDINAL`

Implementation: `vision/calibration/schema.py`, `vision/calibration/validator.py`, extended `calibration_loader.py`.

Distance semantics: contour point = **curb-base heuristic** (`representative_pixel` in `curb_distance_estimator.py`) — provisional until field-validated.

## Measurement quality

`vision/metrics/quality.py` — flags only (no fabricated uncertainty unless `metric.report_uncertainty: true`).

`vision/metrics/distance_consistency.py` — distance jump / timestamp checks (separate from PR4 contour temporal filter).

## Accuracy harness

```bash
python3 tools/validate_curb_accuracy.py \
  --ground-truth config/calibration/validation/my_set.jsonl \
  --profile measured-vehicle-01
```

Report includes MAE, RMSE, bias, pass rate, valid-estimate rate (`vision/accuracy_validation.py`).

## Definition of done (checklist)

| Criterion | Status |
|-----------|--------|
| Calibration profiles versioned, validated, camera-specific | Done — `calibration.version`, validator, YAML contract |
| Ground-plane estimation has deterministic reference tests | Done — `test_synthetic_geometry.py`, `test_projection.py`, `test_ground_plane_projection.py` |
| Physical vs synthetic explicitly distinguished | Done — `CalibrationStatus`, ingest `calibrationType`, `metric.mode` |
| Invalid/stale estimates cannot become valid measurements | Done — quality gates + PR4 overlay stale + distance consistency |
| Java ingest / CurbModel / SSE compatible | Done — optional fields only; `source: VISION` unchanged |
| Viewer distinguishes detection vs metric validity | Done — overlay pill: SYNTHETIC METRIC / DISTANCE UNAVAILABLE / VALIDATED METRIC |
| Accuracy metrics from reproducible test data | Done — JSONL + `validate_curb_accuracy.py` |
| `/stitch` and `/meta` independent of sidecar | Unchanged (Java path) |
| PR1–PR4 regression tests | Run `python3 -m pytest vision/tests` + `mvn test` |

## Related paths

| Path | Role |
|------|------|
| `vision/metrics/measurement.py` | Build ingest + detection metric payload |
| `vision/service.py` | Wires PR5 metric pipeline |
| `config/calibration/validation/` | Ground-truth datasets |
| `tools/validate_curb_accuracy.py` | Offline accuracy gate |
