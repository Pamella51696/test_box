#!/usr/bin/env bash
# Middleware + Java frame tap for vision/service.py (PR1 overlay)
set -euo pipefail
PORT="${1:-9091}"
VISION_URL="${VISION_FRAME_URL:-http://127.0.0.1:8765/frame}"
VIDEO_DIR="${VIDEO_DIR:-$(cd "$(dirname "$0")/../src/main/java" && pwd)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:-}:/usr/lib/jni"

OPENCV_JAR="${OPENCV_JAR:-/usr/share/java/opencv-460.jar}"
if [[ ! -f "$OPENCV_JAR" ]]; then
  OPENCV_JAR="$(find "$HOME/.m2/repository/org/openpnp/opencv" -name 'opencv-*.jar' 2>/dev/null | head -1)"
fi
if [[ ! -f "$OPENCV_JAR" ]]; then
  echo "OpenCV jar not found. Set OPENCV_JAR or install libopencv-java." >&2
  exit 1
fi

cd "$ROOT"
[[ -d target/classes ]] || mvn -q compile

echo "Port: $PORT  vision tap: $VISION_URL  videos: $VIDEO_DIR"
exec java --enable-native-access=ALL-UNNAMED \
  "-Dwp5.vision.url=${VISION_URL}" \
  -Dwp5.vision.maxFps=8 \
  -Dwp5.vision.cameras=left,front,right,rear \
  -Dpanels=3:left,1:front,4:right,2:rear \
  "-Dvideo.dir=${VIDEO_DIR}" \
  -cp "target/classes:${OPENCV_JAR}" \
  com.middleware.panorama.VideoStreamingServer "$PORT"
