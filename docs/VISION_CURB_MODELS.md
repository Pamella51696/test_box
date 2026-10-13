# Choosing a vision / NN model for curb overlay

This middleware expects **distance + optional panorama pixel box** via `POST /api/v1/ingest/range`. The Python sidecar turns a **segmentation model** into that JSON. You do **not** need a model that outputs metres on day one — overlay first, ranging second.

## Recommended default (this repo)

| | |
|---|---|
| **Model** | [road-segmentation-adas-0001](https://docs.openvino.ai/2023.3/omz_models_model_road_segmentation_adas_0001.html) (Intel Open Model Zoo) |
| **Why** | Small (~0.18 M params), built for **bumper / ADAS** cameras, **four classes: background, road, curb, mark** — includes an explicit **curb** channel (not just “sidewalk”). |
| **Input** | BGR `1×3×512×896` |
| **Output** | Class logits `1×4×512×896` |
| **Runtime** | OpenVINO (`VISION_BACKEND=openvino`, default) or export to ONNX (`VISION_BACKEND=onnx`) |
| **Download** | `python tools/vision_sidecar/download_model.py --preset adas-curb` |

**When to use:** Parking / low-speed surround views similar to your stitched clips; quick overlay prototype on CPU.

**Limits:** Trained on forward ADAS views — side fisheye may need retraining or fine-tuning; metric distance is **not** native (phase 2: homography / stereo).

---

## Alternatives (when to switch)

### Cityscapes semantic segmentation (DeepLabV3 / SegFormer / LR-ASPP)

| | |
|---|---|
| **Classes** | Road + sidewalk (no dedicated “curb” — use **boundary between road and sidewalk** or train a custom head) |
| **Why** | Large ecosystem; many ONNX exports on Hugging Face |
| **Use when** | You need higher-quality urban scenes and can accept post-processing (edge of sidewalk class) |
| **Size** | MobileNet-LR-ASPP: fast; SegFormer-B0: better, heavier |

Export example (local): torchvision `deeplabv3_mobilenet_v3_large` weights `COCO_WITH_VOC_LABELS_V1` — **not** Cityscapes out of the box; for production, export a **Cityscapes-finetuned** checkpoint to ONNX and set `VISION_ONNX_PATH`.

### YOLOv8-seg / YOLO11-seg (custom dataset)

| | |
|---|---|
| **Why** | You label “curb” as instances in your own fisheye data |
| **Use when** | ADAS model misses curbs on your mounts; you can invest in labeling |
| **Output** | Masks → bbox → same sidecar `pano` mapping |

### Monocular depth (Depth Anything, ZoeDepth, etc.)

| | |
|---|---|
| **Why** | Direct **distance in metres** (phase 2) from a single camera |
| **Use when** | Overlay works and you need metric ranging without ultrasonics |
| **Caveat** | Scale ambiguity — combine with camera height + ground plane or sparse LiDAR calibration |

---

## Decision checklist

1. **Need a dedicated “curb” class in the mask?** → Start with **road-segmentation-adas-0001**.
2. **Side cameras / fisheye only?** → Plan fine-tune or YOLO-seg on your frames from `-Dwp5.vision.url` recordings.
3. **CPU-only in car?** → ADAS model or MobileNet segmentation; avoid huge SegFormer on CPU at 30 FPS.
4. **Metres matter soon?** → Keep overlay model; add depth / IPM module in sidecar; still POST `distanceM` to the same API.

---

## ONNX vs OpenVINO in the sidecar

| Backend | Env | Model file |
|---------|-----|------------|
| OpenVINO (default) | `VISION_BACKEND=openvino` | `tools/vision_sidecar/models/road-segmentation-adas-0001.xml` + `.bin` |
| ONNX | `VISION_BACKEND=onnx` | `VISION_ONNX_PATH=.../curb_segmentation.onnx` |

To convert ADAS IR → ONNX (one-time, on a dev machine with OpenVINO installed):

```bash
pip install openvino onnx
ovc road-segmentation-adas-0001.xml --output_model curb_segmentation.onnx
```

(Exact CLI may vary slightly by OpenVINO version; see OpenVINO “convert model to ONNX” docs.)

---

## What we are *not* using (and why)

| Approach | Reason |
|----------|--------|
| Generic COCO object detection | COCO has no “curb” class |
| Pure edge/Canny in production | OK for pipeline bring-up only; not robust to texture/lighting |
| Full BEV map in middleware | Out of scope — keep BEV in sidecar, send ingest JSON |

See `docs/VISION_INTEGRATION.md` for wiring Java frames → sidecar → `/api/v1/ingest/range`.
