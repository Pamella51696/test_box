#!/usr/bin/env python3
"""
Vision sidecar: overlay (PR1) and optional metric curb ingest (PR2).
- Receives frames from Java VisionFrameTap
- Runs segmentation (OpenVINO ADAS curb model by default)
- overlay_only / both → POST /api/v1/vision/detections
- metric / both → POST /api/v1/ingest/range (fail-closed without valid calibration)
"""
from __future__ import annotations

import json
import os
import threading
import time
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import cv2
import numpy as np
import uvicorn
import yaml
from fastapi import FastAPI, File, Form, UploadFile

from calibration_loader import load_all, resolve_calibration_directory
from coordinate_mapper import contour_camera_to_panorama, fetch_meta, panorama_contour_strip
from curb_distance_estimator import DistanceResult, estimate_from_contour
from detection_schema import Detection, envelope
from distance_validator import validate as validate_distance
from frame_receiver import FrameStore
from model_runner import ModelRunner
from vision_ingest_client import build_sensor, post_range_sensors

CAMERA_TO_POS = {
    "left": "LEFT_REAR",
    "right": "RIGHT_REAR",
    "rear": "REAR_CENTER",
    "front": "FRONT_CENTER",
}

ROOT = Path(__file__).resolve().parent


def normalize_jpeg(body: bytes) -> bytes:
    """Java VisionFramePublisher may prefix image part with stray CRLF before SOI."""
    start = body.find(b"\xff\xd8")
    if start < 0:
        return body
    end = body.rfind(b"\xff\xd9")
    if end >= start:
        return body[start : end + 2]
    return body[start:]


def load_config() -> Dict[str, Any]:
    path = os.environ.get("VISION_CONFIG", str(ROOT / "config.yaml"))
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)["vision"]


CFG = load_config()
MIDDLEWARE = os.environ.get("MIDDLEWARE_URL", CFG.get("middleware_url", "http://127.0.0.1:9091")).rstrip("/")
HOST = os.environ.get("VISION_HOST", CFG["server"]["host"])
PORT = int(os.environ.get("VISION_PORT", CFG["server"]["port"]))
MODE = os.environ.get("VISION_MODE", CFG.get("mode", "overlay_only")).strip().lower()
CONF_TH = float(CFG["inference"]["confidence_threshold"])
TARGET_HZ = float(CFG["inference"]["target_hz"])
MAX_AGE_MS = int(CFG["inference"]["max_frame_age_ms"])
PANO_W = int(CFG["output"]["width"])
PANO_H = int(CFG["output"]["height"])

CAL_CFG = CFG.get("calibration", {})
CAL_PROFILE = os.environ.get("VISION_CALIBRATION_PROFILE", CAL_CFG.get("profile", "default"))
ALLOW_SYNTHETIC = os.environ.get("VISION_ALLOW_SYNTHETIC", "").strip() in ("1", "true", "yes")
if not ALLOW_SYNTHETIC:
    ALLOW_SYNTHETIC = bool(CAL_CFG.get("allow_synthetic", False))
CAL_DIR, CAL_PROFILE = resolve_calibration_directory(ROOT, CAL_CFG, CAL_PROFILE)
GROUND_Z = float(CAL_CFG.get("ground_plane_z_m", 0.0))
CALIBRATIONS = load_all(CAL_DIR, allow_synthetic=ALLOW_SYNTHETIC)

DIST_CFG = CFG.get("distance", {})
MIN_DIST_M = float(DIST_CFG.get("min_valid_distance_m", 0.05))
MAX_DIST_M = float(DIST_CFG.get("max_valid_distance_m", 5.0))

VAL_CFG = CFG.get("validation", {})
MIN_DET_CONF = float(VAL_CFG.get("min_detection_confidence", 0.60))

INGEST_CFG = CFG.get("ingest", {})
INGEST_ENABLED = bool(INGEST_CFG.get("enabled", True)) and MODE in ("metric", "both")
INGEST_HZ = float(INGEST_CFG.get("publish_hz", 10.0))
SENSOR_PREFIX = str(INGEST_CFG.get("sensor_id_prefix", "VISION_"))
MIN_PANO_STRIP = int(INGEST_CFG.get("min_pano_strip_px", 40))

DO_OVERLAY = MODE in ("overlay_only", "both")
DO_METRIC = MODE in ("metric", "both")

frames = FrameStore(max_age_ms=MAX_AGE_MS)
meta: Dict[str, Any] = {}
meta_lock = threading.Lock()
runner: ModelRunner | None = None
last_ingest_mono = 0.0

app = FastAPI(title="WP5 vision sidecar")


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


