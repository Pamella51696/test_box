"""Ground-plane ray intersection for curb points (vehicle frame)."""
from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Tuple

import numpy as np

from calibration_loader import CameraCalibration


@dataclass
class DistanceResult:
    distance_m: float
    ground_point: np.ndarray  # (3,) vehicle frame
    valid: bool
    reason: str = ""


def pixel_to_ray_cam(u: float, v: float, cal: CameraCalibration) -> np.ndarray:
    """OpenCV camera: x right, y down, z forward."""
    x = (u - cal.cx) / cal.fx
    y = (v - cal.cy) / cal.fy
    ray = np.array([x, y, 1.0], dtype=float)
    n = np.linalg.norm(ray)
    if n < 1e-12:
        return ray
    return ray / n


def ray_ground_intersection(
    cal: CameraCalibration,
    u: float,
    v: float,
    ground_z_m: float = 0.0,
) -> DistanceResult:
    """
    Vehicle frame: +x forward, +y left, +z up (WP5). Ground plane z = ground_z_m.
    Distance = hypot(x, y) of the ground intersection in the active calibration frame
    (rear_axle for measured YAML; vehicle_center for synthetic-dev).
    Uses rectified camera pixels (640×360 from Java), not raw fisheye or panorama.
    """
    if not cal.valid:
        return DistanceResult(0.0, np.zeros(3), False, "calibration_not_valid")
    ray_cam = pixel_to_ray_cam(u, v, cal)
    d_vehicle = cal.r_cam_to_vehicle @ ray_cam
    dz = d_vehicle[2]
    if abs(dz) < 1e-9:
        return DistanceResult(0.0, np.zeros(3), False, "ray_parallel_to_ground")
    lam = (ground_z_m - cal.t_m[2]) / dz
    if lam <= 0:
        return DistanceResult(0.0, np.zeros(3), False, "intersection_behind_camera")
    p = cal.t_m + lam * d_vehicle
    dist = float(np.hypot(p[0], p[1]))
    if not np.isfinite(dist):
        return DistanceResult(0.0, p, False, "non_finite")
    return DistanceResult(dist, p, True, "ok")


def representative_pixel(
    contour: List[List[int]],
    image_height: int,
    bottom_fraction: float = 0.4,
) -> Optional[Tuple[float, float]]:
    """Curb contact heuristic: lowest points (max v) in bottom band of image."""
    if not contour:
        return None
    y_min = image_height * (1.0 - bottom_fraction)
    band = [(x, y) for x, y in contour if y >= y_min]
    if not band:
        band = contour
    # Prefer maximum row (closest to bottom of image)
    y_max = max(p[1] for p in band)
    xs = [p[0] for p in band if p[1] >= y_max - 2]
    if not xs:
        return None
    return float(sum(xs) / len(xs)), float(y_max)


def estimate_from_contour(
    cal: CameraCalibration,
    contour: List[List[int]],
    image_height: int,
    ground_z_m: float = 0.0,
) -> DistanceResult:
    pt = representative_pixel(contour, image_height)
    if pt is None:
        return DistanceResult(0.0, np.zeros(3), False, "no_contour_point")
    return ray_ground_intersection(cal, pt[0], pt[1], ground_z_m)
