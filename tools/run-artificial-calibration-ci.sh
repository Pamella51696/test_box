#!/usr/bin/env bash
# CI/dev gate for artificial-measured profile (not field sign-off).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

GT="config/calibration/validation/ground_truth.artificial-measured.jsonl"
TH="config/calibration/validation/thresholds.artificial-measured.json"
PROFILE="artificial-measured"

if [[ ! -f "$GT" ]]; then
  python3 tools/generate_artificial_ground_truth.py --profile "$PROFILE" --output "$GT"
fi

python3 tools/validate_curb_accuracy.py \
  --profile "$PROFILE" \
  --ground-truth "$GT" \
  --allow-synthetic \
  --report /tmp/curb_accuracy_artificial.json \
  --min-pass-rate "$(python3 -c "import json; print(json.load(open('$TH'))['minPassRate'])")" \
  --max-mae-m "$(python3 -c "import json; print(json.load(open('$TH'))['maxMaeM'])")"

echo "Artificial calibration CI passed (development geometry only)."