def metric_for_detection(
    confidence: float,
    camera: str,
    cam_contour: List[List[int]],
    image_height: int,
    pano_contour: List[List[int]],
) -> Tuple[Optional[float], bool, Optional[float], Optional[Dict[str, Any]]]:
    """Distance fields and optional ingest sensor; fail-closed when calibration invalid."""
    cal = CALIBRATIONS.get(camera)
    if cal is None:
        geom = DistanceResult(0.0, np.zeros(3), False, "no_calibration")
    else:
        geom = estimate_from_contour(cal, cam_contour, image_height, GROUND_Z)
    v = validate_distance(confidence, geom, MIN_DET_CONF, MIN_DIST_M, MAX_DIST_M)
    if not v.ok:
        return None, False, None, None
    pos = CAMERA_TO_POS.get(camera, "")
    sensor: Optional[Dict[str, Any]] = None
    if pos:
        strip = panorama_contour_strip(pano_contour, MIN_PANO_STRIP)
        pano_x, pano_w = strip if strip else (None, None)
        sensor = build_sensor(f"{SENSOR_PREFIX}{pos}", pos, v.distance_m, pano_x, pano_w)
    return v.distance_m, True, v.uncertainty_m, sensor


def inference_loop() -> None:
    global runner, last_ingest_mono
    while runner is None:
        time.sleep(0.1)
    interval = 1.0 / TARGET_HZ
    ingest_interval = 1.0 / INGEST_HZ if INGEST_ENABLED and INGEST_HZ > 0 else 0.0
    while True:
        time.sleep(interval)
        with meta_lock:
            m = meta
        if not m:
            continue
        batch = frames.snapshot_latest()
        if not batch:
            continue
        now_ms = time.time() * 1000.0
        max_age = int(VAL_CFG.get("max_detection_age_ms", MAX_AGE_MS))
        all_det: List[Detection] = []
        ingest_sensors: List[Dict[str, Any]] = []
        max_fid = 0
        for cam, pkt in batch.items():
            if now_ms - pkt.timestamp_ms > max_age and pkt.timestamp_ms > 0:
                continue
            arr = np.frombuffer(pkt.jpeg, dtype=np.uint8)
            bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
            if bgr is None:
                continue
            det = runner.infer(bgr)
            if not det.found or det.confidence < CONF_TH:
                continue
            max_fid = max(max_fid, pkt.frame_id)
            cam_contour = [[int(x), int(y)] for x, y in det.contour]
            pano_contour = contour_camera_to_panorama(m, cam, det.contour)
            if len(pano_contour) < 2:
                continue

            dist_m: Optional[float] = None
            dist_valid = False
            dist_unc: Optional[float] = None
            sensor: Optional[Dict[str, Any]] = None
            if DO_METRIC:
                dist_m, dist_valid, dist_unc, sensor = metric_for_detection(
                    det.confidence, cam, cam_contour, pkt.height, pano_contour
                )
                if sensor is not None:
                    ingest_sensors.append(sensor)

            all_det.append(
                Detection(
                    camera=cam,
                    confidence=det.confidence,
                    coordinate_space="PANORAMA_PIXELS",
                    contour=pano_contour,
                    contour_camera_pixels=cam_contour,
                    image_width=pkt.width,
                    image_height=pkt.height,
                    pos=CAMERA_TO_POS.get(cam, ""),
                    bbox=[],
                    distance_m=dist_m,
                    distance_valid=dist_valid,
                    distance_uncertainty_m=dist_unc,
                )
            )

        if DO_OVERLAY and all_det:
            try:
                post_detections(
                    envelope(max_fid, time.monotonic_ns(), PANO_W, PANO_H, all_det)
                )
            except Exception as e:
                print("[vision] post detections:", e)

        if INGEST_ENABLED and ingest_sensors:
            now = time.monotonic()
            if ingest_interval <= 0 or (now - last_ingest_mono) >= ingest_interval:
                last_ingest_mono = now
                try:
                    post_range_sensors(MIDDLEWARE, ingest_sensors)
                except Exception as e:
                    print("[vision] post ingest:", e)


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
    cal_valid = [k for k, c in CALIBRATIONS.items() if c.valid]
    synth = [k for k, c in CALIBRATIONS.items() if c.calibration_type == "synthetic"]
    print(
        f"[vision] mode={MODE}  {HOST}:{PORT}  →  {MIDDLEWARE}  "
        f"overlay={DO_OVERLAY} metric={DO_METRIC} ingest={INGEST_ENABLED} "
        f"cal_profile={CAL_PROFILE} allow_synthetic={ALLOW_SYNTHETIC} "
        f"calib_valid={cal_valid or 'none'} synthetic_files={synth or 'none'}"
    )


@app.post("/frame")
async def frame(
    camera: str = Form(...),
    frameId: int = Form(0),
    timestampMs: int = Form(0),
    width: int = Form(640),
    height: int = Form(360),
    image: UploadFile = File(...),
) -> dict:
    body = normalize_jpeg(await image.read())
    cam = camera.strip().lower()
    frames.put(cam, frameId, timestampMs, width, height, body)
    return {"ok": True, "camera": camera, "frameId": frameId}


@app.get("/health")
def health() -> dict:
    snap = frames.snapshot_fresh()
    return {
        "ok": True,
        "middleware": MIDDLEWARE,
        "mode": MODE,
        "model": runner is not None,
        "freshFrames": {k: v.frame_id for k, v in snap.items()},
        "calibrationProfile": CAL_PROFILE,
        "allowSynthetic": ALLOW_SYNTHETIC,
        "calibration": {
            k: {
                "valid": c.valid,
                "type": c.calibration_type,
                "frame": c.coordinate_frame,
            }
            for k, c in CALIBRATIONS.items()
        },
    }


if __name__ == "__main__":
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")
