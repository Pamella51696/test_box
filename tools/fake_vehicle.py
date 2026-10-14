#!/usr/bin/env python3
"""
Fake vehicle gateway: steering + either ultrasonic range ingest or curb-in-frame TEST (PR6).

  python tools/fake_vehicle.py --mode sensors
  python tools/fake_vehicle.py --mode curb-test --left 0.7 --right 0.4

Start the server WITHOUT -Dwp5.simulate=true. For curb-test enable:
  -Dwp5.visionTest.enabled=true
"""
import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.request

SIDES = ("left", "right", "front", "rear")
SIDE_POS = {
    "left": "LEFT_REAR",
    "right": "RIGHT_REAR",
    "front": "FRONT_CENTER",
    "rear": "REAR_CENTER",
}


def post(base, path, obj, token):
    req = urllib.request.Request(
        base + path,
        data=json.dumps(obj).encode(),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    if token:
        req.add_header("X-Api-Key", token)
    with urllib.request.urlopen(req, timeout=2) as r:
        return r.status


def sensor(pos, d, kind=None, height=None):
    if kind is None:
        kind = "CURB" if pos.startswith(("LEFT_", "RIGHT_")) else "OBSTACLE"
    s = {
        "id": pos,
        "pos": pos,
        "distanceM": -1 if d is None else round(d, 3),
        "kind": kind,
        "source": "ULTRASONIC",
    }
    if height is not None:
        s["heightM"] = height
    return s


def smooth(t, a, b):
    return min(1.0, max(0.0, (t - a) / (b - a)))


def parking(t):
    t = t % 30.0
    if t < 4:
        swa = 0
    elif t < 10:
        swa = -450 * smooth(t, 4, 10)
    elif t < 14:
        swa = -450
    elif t < 22:
        swa = -450 + 900 * smooth(t, 14, 22)
    else:
        swa = 450 * (1 - smooth(t, 22, 28))
    rear = 2.5 - 2.2 * smooth(t, 3, 26)
    curb_l = 1.6 - 1.1 * smooth(t, 8, 24)
    s = [
        sensor("REAR_CENTER", rear if rear < 2.5 else None),
        sensor("REAR_LEFT", rear + 0.3 if rear + 0.3 < 2.5 else None),
        sensor("REAR_RIGHT", None),
        sensor("LEFT_REAR", curb_l, "CURB", 0.12),
        sensor("LEFT_FRONT", curb_l + 0.2, "CURB", 0.12),
        sensor("RIGHT_REAR", 1.9),
        sensor("RIGHT_FRONT", None),
    ]
    return swa, "R", 1.5, s


def sweep(t):
    swa = 450 * math.sin(2 * math.pi * t / 12.0)
    rear = 1.35 + 1.15 * math.sin(2 * math.pi * t / 8.0)
    return swa, "R", 1.5, [
        sensor("REAR_CENTER", rear),
        sensor("LEFT_REAR", 0.7 + 0.45 * math.sin(2 * math.pi * t / 6.0 + 1), "CURB", 0.12),
    ]


def vision_test_payload(args):
    sides = []
    for side in SIDES:
        val = getattr(args, side)
        if val is None or val < 0:
            sides.append({"side": side, "detected": False, "distanceM": None, "confidence": 0.0})
        else:
            sides.append({"side": side, "detected": True, "distanceM": round(val, 3), "confidence": 1.0})
    return {"sides": sides}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=9091)
    ap.add_argument("--token", default="")
    ap.add_argument("--rate", type=float, default=10.0)
    ap.add_argument("--mode", choices=["sensors", "curb-test"], default="sensors")
    ap.add_argument("--scenario", choices=["park", "sweep", "static"], default="park")
    ap.add_argument("--duration", type=float, default=0)
    ap.add_argument("--steer", type=float, default=0)
    ap.add_argument("--gear", default="R")
    ap.add_argument("--rear", type=float, default=None)
    ap.add_argument("--curb", type=float, default=None)
    for side in SIDES:
        ap.add_argument("--" + side, type=float, default=None, help="curb-test distance m; omit = off")
    a = ap.parse_args()
    base = "http://%s:%d" % (a.host, a.port)
    print("Sending to", base, "mode:", a.mode, "(Ctrl+C to stop)")
    t0, n = time.time(), 0
    try:
        while a.duration <= 0 or time.time() - t0 < a.duration:
            t = time.time() - t0
            if a.scenario == "park":
                swa, gear, spd, s = parking(t)
            elif a.scenario == "sweep":
                swa, gear, spd, s = sweep(t)
            else:
                swa, gear, spd = a.steer, a.gear, 1.5
                s = [sensor("REAR_CENTER", a.rear), sensor("LEFT_REAR", a.curb, "CURB", 0.12)]
            if a.mode == "curb-test" and a.scenario == "static":
                swa, gear, spd = a.steer, a.gear, 1.5
            post(
                base,
                "/api/v1/ingest/steering",
                {"steeringWheelDeg": round(swa, 2), "gear": gear, "speedKph": spd},
                a.token,
            )
            if a.mode == "sensors":
                post(base, "/api/v1/ingest/range", {"sensors": s}, a.token)
            else:
                post(base, "/api/v1/ingest/vision-test", vision_test_payload(a), a.token)
            n += 1
            if n % int(max(1, a.rate)) == 0:
                print("t=%5.1fs  steering %7.1f deg  mode %s" % (t, swa, a.mode))
            time.sleep(1.0 / a.rate)
    except urllib.error.HTTPError as e:
        sys.stderr.write("HTTP %s from middleware: %s\n" % (e.code, e.read().decode()[:200]))
        sys.exit(1)
    except urllib.error.URLError as e:
        sys.exit("Cannot reach middleware at %s: %s" % (base, e))
    except KeyboardInterrupt:
        if a.mode == "curb-test":
            try:
                off = {"sides": [{"side": s, "detected": False, "distanceM": None, "confidence": 0} for s in SIDES]}
                post(base, "/api/v1/ingest/vision-test", off, a.token)
            except urllib.error.URLError:
                pass
        print("stopped")


if __name__ == "__main__":
    main()
