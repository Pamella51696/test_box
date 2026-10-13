# Vision curb sidecar (Python + ONNX / OpenVINO)

## Architecture

```text
VideoStreamingServer stitch loop
  undistorted Mat[] ready  ──JPEG POST──►  vision_sidecar (/frame)
                                                    │
                                                    ▼
                                            segmentation NN
                                                    │
                     POST /api/v1/ingest/range ◄────┘
                           (kind=CURB, source=VISION, pano{x,w})
                                                    │
                                                    ▼
                                            CurbModel → SSE /wp5/viewer
```

Frames are taken from the **same Java pipeline** that feeds `/stitch` (after undistort / rear pipeline), not a second camera open.

## Quick start

### 1. Model

```bash
cd tools/vision_sidecar
pip install -r requirements.txt
python download_model.py --preset adas-curb
```

Model choice: **`docs/VISION_CURB_MODELS.md`**.

### 2. Middleware (no fake_vehicle, no wp5.simulate)

```bash
export LD_LIBRARY_PATH=/usr/lib/jni:$LD_LIBRARY_PATH
java --enable-native-access=ALL-UNNAMED \
  -Dwp5.vision.url=http://127.0.0.1:8765/frame \
  -Dwp5.vision.maxFps=5 \
  -Dwp5.vision.cameras=left,right,rear \
  -Dvideo.dir=/workspace/src/main/java \
  -cp "/workspace/target/classes:/usr/share/java/opencv-460.jar" \
  com.middleware.panorama.VideoStreamingServer 9091
```

### 3. Sidecar

```bash
export MIDDLEWARE_URL=http://127.0.0.1:9091
export VISION_BACKEND=openvino   # or onnx + VISION_ONNX_PATH
python tools/vision_sidecar/server.py
```

### 4. View

Open `http://localhost:9091/wp5/viewer` — panorama overlay lines enabled (`PANO_ZONES=true`).

Phase 1 uses `VISION_PLACEHOLDER_DISTANCE_M=1.5` (green **SAFE** band) so you can validate **position** before metric ranging.

## Ingest extension (vision overlay)

Optional per-sensor panorama override (pixels in stitched space):

```json
{
  "sensors": [{
    "id": "vision_left",
    "pos": "LEFT_REAR",
    "kind": "CURB",
    "source": "VISION",
    "distanceM": 1.5,
    "panoX": 1700,
    "panoW": 120
  }]
}
```

When `pano` is present, `CurbModel` uses it instead of the fixed ultrasonic strip map.

## Phase 2 — metres

Replace `PLACEHOLDER_DISTANCE_M` in `server.py` with:

- IPM / homography from curb polyline to ground plane, or  
- monocular depth sampled at curb pixels, or  
- fusion with real ultrasonics: `distanceM = min(ultra, vision)`.

Same ingest API; Android contract unchanged.

## JVM flags

| Property | Default | Meaning |
|----------|---------|---------|
| `wp5.vision.url` | *(empty)* | Sidecar frame endpoint; empty = disabled |
| `wp5.vision.maxFps` | `5` | Max frames sent per second (all cameras) |
| `wp5.vision.cameras` | `left,right,rear` | Subset of `front,right,rear,left` |
