import math

import numpy as np

from calibration_loader import CameraCalibration, java_pinhole_fx_fy
from curb_distance_estimator import ray_ground_intersection


def _mount_forward_level() -> np.ndarray:
    """OpenCV cam (+x right, +y down, +z fwd) → WP5 vehicle (+x fwd, +y left, +z up)."""
    return np.array(
        [
            [0.0, -1.0, 0.0],
            [0.0, 0.0, -1.0],
            [1.0, 0.0, 0.0],
        ],
        dtype=float,
    )


def _pitch_cam_deg(r_mount: np.ndarray, pitch_deg: float) -> np.ndarray:
    """Pitch about camera +Y (image down); negative pitch tilts optical axis toward ground."""
    p = math.radians(pitch_deg)
    cp, sp = math.cos(p), math.sin(p)
    ry = np.array([[cp, 0, sp], [0, 1, 0], [-sp, 0, cp]], dtype=float)
    return r_mount @ ry


def _cal(t_m, r_cam_to_vehicle, valid=True) -> CameraCalibration:
    return CameraCalibration(
        name="test",
        valid=valid,
        width=640,
        height=360,
        fx=320.0,
        fy=320.0,
        cx=320.0,
        cy=180.0,
        t_m=np.array(t_m, dtype=float),
        r_cam_to_vehicle=r_cam_to_vehicle,
    )


def test_java_pinhole_matches_90deg_hfov():
    fx, fy = java_pinhole_fx_fy(640, 360, 90.0)
    assert abs(fx - 320.0) < 1e-6
    assert abs(fy - 320.0) < 1e-6


def test_center_ray_hits_ground_one_metre_ahead():
    r = _pitch_cam_deg(_mount_forward_level(), -45.0)
    cal = _cal(t_m=[0.0, 0.0, 1.0], r_cam_to_vehicle=r)
    res = ray_ground_intersection(cal, 320.0, 180.0, ground_z_m=0.0)
    assert res.valid
    assert abs(res.distance_m - 1.0) < 1e-6


def test_rejects_parallel_to_ground():
    r = _mount_forward_level()
    cal = _cal(t_m=[0.0, 0.0, 1.0], r_cam_to_vehicle=r)
    res = ray_ground_intersection(cal, 320.0, 180.0, ground_z_m=0.0)
    assert not res.valid
    assert res.reason == "ray_parallel_to_ground"


def test_rejects_invalid_calibration():
    r = _pitch_cam_deg(_mount_forward_level(), -45.0)
    cal = _cal(t_m=[0.0, 0.0, 1.0], r_cam_to_vehicle=r, valid=False)
    res = ray_ground_intersection(cal, 320.0, 180.0, ground_z_m=0.0)
    assert not res.valid
    assert res.reason == "calibration_not_valid"
