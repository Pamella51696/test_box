#!/usr/bin/env python3
"""
Build ground-truth JSONL for an artificial calibration profile by projecting
known 3D curb points on the ground plane (independent of the estimator).
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import yaml

ROOT = Path(__file__).resolve().parents[1]
VISION = ROOT / "vision"
sys.path.insert(0, str(VISION))

from calibration_loader import load_all, project_vehicle_point_to_pixel, resolve_calibration_directory  # noqa: E402


# Ground points (vehicle frame: +x fwd, +y left, z=0) — artificial curb locations
SCENES = [
    ("front-near", "front", np.array([3.2, 0.15, 0.0])),
    ("front-mid", "front", np.array([4.5, -0.2, 0.0])),
    ("left-close", "left", np.array([1.2, 1.05, 0.0])),
    ("left-mid", "left", np.array([2.0, 1.4, 0.0])),
    ("right-close", "right", np.array([1.0, -1.1, 0.0])),
    ("right-mid", "right", np.array([2.5, -1.35, 0.0])),
    ("rear-near", "rear", np.array([-2.5, 0.1, 0.0])),
    ("rear-mid", "rear", np.array([-3.8, -0.25, 0.0])),
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile", default="artificial-measured")
    ap.add_argument(
        "--output",
        type=Path,
        default=ROOT / "config/calibration/validation/ground_truth.artificial-measured.jsonl",
    )
    ap.add_argument("--tolerance-m", type=float, default=0.08)
    args = ap.parse_args()

    with open(VISION / "config.yaml", encoding="utf-8") as f:
        cal_cfg = yaml.safe_load(f)["vision"]["calibration"]

    cal_dir, _ = resolve_calibration_directory(VISION, cal_cfg, args.profile)
    cals = load_all(cal_dir, allow_synthetic=True)

    lines: list[str] = []
    for sid, cam, p in SCENES:
        cal = cals.get(cam)
        if cal is None:
            print(f"skip {sid}: no cal for {cam}", file=sys.stderr)
            continue
        try:
            u, v = project_vehicle_point_to_pixel(cal, p)
        except ValueError as e:
            print(f"skip {sid}: {e}", file=sys.stderr)
            continue
        ui, vi = int(round(u)), int(round(v))
        # Short polyline along bottom band (curb-base heuristic uses lower image rows)
        contour = [
            [ui - 8, vi],
            [ui, vi],
            [ui + 12, vi - 2],
        ]
        dist = float(np.hypot(p[0], p[1]))
        row = {
            "id": f"artificial-{sid}",
            "camera": cam,
            "contour_camera_pixels": contour,
            "distance_m": round(dist, 4),
            "tolerance_m": args.tolerance_m,
            "image_height": cal.height,
            "notes": "ARTIFICIAL: projected ground point, not tape measure",
        }
        lines.append(json.dumps(row))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    header = "# ARTIFICIAL ground truth — for CI/dev only. Regenerate with tools/generate_artificial_ground_truth.py\n"
    args.output.write_text(header + "\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {len(lines)} samples → {args.output}")
    return 0 if lines else 1


if __name__ == "__main__":
    raise SystemExit(main())
