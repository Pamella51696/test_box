"""Load per-camera calibration for ground-plane distance (PR2)."""
from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Literal, Optional, Tuple

import numpy as np
import yaml

# OpenCV camera (+x right, +y down, +z forward) → WP5 vehicle (+x fwd, +y left, +z up).
# Columns: cam +X → vehicle −Y (right), +Y → −Z (down), +Z → +X (forward).
R_MOUNT_OPENCV_WP5 = np.array(
    [
        [0.0, 0.0, 1.0],
        [-1.0, 0.0, 0.0],
        [0.0, -1.0, 0.0],
    ],
    dtype=float,
)

RotationModel = Literal["ypr_intrinsic", "vehicle_mount_yaw"]


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
    calibration_type: str = "measured"
    coordinate_frame: str = "rear_axle"
    rotation_model: RotationModel = "ypr_intrinsic"


def _ypr_intrinsic_deg_to_r(yaw_deg: float, pitch_deg: float, roll_deg: float) -> np.ndarray:
    """Yaw-pitch-roll (Y, X, Z intrinsic) in degrees → rotation camera→vehicle."""
    y, p, r = map(math.radians, (yaw_deg, pitch_deg, roll_deg))
    cy, sy = math.cos(y), math.sin(y)
    cp, sp = math.cos(p), math.sin(p)
    cr, sr = math.cos(r), math.sin(r)
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rx = np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]])
    rz = np.array([[cr, -sr, 0], [sr, cr, 0], [0, 0, 1]])
    return ry @ rx @ rz


def vehicle_mount_yaw_deg_to_r(
    yaw_deg: float,
    pitch_deg: float = 0.0,
    roll_deg: float = 0.0,
) -> np.ndarray:
    """
    Vehicle-frame yaw (0° = forward), pitch about vehicle +Y (positive = nose down),
    OpenCV→WP5 mount, then roll about camera +X.
    """
    y, p, r = map(math.radians, (yaw_deg, pitch_deg, roll_deg))
    cy, sy = math.cos(y), math.sin(y)
    ry_yaw = np.array([[cy, -sy, 0.0], [sy, cy, 0.0], [0.0, 0.0, 1.0]])
    cp, sp = math.cos(p), math.sin(p)
    ry_pitch = np.array([[cp, 0.0, sp], [0.0, 1.0, 0.0], [-sp, 0.0, cp]])
    cr, sr = math.cos(r), math.sin(r)
    rx_cam = np.array([[1.0, 0.0, 0.0], [0.0, cr, -sr], [0.0, sr, cr]])
    return ry_yaw @ ry_pitch @ R_MOUNT_OPENCV_WP5 @ rx_cam


def rotation_from_extrinsics(ext: dict) -> Tuple[np.ndarray, RotationModel]:
    model: RotationModel = str(ext.get("rotation_model", "ypr_intrinsic"))
    ypr = ext.get("r_ypr_deg", [0.0, 0.0, 0.0])
    if len(ypr) != 3:
        ypr = [0.0, 0.0, 0.0]
    yaw, pitch, roll = float(ypr[0]), float(ypr[1]), float(ypr[2])
    if model == "vehicle_mount_yaw":
        return vehicle_mount_yaw_deg_to_r(yaw, pitch, roll), model
    return _ypr_intrinsic_deg_to_r(yaw, pitch, roll), model


def load_camera_yaml(path: Path, *, allow_synthetic: bool = False) -> CameraCalibration:
    with open(path, encoding="utf-8") as f:
        raw = yaml.safe_load(f)
    img = raw["image"]
    ext = raw.get("extrinsics", {})
    t = np.array(ext.get("t_m", [0.0, 0.0, 0.0]), dtype=float)
    r, rot_model = rotation_from_extrinsics(ext)
    cal_type = str(raw.get("calibration_type", "measured"))
    valid = bool(raw.get("valid", False))
    if cal_type == "synthetic" and not allow_synthetic:
        valid = False
    return CameraCalibration(
        name=str(raw.get("camera_name", path.stem)),
        valid=valid,
        width=int(img.get("width", 640)),
        height=int(img.get("height", 360)),
        fx=float(img["fx"]),
        fy=float(img["fy"]),
        cx=float(img.get("cx", img.get("width", 640) / 2)),
        cy=float(img.get("cy", img.get("height", 360) / 2)),
        t_m=t,
        r_cam_to_vehicle=r,
        calibration_type=cal_type,
        coordinate_frame=str(raw.get("coordinate_frame", "rear_axle")),
        rotation_model=rot_model,
    )


def load_all(directory: Path, *, allow_synthetic: bool = False) -> Dict[str, CameraCalibration]:
    out: Dict[str, CameraCalibration] = {}
    if not directory.is_dir():
        return out
    for path in sorted(directory.glob("*.yaml")):
        if path.name == "vehicle_frame.yaml":
            continue
        cal = load_camera_yaml(path, allow_synthetic=allow_synthetic)
        out[cal.name] = cal
    return out


def resolve_calibration_directory(
    vision_root: Path,
    cal_cfg: dict,
    profile_override: Optional[str] = None,
) -> Tuple[Path, str]:
    """Return calibration directory and active profile name."""
    profile = (profile_override or cal_cfg.get("profile") or "default").strip()
    base = vision_root / cal_cfg.get("directory", "../config/calibration")
    if profile == "default":
        return base.resolve(), profile
    if profile == "synthetic-dev":
        return (vision_root / "../config/calibration/synthetic-dev").resolve(), profile
    # Custom subdirectory under config/calibration/
    return (base / profile).resolve(), profile


def java_pinhole_fx_fy(width: int = 640, height: int = 360, hfov_deg: float = 90.0) -> Tuple[float, float]:
    """Match VideoStreamingServer.FisheyeUndistorter pinholeK(640,360,90°)."""
    half = math.radians(hfov_deg) / 2.0
    f = (width / 2.0) / math.tan(half)
    return f, f


def project_vehicle_point_to_pixel(cal: CameraCalibration, p_vehicle: np.ndarray) -> Tuple[float, float]:
    """Project a 3D vehicle-frame point into rectified camera pixels (for synthetic tests)."""
    d = p_vehicle - cal.t_m
    d_cam = cal.r_cam_to_vehicle.T @ d
    if d_cam[2] <= 1e-9:
        raise ValueError("point behind camera")
    u = cal.fx * d_cam[0] / d_cam[2] + cal.cx
    v = cal.fy * d_cam[1] / d_cam[2] + cal.cy
    return u, v
