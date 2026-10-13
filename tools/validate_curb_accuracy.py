#!/usr/bin/env python3
"""
PR5: compare measured curb distances (ground truth) to vision metric estimator output.

Requires valid measured calibration (not AI/synthetic footage claims).
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
VISION = ROOT / "vision"
sys.path.insert(0, str(VISION))

from accuracy_validation import run_validation  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description="Validate curb distance accuracy against ground truth JSONL")
    ap.add_argument(
        "--ground-truth",
        type=Path,
        default=ROOT / "config/calibration/validation/ground_truth.example.jsonl",
        help="JSONL file: one sample per line (see config/calibration/validation/README.md)",
    )
    ap.add_argument("--profile", default="default", help="calibration profile under config/calibration/")
    ap.add_argument("--config", type=Path, default=VISION / "config.yaml")
    ap.add_argument("--allow-synthetic", action="store_true", help="allow synthetic-dev calibration (dev only)")
    ap.add_argument("--report", type=Path, help="write JSON report to this path")
    ap.add_argument("--min-pass-rate", type=float, default=1.0, help="fail if pass rate below this (0–1)")
    ap.add_argument("--max-mae-m", type=float, default=None, help="optional fail if MAE exceeds this (metres)")
    args = ap.parse_args()

    with open(args.config, encoding="utf-8") as f:
        cfg = yaml.safe_load(f)["vision"]
    cal_cfg = cfg.get("calibration", {})
    ground_z = float(cal_cfg.get("ground_plane_z_m", 0.0))

    if not args.ground_truth.is_file():
        print(f"FAIL: ground truth not found: {args.ground_truth}", file=sys.stderr)
        return 2

    report = run_validation(
        VISION,
        cal_cfg,
        args.profile,
        args.ground_truth,
        allow_synthetic=args.allow_synthetic,
        ground_z_m=ground_z,
        require_valid_calibration=not args.allow_synthetic,
    )
    body = report.to_dict()
    text = json.dumps(body, indent=2)
    print(text)
    if args.report:
        args.report.write_text(text + "\n", encoding="utf-8")

    fails: list[str] = []
    if report.skipped_invalid_calibration:
        fails.append(
            "calibration not valid for: "
            + ", ".join(sorted(set(report.skipped_invalid_calibration)))
            + " (fill measured YAML and set valid: true)"
        )
    if report.mae_m is not None and args.max_mae_m is not None and report.mae_m > args.max_mae_m:
        fails.append(f"MAE {report.mae_m:.3f} m > limit {args.max_mae_m} m")
    if report.pass_rate < args.min_pass_rate:
        fails.append(f"pass rate {report.pass_rate:.2%} < {args.min_pass_rate:.2%}")

    if fails:
        for msg in fails:
            print("FAIL:", msg, file=sys.stderr)
        return 1
    if not report.evaluated:
        print("FAIL: no samples could be evaluated", file=sys.stderr)
        return 1
    print("PASS: accuracy validation complete")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
