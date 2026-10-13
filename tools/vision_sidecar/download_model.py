#!/usr/bin/env python3
"""Download recommended vision models for the curb sidecar."""
import argparse
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MODELS = ROOT / "models"

PRESETS = {
    # Default: Intel Open Model Zoo — 4 classes incl. explicit "curb" (best match for this project).
    "adas-curb": {
        "description": "road-segmentation-adas-0001 (BG, road, curb, mark) — OpenVINO IR",
        "files": {
            "road-segmentation-adas-0001.xml": (
                "https://storage.openvinotoolkit.org/repositories/open_model_zoo/2023.0/"
                "models_bin/1/road-segmentation-adas-0001/FP32/road-segmentation-adas-0001.xml"
            ),
            "road-segmentation-adas-0001.bin": (
                "https://storage.openvinotoolkit.org/repositories/open_model_zoo/2023.0/"
                "models_bin/1/road-segmentation-adas-0001/FP32/road-segmentation-adas-0001.bin"
            ),
        },
    },
}


def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    print("  ->", dest.name, "from", url)
    urllib.request.urlretrieve(url, dest)


def main() -> int:
    ap = argparse.ArgumentParser(description="Download curb-segmentation models for vision_sidecar")
    ap.add_argument("--preset", choices=list(PRESETS), default="adas-curb")
    ap.add_argument("--list", action="store_true", help="List presets and exit")
    args = ap.parse_args()
    if args.list:
        for k, v in PRESETS.items():
            print(k, ":", v["description"])
        return 0
    preset = PRESETS[args.preset]
    print(preset["description"])
    for name, url in preset["files"].items():
        download(url, MODELS / name)
    print("Done. Models in", MODELS)
    print("Run sidecar: VISION_BACKEND=openvino python server.py")
    print("Optional ONNX export: see docs/VISION_CURB_MODELS.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
