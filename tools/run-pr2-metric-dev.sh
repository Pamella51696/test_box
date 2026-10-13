#!/usr/bin/env bash
# PR2 dev: middleware + vision sidecar with synthetic-dev calibration (not for field use).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${1:-9091}"
VIDEO_DIR="${VIDEO_DIR:-$ROOT/artificial-feed}"
export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:-}:/usr/lib/jni"

export VISION_MODE=both
export VISION_CALIBRATION_PROFILE=synthetic-dev
export VISION_ALLOW_SYNTHETIC=1
export MIDDLEWARE_URL="http://127.0.0.1:${PORT}"

OPENCV_JAR="${OPENCV_JAR:-/usr/share/java/opencv-460.jar}"
cd "$ROOT"
[[ -d target/classes ]] || mvn -q compile

echo "Starting vision sidecar (metric+both, synthetic-dev)..."
SESSION="pr2-metric-dev"
tmux -f /exec-daemon/tmux.portal.conf has-session -t "=$SESSION" 2>/dev/null && tmux -f /exec-daemon/tmux.portal.conf kill-session -t "$SESSION"
tmux -f /exec-daemon/tmux.portal.conf new-session -d -s "$SESSION" -c "$ROOT/vision" -- "${SHELL:-bash}" -l
tmux -f /exec-daemon/tmux.portal.conf send-keys -t "$SESSION:0.0" "export VISION_MODE=both VISION_CALIBRATION_PROFILE=synthetic-dev VISION_ALLOW_SYNTHETIC=1 MIDDLEWARE_URL=http://127.0.0.1:${PORT}; python3 service.py" C-m
tmux -f /exec-daemon/tmux.portal.conf new-window -t "$SESSION" -c "$ROOT" -- "${SHELL:-bash}" -l
sleep 2
tmux -f /exec-daemon/tmux.portal.conf new-window -t "$SESSION" -c "$ROOT" -- "${SHELL:-bash}" -l
tmux -f /exec-daemon/tmux.portal.conf send-keys -t "$SESSION:1.0" "exec java --enable-native-access=ALL-UNNAMED \
  -Dwp5.vision.url=http://127.0.0.1:8765/frame \
  -Dwp5.vision.maxFps=8 \
  -Dwp5.vision.cameras=left,front,right,rear \
  -Dpanels=3:left,1:front,4:right,2:rear \
  -Dvideo.dir=${VIDEO_DIR} \
  -cp target/classes:${OPENCV_JAR} \
  com.middleware.panorama.VideoStreamingServer ${PORT}" C-m
sleep 3
tmux -f /exec-daemon/tmux.portal.conf send-keys -t "$SESSION:2.0" "$ROOT/tools/stitch-consumer.sh ${PORT}" C-m

echo "Stack starting on port ${PORT} (tmux session: ${SESSION})"
echo "  Viewer: http://127.0.0.1:${PORT}/wp5/viewer.html"
echo "  Verify: python3 tools/verify_vision_metric_e2e.py --port ${PORT} --seconds 25"
