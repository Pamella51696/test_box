"""Mask / logits → curb contour in camera pixels (per-camera tuning)."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

import cv2
import numpy as np

ADAS_CURB = 2
ADAS_ROAD = 1

DEFAULT_CAMERA_PROFILE: Dict[str, Any] = {
    "min_pixels": 25,
    "bottom_roi": 0.40,
    "min_confidence": 0.12,
    "edge_fallback": True,
}

CAMERA_PROFILES: Dict[str, Dict[str, Any]] = {
    "front": {"min_pixels": 25, "bottom_roi": 0.38, "min_confidence": 0.15, "edge_fallback": True},
    "rear": {"min_pixels": 12, "bottom_roi": 0.52, "min_confidence": 0.10, "edge_fallback": True, "side_band": 0.55},
    "left": {"min_pixels": 12, "bottom_roi": 0.42, "min_confidence": 0.10, "edge_fallback": True, "side_band": 0.45, "side": "left"},
    "right": {"min_pixels": 12, "bottom_roi": 0.42, "min_confidence": 0.10, "edge_fallback": True, "side_band": 0.45, "side": "right"},
}


@dataclass
class CameraDetection:
    found: bool
    confidence: float
    x0: int
    y0: int
    x1: int
    y1: int
    contour: List[Tuple[int, int]]


def _profile(camera: Optional[str], overrides: Optional[Dict[str, Dict[str, Any]]]) -> Dict[str, Any]:
    base = dict(DEFAULT_CAMERA_PROFILE)
    if camera:
        cam = camera.strip().lower()
        if overrides and cam in overrides:
            base.update(overrides[cam])
        elif cam in CAMERA_PROFILES:
            base.update(CAMERA_PROFILES[cam])
    return base


def _curb_probability_map(logits: np.ndarray) -> np.ndarray:
    if logits.ndim != 4:
        return np.zeros((1, 1), dtype=np.float32)
    x = logits[0] - np.max(logits[0], axis=0, keepdims=True)
    exp = np.exp(x, dtype=np.float32)
    p = exp / np.maximum(exp.sum(axis=0, keepdims=True), 1e-9)
    return p[ADAS_CURB]


def _band_mask(h: int, w: int, prof: Dict[str, Any]) -> np.ndarray:
    y0 = int(h * (1.0 - float(prof["bottom_roi"])))
    mask = np.zeros((h, w), dtype=bool)
    mask[y0:, :] = True
    side = prof.get("side")
    band = float(prof.get("side_band", 0.5))
    if side == "left":
        mask[:, int(w * (1.0 - band)) :] = False
    elif side == "right":
        mask[:, : int(w * band)] = False
    return mask


def adas_logits_to_detection(
    logits: np.ndarray,
    orig_w: int,
    orig_h: int,
    camera: Optional[str] = None,
    camera_profiles: Optional[Dict[str, Dict[str, Any]]] = None,
) -> CameraDetection:
    prof = _profile(camera, camera_profiles)
    if logits.ndim == 4:
        cls_map = np.argmax(logits[0], axis=0)
        curb_prob = _curb_probability_map(logits)
    else:
        cls_map = (logits[0, 0] > 0.5).astype(np.uint8) * ADAS_CURB
        curb_prob = (cls_map == ADAS_CURB).astype(np.float32)

    curb = (cls_map == ADAS_CURB).astype(np.uint8)
    road = (cls_map == ADAS_ROAD).astype(np.uint8)
    h, w = curb.shape
    band = _band_mask(h, w, prof)
    roi = curb.copy()
    roi[~band] = 0

    if int(roi.sum()) < int(prof["min_pixels"]) and prof.get("edge_fallback"):
        edge = cv2.Canny((road * 255).astype(np.uint8), 40, 120)
        edge[~band] = 0
        roi = edge

    ys, xs = np.where(roi > 0)
    min_px = int(prof["min_pixels"])
    if len(xs) < min_px:
        # weak curb probability in band only
        prob_band = curb_prob.copy()
        prob_band[~band] = 0
        ys, xs = np.where(prob_band >= float(prof["min_confidence"]) * 0.5)
        if len(xs) < min_px:
            return CameraDetection(False, 0.0, 0, 0, 0, 0, [])

    mh, mw = logits.shape[-2], logits.shape[-1]
    sx, sy = orig_w / mw, orig_h / mh

    if logits.ndim == 4:
        model_ys = (ys / sy).astype(int)
        model_xs = (xs / sx).astype(int)
        model_ys = np.clip(model_ys, 0, mh - 1)
        model_xs = np.clip(model_xs, 0, mw - 1)
        conf = float(curb_prob[model_ys, model_xs].mean())
    else:
        conf = float(np.clip(roi[ys, xs].mean() / 255.0 if roi.max() > 1 else roi[ys, xs].mean(), 0.0, 1.0))

    conf = float(np.clip(conf, 0.0, 1.0))
    if conf < float(prof["min_confidence"]):
        return CameraDetection(False, conf, 0, 0, 0, 0, [])

    x0, x1 = int(xs.min() * sx), int(xs.max() * sx)
    y0 = int(ys.min() * sy)
    y1 = int(ys.max() * sy)
    x0, x1 = max(0, min(x0, orig_w - 1)), max(x0 + 1, min(x1, orig_w))
    y0, y1 = max(0, min(y0, orig_h - 1)), max(y0 + 1, min(y1, orig_h))

    contour: List[Tuple[int, int]] = []
    for frac in (0.15, 0.35, 0.5, 0.65, 0.85):
        row = int(ys.min() + (ys.max() - ys.min()) * frac)
        cols = xs[ys == row] if np.any(ys == row) else xs
        if len(cols) == 0:
            continue
        cx = int(cols.mean() * sx)
        cy = int(row * sy)
        contour.append((cx, min(orig_h - 1, cy)))

    if len(contour) < 2:
        return CameraDetection(False, conf, 0, 0, 0, 0, [])

    return CameraDetection(True, conf, x0, y0, x1, y1, contour)
