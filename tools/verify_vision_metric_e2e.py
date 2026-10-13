#!/usr/bin/env python3
"""PR2 Task 5–6: confirm vision overlay API + curb SSE with source VISION."""
from __future__ import annotations

import argparse
import json
import sys
import threading
import time
import urllib.error
import urllib.request


def sse_collect(url: str, seconds: float, bucket: list) -> None:
    req = urllib.request.Request(url, headers={"Accept": "text/event-stream"})
    stop = time.time() + seconds

    def on_event(ev: str, payload: str) -> None:
        if ev == "curb":
            bucket.append(json.loads(payload))

    with urllib.request.urlopen(req, timeout=seconds + 10) as r:
        ev, data = None, []
        while time.time() < stop:
            raw = r.readline()
            if not raw:
                break
            line = raw.decode("utf-8").rstrip("\r\n")
            if line.startswith(":"):
                continue
            if line == "":
                if ev and data:
                    on_event(ev, "\n".join(data))
                ev, data = None, []
            elif line.startswith("event:"):
                ev = line[6:].strip()
            elif line.startswith("data:"):
                data.append(line[5:].strip())


def stitch_consumer(base: str, stop: threading.Event) -> None:
    """Hold /stitch open so Java runs VisionFrameTap (frames only flow during MJPEG clients)."""
    req = urllib.request.Request(base + "/stitch")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            while not stop.is_set():
                if not r.read(8192):
                    break
    except Exception:
        pass


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=9091)
    ap.add_argument("--seconds", type=float, default=25.0)
    ap.add_argument("--no-stitch", action="store_true", help="skip /stitch consumer (if already running)")
    args = ap.parse_args()
    base = f"http://{args.host}:{args.port}"
    fails = []

    stop_stitch = threading.Event()
    if not args.no_stitch:
        threading.Thread(target=stitch_consumer, args=(base, stop_stitch), daemon=True).start()
        time.sleep(1.5)

    def check(ok: bool, msg: str) -> None:
        print(("PASS" if ok else "FAIL"), msg)
        if not ok:
            fails.append(msg)

    try:
        vh = json.load(urllib.request.urlopen(base + "/api/v1/vision/health", timeout=3))
        check(vh.get("ok") is True, "vision health ok")
    except Exception as e:
        check(False, f"vision health: {e}")

    try:
        sh = json.load(
            urllib.request.urlopen("http://127.0.0.1:8765/health", timeout=3)
        )
        check(sh.get("mode") in ("both", "metric"), f"sidecar mode={sh.get('mode')}")
        check(sh.get("allowSynthetic") is True, "sidecar allowSynthetic=true")
        prof = sh.get("calibrationProfile")
        check(prof == "synthetic-dev", f"sidecar calibrationProfile={prof}")
    except Exception as e:
        check(False, f"sidecar health: {e}")

    curb_msgs: list = []
    th = threading.Thread(
        target=sse_collect,
        args=(base + "/api/v1/signals", args.seconds, curb_msgs),
        daemon=True,
    )
    th.start()
    time.sleep(args.seconds)
    th.join(timeout=2)
    stop_stitch.set()

    check(len(curb_msgs) > 0, f"received {len(curb_msgs)} curb SSE message(s)")

    vision_hits = 0
    vision_zones = []
    for msg in curb_msgs:
        for z in msg.get("zones", []):
            if str(z.get("source", "")).upper() == "VISION":
                vision_hits += 1
                vision_zones.append(z)

    if vision_hits == 0:
        try:
            curb = json.load(urllib.request.urlopen(base + "/api/v1/curb", timeout=3))
            for z in curb.get("zones", []):
                if str(z.get("source", "")).upper() == "VISION":
                    vision_hits += 1
                    vision_zones.append(z)
        except Exception:
            pass
    check(vision_hits > 0, f"curb stream/API includes source=VISION ({vision_hits} zone reading(s))")
    if vision_zones:
        sample = vision_zones[-1]
        # CurbModel omits distanceM when reading is beyond maxRangeM (still classifies zone colour).
        has_metric = sample.get("distanceM") is not None
        has_zone = sample.get("zone") in ("SAFE", "WARNING", "DANGER")
        check(has_metric or has_zone, "VISION zone has distanceM or classified zone colour")
        check(sample.get("kind") == "CURB", "VISION zone kind=CURB")
        print("  sample VISION zone:", json.dumps(sample, indent=2)[:500])

    det = None
    for _ in range(10):
        try:
            with urllib.request.urlopen(base + "/api/v1/vision/detections", timeout=2) as r:
                if r.status != 204:
                    det = json.load(r)
                    break
        except urllib.error.HTTPError as e:
            if e.code == 204:
                time.sleep(1)
                continue
        except Exception:
            time.sleep(1)
    if det:
        n = len(det.get("detections") or [])
        check(n > 0, f"GET /api/v1/vision/detections has {n} detection(s) (magenta overlay)")
        has_contour = any(d.get("contour") for d in det.get("detections", []))
        check(has_contour, "detection includes panorama contour for overlay")
    else:
        check(
            False,
            "no vision detections yet (NN may not see curb in feed — check sidecar logs / confidence)",
        )

    print("\nSUMMARY:", len(fails), "failed")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
