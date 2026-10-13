# PR3: Vision–ultrasonic fusion (Java)

## Goal

One **fused curb zone per logical position** (`LEFT_REAR`, …) using existing SAFE / WARNING / DANGER thresholds. No averaging of incompatible readings. Video pipeline unchanged.

## Enable

```bash
-Dwp5.fusion.enabled=true
-Dwp5.fusion.mode=source_priority   # vision_only | source_priority | fusion
-Dwp5.fusion.ultrasonic.priority=1
-Dwp5.fusion.vision.priority=2
-Dwp5.fusion.conflict.thresholdM=0.30
-Dwp5.fusion.stale.timeoutMs=500
-Dwp5.fusion.calibration.allowSynthetic=false   # dev/test only
```

Default: **fusion off** — PR1/PR2 behaviour (one SSE zone per sensor `id`).

## Ingest

Unchanged: `POST /api/v1/ingest/range`. Optional fields for fusion:

- `distanceValid`: `false` → ignored for fusion
- `calibrationType`: `synthetic` → rejected unless `allowSynthetic=true`
- `confidence`: optional (vision)

Vision and ultrasonic remain **separate ids** (e.g. `VISION_LEFT_REAR` vs `LEFT_REAR`); fusion merges by **`pos`**.

## SSE extension

Each zone may include:

```json
"fusion": {
  "mode": "source_priority",
  "selectedSource": "ULTRASONIC",
  "conflict": true
}
```

Top-level `source` / `distanceM` / `zone` / `color` / `pano` remain the **selected** values for the viewer and Android.

## Tests

`mvn test -Dtest=CurbFusionTest`

Synthetic video distances are for **software path validation only**; field accuracy is PR4.
