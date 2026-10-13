#!/usr/bin/env python3
"""
Vision sidecar: receives JPEG frames from Java (-Dwp5.vision.url=...), runs segmentation,
POSTs curb overlays to middleware /api/v1/ingest/range with pano {x,w}.

Phase 1: overlay (distanceM placeholder). Phase 2: replace with metric ranging.

  export MIDDLEWARE_URL=http://127.0.0.1:9092
  python download_model.py
  python server.py
"""
from __future__ import annotations

import json
import os
import threading
import time
import urllib.request
from typing import Any, Dict, Optional

import uvicorn
from fastapi import FastAPI, File, Form, UploadFile

from infer import CurbDetector
from pano_map import CAMERA_TO_POS, camera_to_pano, fetch_meta

MIDDLEWARE = os.environ.get("MIDDLEWARE_URL", "http://127.0.0.1:9091").rstrip("/")
HOST = os.environ.get("VISION_HOST", "127.0.0.1")
PORT = int(os.environ.get("VISION_PORT", "8765"))
# Placeholder distance until metric ranging (phase 2). 1.5 m => SAFE (green overlay).
PLACEHOLDER_DISTANCE_M = float(os.environ.get("VISION_PLACEHOLDER_DISTANCE_M", "1.5"))
MIN_CONF = float(os.environ.get("VISION_MIN_CONF", "0.001"))
INGEST_HZ = float(os.environ.get("VISION_INGEST_HZ", "8"))

app = FastAPI(title="WP5 vision curb sidecar")
detector: Optional[CurbDetector] = None
meta: Optional[Dict[str, Any]] = None
meta_lock = threading.Lock()
last_frames: Dict[str, bytes] = {}
frame_lock = threading.Lock()


def post_ingest(payload: dict) -> None:
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        MIDDLEWARE + "/api/v1/ingest/range",
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
        print("[vision] meta fetch failed:", e)


def ingest_loop() -> None:
    import cv2
    import numpy as np

    while True:
        time.sleep(1.0 / INGEST_HZ)
        if detector is None:
            continue
        with meta_lock:
            m = meta
        if m is None:
            continue
        sensors = []
        with frame_lock:
            items = list(last_frames.items())
        for camera, jpeg in items:
            arr = np.frombuffer(jpeg, dtype=np.uint8)
            bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
            if bgr is None:
                continue
            det = detector.detect(bgr)
            pos = CAMERA_TO_POS.get(camera)
            if not pos:
                continue
            sid = "vision_" + camera
            if not det.found or det.confidence < MIN_CONF:
                continue  # omit → prior reading goes stale (grey) after wp5.staleMs
            mapped = camera_to_pano(m, camera, det.x0, det.y0, det.x1, det.y1)
            if not mapped:
                continue
            pano_x, pano_w, _ = mapped
            sensors.append(
                {
                    "id": sid,
                    "pos": pos,
                    "distanceM": PLACEHOLDER_DISTANCE_M,
                    "kind": "CURB",
                    "source": "VISION",
                    "panoX": pano_x,
                    "panoW": pano_w,
                }
            )
        if sensors:
            try:
                post_ingest({"sensors": sensors})
            except Exception as e:
                print("[vision] ingest failed:", e)


@app.on_event("startup")
def startup() -> None:
    global detector
    refresh_meta()
    detector = CurbDetector()
    threading.Thread(target=ingest_loop, daemon=True).start()

    def meta_loop() -> None:
        while True:
            time.sleep(30)
            refresh_meta()

    threading.Thread(target=meta_loop, daemon=True).start()
    print(f"[vision] listening {HOST}:{PORT}  middleware={MIDDLEWARE}")


@app.post("/frame")
async def frame(
    camera: str = Form(...),
    seq: int = Form(0),
    image: UploadFile = File(...),
) -> dict:
    body = await image.read()
    cam = camera.strip().lower()
    with frame_lock:
        last_frames[cam] = body
    return {"ok": True, "camera": cam, "seq": seq, "bytes": len(body)}


@app.get("/health")
def health() -> dict:
    return {"ok": True, "middleware": MIDDLEWARE, "detector": detector is not None}


if __name__ == "__main__":
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")
