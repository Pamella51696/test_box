# Artificial “measured” calibration (CI / dev only)

**Not survey or OEM data.** This profile lets you exercise the PR5 workflow (ground-truth JSONL, `validate_curb_accuracy.py`, metric ingest) without a real vehicle.

| Claim | Allowed? |
|-------|----------|
| Magenta overlay on AI video | Yes (unchanged) |
| Physical distance accuracy | **No** — geometry is made up |
| `calibration.status: VALIDATED` | **No** — stays `SYNTHETIC` |
| `metric.mode: calibrated` | **No** — use `development` + `allow_synthetic` |

## Enable in vision sidecar

```yaml
# vision/config.yaml
calibration:
  profile: artificial-measured
  allow_synthetic: true
metric:
  mode: development
mode: both   # optional metric ingest for pipeline test
```

Or environment:

```bash
export VISION_CALIBRATION_PROFILE=artificial-measured
export VISION_ALLOW_SYNTHETIC=1
export VISION_MODE=both
```

## Regenerate ground truth

```bash
python3 tools/generate_artificial_ground_truth.py \
  --profile artificial-measured \
  --output config/calibration/validation/ground_truth.artificial-measured.jsonl
```

## Run accuracy gate (CI)

```bash
tools/run-artificial-calibration-ci.sh
```

Thresholds live in `config/calibration/validation/thresholds.artificial-measured.json` (derived from this artificial geometry, not from field tape measure).

When you have real data, copy this folder layout to `config/calibration/<your-vehicle>/`, replace numbers from survey, build a new JSONL, re-run the validator, and only then set `status: VALIDATED` and `metric.mode: calibrated`.
