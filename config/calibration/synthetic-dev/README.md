# Synthetic-dev calibration (PR2 pipeline testing only)

**Do not use for real-world distance claims.** These extrinsics are generic vehicle assumptions, not measurements from your camera feeds.

## Enable in the vision sidecar

1. Set in `vision/config.yaml` (or environment):

   ```yaml
   calibration:
     profile: synthetic-dev
     allow_synthetic: true
   ```

   Or: `VISION_CALIBRATION_PROFILE=synthetic-dev` and `VISION_ALLOW_SYNTHETIC=1`

2. Set `mode: both` or `VISION_MODE=both` for metric ingest.

With `allow_synthetic: false` (default), synthetic YAML files load but **`valid` is forced false** — no `distanceM` ingest (fail-closed).

## Rotation convention

Matches `vision/calibration_loader.py`:

- **Intrinsics**: pinhole on **Java rectified** 640×360 (not raw fisheye).
- **`rotation_model: vehicle_mount_yaw`**: yaw about vehicle +Z (0° = forward), then OpenCV→WP5 mount, then pitch/roll in camera frame.
- **`r_ypr_deg`**: `[yaw, pitch, roll]` in degrees — yaw about vehicle +Z, **pitch about vehicle +Y (positive = nose down)**, roll about camera +X.

## Frame origin

Translations `t_m` are relative to **vehicle center** on the ground plane (`vehicle_frame.yaml`), not rear axle. Distance published to Java is `hypot(x, y)` from that origin on the ground hit point.

Measured production calibration should live in `config/calibration/*.yaml` with `calibration_type: measured` and `valid: true` only after verification.
