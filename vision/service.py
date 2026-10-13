#!/usr/bin/env python3
"""
PR-1 vision sidecar: overlay only.
- Receives frames from Java VisionFrameTap
- Runs segmentation (OpenVINO ADAS curb model by default)
- POSTs detection JSON to middleware /api/v1/vision/detections
Does NOT touch CurbModel or ultrasonic ingest.
"""
from __future__ import annotations

import json
import os
import threading
import time
import urllib.request
from pathlib import Path
from typing import Any, Dict, List

import cv2
import numpy as np
import uvicorn
import yaml
from fastapi import FastAPI, File, Form, UploadFile

from coordinate_mapper import contour_camera_to_panorama, fetch_meta
from detection_schema import Detection, envelope
from frame_receiver import FrameStore
from model_runner import ModelRunner

ROOT = Path(__file__).resolve().parent


def load_config() -> Dict[str, Any]:
    path = os.environ.get("VISION_CONFIG", str(ROOT / "config.yaml"))
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)["vision"]


CFG = load_config()
MIDDLEWARE = os.environ.get("MIDDLEWARE_URL", CFG.get("middleware_url", "http://127.0.0.1:9091")).rstrip("/")
HOST = os.environ.get("VISION_HOST", CFG["server"]["host"])
PORT = int(os.environ.get("VISION_PORT", CFG["server"]["port"]))
CONF_TH = float(CFG["inference"]["confidence_threshold"])
TARGET_HZ = float(CFG["inference"]["target_hz"])
MAX_AGE_MS = int(CFG["inference"]["max_frame_age_ms"])
PANO_W = int(CFG["output"]["width"])
PANO_H = int(CFG["output"]["height"])

frames = FrameStore(max_age_ms=MAX_AGE_MS)
meta: Dict[str, Any] = {}
meta_lock = threading.Lock()
runner: ModelRunner | None = None

app = FastAPI(title="WP5 vision overlay (PR1)")


def post_detections(body: dict) -> None:
    data = json.dumps(body).encode()
    req = urllib.request.Request(
        MIDDLEWARE + "/api/v1/vision/detections",
        data=data,
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    urllib.request.urlopen(req, timeout=2).read()


def refresh_meta() -> None:
    global meta
    try:
        m = fetch_meta(MIDDLEWARE)
        with meta_lock:
            meta = m
    except Exception as e:
        print("[vision] meta:", e)


def inference_loop() -> None:
    global runner
    while runner is None:
        time.sleep(0.1)
    interval = 1.0 / TARGET_HZ
    while True:
        time.sleep(interval)
        with meta_lock:
            m = meta
        if not m:
            continue
        batch = frames.snapshot_fresh()
        all_det: List[Detection] = []
        max_fid = 0
        for cam, pkt in batch.items():
            arr = np.frombuffer(pkt.jpeg, dtype=np.uint8)
            bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
            if bgr is None:
                continue
            det = runner.infer(bgr)
            if not det.found or det.confidence < CONF_TH:
                continue
            max_fid = max(max_fid, pkt.frame_id)
            pano_contour = contour_camera_to_panorama(m, cam, det.contour)
            if len(pano_contour) < 2:
                continue
            all_det.append(
                Detection(
                    camera=cam,
                    confidence=det.confidence,
                    coordinate_space="PANORAMA_PIXELS",
                    contour=pano_contour,
                    bbox=[],
                )
            )
        if not all_det:
            continue
        try:
            post_detections(
                envelope(max_fid, time.monotonic_ns(), PANO_W, PANO_H, all_det)
            )
        except Exception as e:
            print("[vision] post detections:", e)


@app.on_event("startup")
def startup() -> None:
    global runner
    refresh_meta()
    runner = ModelRunner(CFG.get("model", {}))
    threading.Thread(target=inference_loop, daemon=True).start()

    def meta_loop() -> None:
        while True:
            time.sleep(30)
            refresh_meta()

    threading.Thread(target=meta_loop, daemon=True).start()
    print(f"[vision] PR1 overlay  {HOST}:{PORT}  →  {MIDDLEWARE}/api/v1/vision/detections")


@app.post("/frame")
async def frame(
    camera: str = Form(...),
    frameId: int = Form(0),
    timestampMs: int = Form(0),
    width: int = Form(640),
    height: int = Form(360),
    image: UploadFile = File(...),
) -> dict:
    body = await image.read()
    frames.put(camera.strip().lower(), frameId, timestampMs, width, height, body)
    return {"ok": True, "camera": camera, "frameId": frameId}


@app.get("/health")
def health() -> dict:
    return {"ok": True, "middleware": MIDDLEWARE, "model": runner is not None}


if __name__ == "__main__":
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")
