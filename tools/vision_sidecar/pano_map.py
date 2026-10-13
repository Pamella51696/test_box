"""Map a detection bbox in camera pixels to panorama coordinates (/meta layout)."""
from __future__ import annotations

import json
import urllib.request
from typing import Any, Dict, Optional, Tuple


def fetch_meta(middleware_base: str) -> Dict[str, Any]:
    with urllib.request.urlopen(middleware_base.rstrip("/") + "/meta", timeout=5) as r:
        return json.load(r)


def camera_to_pano(
    meta: Dict[str, Any],
    camera: str,
    x0: int,
    y0: int,
    x1: int,
    y1: int,
) -> Optional[Tuple[int, int, str]]:
    """
    Convert inclusive-exclusive bbox in camera image space to panorama (x, w).
    Returns (pano_x, pano_w, camera_name) or None if camera unknown.
    """
    cams = meta.get("cameras") or {}
    cam = cams.get(camera)
    if not cam:
        return None
    sw = cam.get("source_w", cam.get("w", 640))
    sh = cam.get("source_h", cam.get("h", 360))
    # Scale if sidecar frame size differs from meta source size
    cx0 = int(x0 * cam["w"] / sw)
    cx1 = int(x1 * cam["w"] / sw)
    pano_x = cam["x"] + max(0, cx0)
    pano_w = max(8, min(cam["w"], cx1 - cx0))
    pw = meta["panorama"]["width"]
    if pano_x + pano_w > pw:
        pano_w = pw - pano_x
    if pano_w <= 0:
        return None
    return pano_x, pano_w, camera


# Which logical sensor slot to fill per camera (phase 1: one zone per side camera).
CAMERA_TO_POS = {
    "left": "LEFT_REAR",
    "right": "RIGHT_REAR",
    "rear": "REAR_CENTER",
    "front": "FRONT_CENTER",
}
