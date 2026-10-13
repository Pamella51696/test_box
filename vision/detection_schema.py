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
    contour_camera_pixels: List[List[int]] = field(default_factory=list)
    image_width: int = 0
    image_height: int = 0
    pos: str = ""  # logical sensor e.g. LEFT_REAR (PR2 ingest)
    bbox: List[int] = field(default_factory=list)  # x0,y0,x1,y1 optional
    distance_m: float | None = None
    distance_valid: bool = False
    distance_uncertainty_m: float | None = None

    def to_dict(self) -> Dict[str, Any]:
        d: Dict[str, Any] = {
            "class": "CURB",
            "confidence": round(min(1.0, max(0.0, self.confidence)), 4),
            "camera": self.camera,
            "coordinateSpace": self.coordinate_space,
        }
        if self.pos:
            d["pos"] = self.pos
        if self.contour:
            d["contour"] = self.contour
        if self.contour_camera_pixels:
            d["contourCameraPixels"] = self.contour_camera_pixels
        if self.image_width and self.image_height:
            d["imageWidth"] = self.image_width
            d["imageHeight"] = self.image_height
        if self.distance_valid:
            d["distanceValid"] = True
            if self.distance_m is not None:
                d["distanceM"] = round(self.distance_m, 3)
            if self.distance_uncertainty_m is not None:
                d["distanceUncertaintyM"] = round(self.distance_uncertainty_m, 3)
        else:
            d["distanceValid"] = False
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
