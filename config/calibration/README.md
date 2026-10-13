# Camera calibration (PR2)

No measured calibration ships with the repo. PR1 uses **assumed** FOV models inside `VideoStreamingServer` only.

## File layout (one per panorama camera name)

```
config/calibration/
  front.yaml
  left.yaml
  right.yaml
  rear.yaml
  vehicle_frame.yaml
```

## `vehicle_frame.yaml` (example shape)

```yaml
frame: rear_axle
axes:
  forward: +x
  left: +y
  up: +z
ground_plane_z_m: 0.0
# Reference point for "distance to curb" (e.g. body edge mid-ship)
distance_reference: lateral_body_edge
```

## Per-camera `left.yaml` (example shape — values are placeholders)

```yaml
camera_name: left
image:
  width: 640
  height: 360
  # Pinhole model of the *rectified* image sent to stitch and vision (matches Java OUTPUT 90° crop)
  fx: 0.0   # REQUIRED: fill from measurement or Java equidistantK/pinholeK export
  fy: 0.0
  cx: 320.0
  cy: 180.0
extrinsics:
  # Camera centre in vehicle frame (metres), rotation camera→vehicle (degrees or 3x3 R)
  t_m: [0.0, 0.0, 0.0]
  r_ypr_deg: [0.0, 0.0, 0.0]
valid: false   # estimator must not publish distanceM while false
```

Set `valid: true` only after verification.

## Relation to Java

`FisheyeUndistorter` builds K from `INPUT_FOV_DEG=150`, `OUTPUT_FOV_DEG=90` on 640×360. For a first **synthetic** PR2 dev profile (not for vehicle sign-off), you can copy the fx/fy that Java computes:

- `half = rad(90/2)`, `f = (640/2) / tan(half)` → fx ≈ fy ≈ **320** (for 90° horizontal on 640 width).

Replace with measured values before acceptance tests.
