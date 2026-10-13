# Ground truth for curb distance validation (PR5)

Use this directory for **field or lab footage** where both of the following are known:

1. **Camera geometry** — measured `config/calibration/<profile>/*.yaml` with `valid: true`
2. **Curb distance** — tape measure, survey, or fiducial at a defined reference (document which reference in `notes`)

Do **not** use AI-generated panorama footage for accuracy sign-off. Synthetic calibration (`synthetic-dev`) is only for pipeline self-tests (`--allow-synthetic`).

## JSONL format (one object per line)

| Field | Required | Description |
|-------|----------|-------------|
| `id` | yes | Stable sample name |
| `camera` | yes | `front` \| `left` \| `right` \| `rear` |
| `contour_camera_pixels` | yes | `[[x,y], ...]` in **640×360 rectified** pixels (same as Java stitch input) |
| `distance_m` | yes | Measured distance in metres (same definition as `vehicle_frame.yaml` / lateral body edge) |
| `image_height` | no | Default `360` |
| `tolerance_m` | no | Per-sample acceptance band (default `0.15`) |
| `notes` | no | Scene, weather, reference point |

Example line:

```json
{"id":"parking-lot-01-left","camera":"left","contour_camera_pixels":[[120,310],[200,295],[280,288]],"distance_m":0.72,"tolerance_m":0.12,"notes":"tape at curb lip, dry asphalt"}
```

## Capture workflow

1. Record synchronized video with the production camera rig (or export stills at known timestamps).
2. Export rectified frames from Java (or save from frame tap) — **not** raw fisheye MP4 pixels.
3. Mark the curb polyline in camera pixels (label tool or manual JSON).
4. Measure ground truth at the same curb segment; record uncertainty in `tolerance_m`.
5. Run:

```bash
python3 tools/validate_curb_accuracy.py \
  --ground-truth config/calibration/validation/my_dataset.jsonl \
  --profile measured-vehicle-01 \
  --min-pass-rate 0.9 \
  --max-mae-m 0.20 \
  --report /tmp/curb_accuracy_report.json
```

6. Tune extrinsics/intrinsics only with independent hold-out clips (avoid fitting on the same frames you measure).

## Files

| File | Purpose |
|------|---------|
| `ground_truth.example.jsonl` | Schema reference (not for pass/fail) |
| `ground_truth.synthetic_selftest.jsonl` | CI self-test with `synthetic-dev` + `--allow-synthetic` |
