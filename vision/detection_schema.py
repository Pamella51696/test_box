"""PR-1 vision detection message (overlay only, no metric distance)."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List


@dataclass
class Detection:
    camera: str
    confidence: float
    coordinate_space: str  # CAMERA_PIXELS | PANORAMA_PIXELS
    contour: List[List[int]] = field(default_factory=list)
    bbox: List[int] = field(default_factory=list)  # x0,y0,x1,y1 optional

    def to_dict(self) -> Dict[str, Any]:
        d: Dict[str, Any] = {
            "class": "CURB",
            "confidence": round(self.confidence, 4),
            "camera": self.camera,
            "coordinateSpace": self.coordinate_space,
        }
        if self.contour:
            d["contour"] = self.contour
        if self.bbox:
            d["bbox"] = self.bbox
        return d


def envelope(
    frame_id: int,
    timestamp_ns: int,
    frame_width: int,
    frame_height: int,
    detections: List[Detection],
) -> Dict[str, Any]:
    return {
        "frameId": frame_id,
        "timestampMonotonicNs": timestamp_ns,
        "frameWidth": frame_width,
        "frameHeight": frame_height,
        "coordinateSpace": "PANORAMA_PIXELS",
        "detections": [d.to_dict() for d in detections],
    }
