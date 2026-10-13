"""Reject invalid metric readings before ingest."""
from __future__ import annotations

from dataclasses import dataclass

from curb_distance_estimator import DistanceResult


@dataclass
class ValidatedDistance:
    ok: bool
    distance_m: float
    uncertainty_m: float
    reason: str


def validate(
    det_confidence: float,
    geom: DistanceResult,
    min_conf: float,
    min_m: float,
    max_m: float,
) -> ValidatedDistance:
    if det_confidence < min_conf:
        return ValidatedDistance(False, 0.0, 0.0, "low_detection_confidence")
    if not geom.valid:
        return ValidatedDistance(False, 0.0, 0.0, geom.reason or "invalid_geometry")
    d = geom.distance_m
    if d < min_m or d > max_m:
        return ValidatedDistance(False, d, 0.0, "out_of_range")
    # Simple uncertainty: larger when pixel row is high (farther on ground) — placeholder
    unc = max(0.05, 0.08 + 0.02 * d)
    return ValidatedDistance(True, d, unc, "ok")
