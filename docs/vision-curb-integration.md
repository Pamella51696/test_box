# Vision curb integration — PR 1 (overlay only)

## What PR 1 does

| Layer | Behavior |
|-------|----------|
| Java video | Unchanged `/stitch`, `/meta`, ultrasonic ingest, `CurbModel`, curb SSE |
| Frame tap | `VisionFrameTap` + `VisionFramePublisher` — bounded latest-frame, async HTTP to Python |
| Python `vision/` | Segmentation → panorama contours → `POST /api/v1/vision/detections` |
| Viewer | Polls vision API, draws **magenta** curb overlay (separate from distance zones) |

**No metric distance in PR 1.** No changes required to fake-vehicle or ultrasonic tests.

## Run (three terminals)

### 1. Middleware

**Easiest (Linux/macOS):**

```bash
chmod +x tools/run-middleware-with-vision.sh
./tools/run-middleware-with-vision.sh 9091
```

**Manual `java` line — rules that avoid your error:**

- Every `-D…` flag must come **before** the main class name `com.middleware.panorama.VideoStreamingServer`.
- Put the URL in **quotes** so `//` and `:` are not split by the shell.
- The main class is always **last** (before the port number `9091`).

```bash
export LD_LIBRARY_PATH=/usr/lib/jni:$LD_LIBRARY_PATH
java --enable-native-access=ALL-UNNAMED \
  "-Dwp5.vision.url=http://127.0.0.1:8765/frame" \
  -Dwp5.vision.maxFps=8 \
  -Dwp5.vision.cameras=left,right,rear \
  -cp "target/classes:/usr/share/java/opencv-460.jar" \
  com.middleware.panorama.VideoStreamingServer 9091
```

**PowerShell (Windows):** use `tools/run-middleware-with-vision.ps1` or quote the property:

```powershell
java --enable-native-access=ALL-UNNAMED "-Dwp5.vision.url=http://127.0.0.1:8765/frame" -Dwp5.vision.maxFps=8 -cp "target/classes;opencv.jar" com.middleware.panorama.VideoStreamingServer 9091
```

**Wrong (causes `ClassNotFoundException: …vision.url=…`):** putting `-Dwp5.vision.url=…` **after** the main class, or breaking the line so the next line starts with `.vision.url=…`.

Do **not** set `-Dwp5.simulate=true` unless you want the built-in simulator.

### 2. Vision sidecar

```bash
pip install -r vision/requirements.txt
python vision/download_model.py
export MIDDLEWARE_URL=http://127.0.0.1:9091
python vision/service.py
```

### 3. Viewer

Open `http://localhost:9091/wp5/viewer` and open `/stitch` (panorama img loads automatically).

Check:

- Header pill **vision: N det** (N > 0 when curbs visible in side/rear cameras)
- Magenta polyline on the panorama
- `GET http://localhost:9091/api/v1/vision/health`

## Model selection

See **`docs/VISION_CURB_MODELS.md`**. Default: **road-segmentation-adas-0001** (explicit curb class).

## Detection JSON

```json
{
  "frameId": 12840,
  "timestampMonotonicNs": 123456789000,
  "frameWidth": 2320,
  "frameHeight": 360,
  "coordinateSpace": "PANORAMA_PIXELS",
  "detections": [{
    "class": "CURB",
    "confidence": 0.94,
    "camera": "left",
    "coordinateSpace": "PANORAMA_PIXELS",
    "contour": [[1710, 220], [1750, 205]]
  }]
}
```

## Why you might see “waiting for curb message”

That text is **ultrasonic / fake-vehicle SSE**, not vision.

- With all distance sliders at **clear**, curb zones may be empty or grey.
- Vision overlay is independent — it needs **vision/service.py** and `-Dwp5.vision.url`.
- SSE shows `connecting` until the stream opens; steering/curb rates stay `0/s` until ingest sends data.

## PR 2 (metric distances)

See **`docs/PR1_CALIBRATION_AUDIT.md`** (gate) and **`docs/PR2_VISION_METRIC_PLAN.md`** (tasks, contracts, acceptance).

Calibration files: **`config/calibration/README.md`**.

### Synthetic-dev end-to-end (pipeline test)

```bash
./tools/run-pr2-metric-dev.sh 9091          # vision + middleware + /stitch consumer
python3 tools/verify_vision_metric_e2e.py --port 9091 --seconds 20
```

Uses `config/calibration/synthetic-dev/` with `VISION_ALLOW_SYNTHETIC=1` and `mode=both`. **Not** for real-world distance claims.

**Important:** `VisionFrameTap` only runs while a client holds **`/stitch`** open (viewer or `tools/stitch-consumer.sh`). Without that, the sidecar receives no frames.
