#!/usr/bin/env bash
# Start vision sidecar with artificial-measured profile (dev metric ingest, not field accuracy).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export VISION_CALIBRATION_PROFILE=artificial-measured
export VISION_ALLOW_SYNTHETIC=1
export VISION_MODE=both
export MIDDLEWARE_URL="${MIDDLEWARE_URL:-http://127.0.0.1:9091}"
cd "$ROOT/vision"
echo "[artificial-metric-dev] profile=$VISION_CALIBRATION_PROFILE mode=$VISION_MODE middleware=$MIDDLEWARE_URL"
exec python3 -m uvicorn service:app --host 127.0.0.1 --port "${VISION_PORT:-8765}"
