"""Load per-camera calibration for ground-plane distance (PR2)."""
from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
import yaml


@dataclass
class CameraCalibration:
    name: str
    valid: bool
    width: int
    height: int
    fx: float
    fy: float
    cx: float
    cy: float
    t_m: np.ndarray  # camera centre in vehicle frame (3,)
    r_cam_to_vehicle: np.ndarray  # 3x3


def _ypr_deg_to_r(yaw_deg: float, pitch_deg: float, roll_deg: float) -> np.ndarray:
    """Yaw-pitch-roll (Y, X, Z intrinsic) in degrees → rotation camera→vehicle."""
    y, p, r = map(math.radians, (yaw_deg, pitch_deg, roll_deg))
    cy, sy = math.cos(y), math.sin(y)
    cp, sp = math.cos(p), math.sin(p)
    cr, sr = math.cos(r), math.sin(r)
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rx = np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]])
    rz = np.array([[cr, -sr, 0], [sr, cr, 0], [0, 0, 1]])
    return ry @ rx @ rz


def load_camera_yaml(path: Path) -> CameraCalibration:
    with open(path, encoding="utf-8") as f:
        raw = yaml.safe_load(f)
    img = raw["image"]
    ext = raw.get("extrinsics", {})
    t = np.array(ext.get("t_m", [0.0, 0.0, 0.0]), dtype=float)
    ypr = ext.get("r_ypr_deg", [0.0, 0.0, 0.0])
    if len(ypr) != 3:
        ypr = [0.0, 0.0, 0.0]
    r = _ypr_deg_to_r(float(ypr[0]), float(ypr[1]), float(ypr[2]))
    return CameraCalibration(
        name=str(raw.get("camera_name", path.stem)),
        valid=bool(raw.get("valid", False)),
        width=int(img.get("width", 640)),
        height=int(img.get("height", 360)),
        fx=float(img["fx"]),
        fy=float(img["fy"]),
        cx=float(img.get("cx", img.get("width", 640) / 2)),
        cy=float(img.get("cy", img.get("height", 360) / 2)),
        t_m=t,
        r_cam_to_vehicle=r,
    )


def load_all(directory: Path) -> Dict[str, CameraCalibration]:
    out: Dict[str, CameraCalibration] = {}
    if not directory.is_dir():
        return out
    for path in sorted(directory.glob("*.yaml")):
        if path.name == "vehicle_frame.yaml":
            continue
        cal = load_camera_yaml(path)
        out[cal.name] = cal
    return out


def java_pinhole_fx_fy(width: int = 640, height: int = 360, hfov_deg: float = 90.0) -> Tuple[float, float]:
    """Match VideoStreamingServer.FisheyeUndistorter pinholeK(640,360,90°)."""
    half = math.radians(hfov_deg) / 2.0
    f = (width / 2.0) / math.tan(half)
    return f, f
