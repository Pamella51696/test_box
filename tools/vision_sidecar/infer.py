"""ONNX / OpenVINO inference and curb-mask → bbox extraction."""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Tuple

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent
MODELS = ROOT / "models"

# road-segmentation-adas-0001 class indices (OpenVINO OMZ)
ADAS_BG, ADAS_ROAD, ADAS_CURB, ADAS_MARK = 0, 1, 2, 3


@dataclass
class Detection:
    found: bool
    x0: int
    y0: int
    x1: int
    y1: int
    confidence: float


class CurbDetector:
    def __init__(self) -> None:
        self.backend = os.environ.get("VISION_BACKEND", "openvino").lower()
        self._ov_compiled = None
        self._onnx = None
        self.input_h, self.input_w = 512, 896  # ADAS model default
        if self.backend == "onnx":
            self._init_onnx()
        else:
            self._init_openvino()

    def _init_openvino(self) -> None:
        from openvino import Core

        xml = MODELS / "road-segmentation-adas-0001.xml"
        if not xml.exists():
            raise FileNotFoundError(
                f"Missing {xml}. Run: python download_model.py --preset adas-curb"
            )
        core = Core()
        model = core.read_model(str(xml))
        self._ov_compiled = core.compile_model(model, "CPU")
        inp = self._ov_compiled.inputs[0]
        shape = inp.shape
        if len(shape) == 4:
            self.input_h, self.input_w = int(shape[2]), int(shape[3])

    def _init_onnx(self) -> None:
        import onnxruntime as ort

        onnx_path = Path(os.environ.get("VISION_ONNX_PATH", MODELS / "curb_segmentation.onnx"))
        if not onnx_path.exists():
            raise FileNotFoundError(
                f"ONNX model not found at {onnx_path}. See docs/VISION_CURB_MODELS.md to export or choose a model."
            )
        self._onnx = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        inp = self._onnx.get_inputs()[0]
        _, _, h, w = inp.shape
        self.input_h, self.input_w = int(h), int(w)

    def _preprocess(self, bgr: np.ndarray) -> np.ndarray:
        resized = cv2.resize(bgr, (self.input_w, self.input_h), interpolation=cv2.INTER_LINEAR)
        blob = resized.transpose(2, 0, 1)[np.newaxis, ...].astype(np.float32)
        return blob

    def _postprocess_adas(self, logits: np.ndarray, orig_w: int, orig_h: int) -> Detection:
        # logits: (1, 4, H, W) class scores
        if logits.ndim == 4:
            cls_map = np.argmax(logits[0], axis=0)
        else:
            cls_map = (logits[0, 0] > 0.5).astype(np.uint8) * ADAS_CURB
        curb = (cls_map == ADAS_CURB).astype(np.uint8)
        # Also use road|curb boundary in lower half when curb channel is sparse
        road = (cls_map == ADAS_ROAD).astype(np.uint8)
        h = curb.shape[0]
        roi = curb[int(h * 0.45) :, :]
        if roi.sum() < 30:
            edge = cv2.Canny((road * 255).astype(np.uint8), 50, 150)
            roi = edge[int(h * 0.45) :, :]
        ys, xs = np.where(roi > 0)
        if len(xs) < 40:
            return Detection(False, 0, 0, 0, 0, 0.0)
        x0, x1 = int(xs.min()), int(xs.max())
        y0, y1 = int(ys.min()) + int(h * 0.45), int(ys.max()) + int(h * 0.45)
        # Map model coords → original frame
        sx, sy = orig_w / self.input_w, orig_h / self.input_h
        x0, x1 = int(x0 * sx), int(x1 * sx)
        y0, y1 = int(y0 * sy), int(y1 * sy)
        x0, x1 = max(0, min(x0, orig_w - 1)), max(x0 + 1, min(x1, orig_w))
        y0, y1 = max(0, min(y0, orig_h - 1)), max(y0 + 1, min(y1, orig_h))
        conf = float(roi.mean())
        return Detection(True, x0, y0, x1, y1, conf)

    def detect(self, bgr: np.ndarray) -> Detection:
        oh, ow = bgr.shape[:2]
        blob = self._preprocess(bgr)
        if self._ov_compiled is not None:
            out = self._ov_compiled(blob)[0]
        else:
            name = self._onnx.get_inputs()[0].name
            out = self._onnx.run(None, {name: blob})[0]
        return self._postprocess_adas(out, ow, oh)
