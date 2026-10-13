"""Mask / logits → curb contour in camera pixels."""
from __future__ import annotations

from dataclasses import dataclass
from typing import List, Tuple

import cv2
import numpy as np

ADAS_CURB = 2


@dataclass
class CameraDetection:
    found: bool
    confidence: float
    x0: int
    y0: int
    x1: int
    y1: int
    contour: List[Tuple[int, int]]


def adas_logits_to_detection(logits: np.ndarray, orig_w: int, orig_h: int) -> CameraDetection:
    if logits.ndim == 4:
        cls_map = np.argmax(logits[0], axis=0)
    else:
        cls_map = (logits[0, 0] > 0.5).astype(np.uint8) * ADAS_CURB
    curb = (cls_map == ADAS_CURB).astype(np.uint8)
    road = (cls_map == 1).astype(np.uint8)
    h = curb.shape[0]
    roi = curb[int(h * 0.4) :, :]
    if roi.sum() < 25:
        edge = cv2.Canny((road * 255).astype(np.uint8), 50, 150)
        roi = edge[int(h * 0.4) :, :]
    ys, xs = np.where(roi > 0)
    if len(xs) < 30:
        return CameraDetection(False, 0.0, 0, 0, 0, 0, [])
    mh, mw = logits.shape[-2], logits.shape[-1]
    sx, sy = orig_w / mw, orig_h / mh
    x0, x1 = int(xs.min() * sx), int(xs.max() * sx)
    y0 = int((ys.min() + int(h * 0.4)) * sy)
    y1 = int((ys.max() + int(h * 0.4)) * sy)
    x0, x1 = max(0, min(x0, orig_w - 1)), max(x0 + 1, min(x1, orig_w))
    y0, y1 = max(0, min(y0, orig_h - 1)), max(y0 + 1, min(y1, orig_h))
    conf = float(roi.mean())
    # sparse polyline along bottom of mask
    contour: List[Tuple[int, int]] = []
    for frac in (0.2, 0.35, 0.5, 0.65, 0.8):
        row = int(ys.min() + (ys.max() - ys.min()) * frac)
        cols = xs[ys == row] if np.any(ys == row) else xs
        if len(cols) == 0:
            continue
        cx = int(cols.mean() * sx)
        cy = int((row + int(h * 0.4)) * sy)
        contour.append((cx, min(orig_h - 1, cy)))
    return CameraDetection(True, conf, x0, y0, x1, y1, contour)
