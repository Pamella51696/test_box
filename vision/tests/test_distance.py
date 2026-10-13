import math

import numpy as np

from calibration_loader import CameraCalibration
from curb_distance_estimator import estimate_from_contour, representative_pixel


def _mount_forward_level() -> np.ndarray:
    return np.array(
        [
            [0.0, -1.0, 0.0],
            [0.0, 0.0, -1.0],
            [1.0, 0.0, 0.0],
        ],
        dtype=float,
    )


def _pitch_cam_deg(r_mount: np.ndarray, pitch_deg: float) -> np.ndarray:
    p = math.radians(pitch_deg)
    cp, sp = math.cos(p), math.sin(p)
    ry = np.array([[cp, 0, sp], [0, 1, 0], [-sp, 0, cp]], dtype=float)
    return r_mount @ ry


def test_representative_pixel_uses_bottom_band():
    contour = [[100, 200], [110, 350], [120, 340]]
    u, v = representative_pixel(contour, 360, bottom_fraction=0.4)
    assert v >= 360 * 0.6 - 1
    assert 105 <= u <= 115


def test_estimate_from_contour_known_geometry():
    cal = CameraCalibration(
        name="left",
        valid=True,
        width=640,
        height=360,
        fx=320.0,
        fy=320.0,
        cx=320.0,
        cy=180.0,
        t_m=np.array([0.0, 0.0, 2.0]),
        r_cam_to_vehicle=_pitch_cam_deg(_mount_forward_level(), -45.0),
    )
    contour = [[320, 350], [325, 355]]
    res = estimate_from_contour(cal, contour, 360, ground_z_m=0.0)
    assert res.valid
    assert 2.0 < res.distance_m < 3.0
