# Legacy path (superseded)

**Use `vision/service.py` for PR-1 overlay** — see `docs/vision-curb-integration.md`.

This folder remains for reference; the active sidecar is at repo root `vision/`.

**Model choice:** `docs/VISION_CURB_MODELS.md`  
**Wiring:** `docs/VISION_INTEGRATION.md`

```bash
pip install -r requirements.txt
python download_model.py
export MIDDLEWARE_URL=http://127.0.0.1:9091
python server.py
```

Java (same machine):

```text
-Dwp5.vision.url=http://127.0.0.1:8765/frame
-Dwp5.vision.maxFps=5
-Dwp5.vision.cameras=left,right,rear
```

Do **not** run `fake_vehicle.py` or `-Dwp5.simulate=true` while testing vision unless you intend to fuse sources.
