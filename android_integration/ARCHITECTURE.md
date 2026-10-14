# PR7 architecture — Curb Overlay adapter

## Principle

The middleware owns zone math (thresholds, hysteresis, stale `NONE`). Android **displays** middleware `zone` and `color` fields; it does not re-classify distances unless the event omits `zone` (then optional fallback using `thresholds` from the same JSON).

```
VideoStreamingServer / Wp5Service
        │  SSE event: curb
        ▼
SseCurbEventSource  (OkHttp, background thread)
        ▼
CurbJsonParser      (pure, unit-tested)
        ▼
CurbZoneMapper      → CurbState (FRONT, REAR, LEFT, RIGHT)
        ▼
CurbOverlayController (freshness, Main dispatcher)
        ▼
VehicleCurbOverlayView (transparent layer, UI thread)
```

## Discovered Android baseline (this repo)

| Existing file | Role | PR7 relationship |
|---------------|------|------------------|
| `android/Wp5Client.kt` | SSE + parse steering/curb | **Not modified**; `SseCurbEventSource` mirrors curb path only |
| `android/CurbOverlayView.kt` | Panorama strip zones (`xNorm`/`wNorm`) | **Not modified**; PR7 adds **vehicle-view** overlay |
| `android/WheelRig.kt` | Steering / GLB bindings | **No dependency** from `curboverlay` |

**Smallest integration point:** a `FrameLayout` (or Compose `Box`) that already hosts the camera/panorama: add `renderer.android.VehicleCurbOverlayView` as the top child and wire `CurbOverlayController.state` to it (or use `CurbOverlayIntegration.attach`).

## JSON contract (actual middleware)

Envelope (from `CurbModel.snapshot` / `docs/WP5_SIGNAL_API.md`):

- `type`, `v`, `seq`, `ts`
- `panorama.width`, `panorama.height`
- `thresholds.dangerM`, `thresholds.warningM`
- `zones[]`: `id`, `pos`, `kind`, `source`, `distanceM` (nullable), `zone`, `level`, `color`, `camera`, `pano{x,w,xNorm,wNorm}`
- `summary`: `worstZone`, `nearestDistanceM`, `leftWorst`, `rightWorst`, …
- Optional fusion (PR3): `fusion.mode`, `selectedSource` per zone when fusion enabled

Only entries with `kind == "CURB"` participate in curb overlay (matches WP5 viewer).

### Side mapping

| Middleware `pos` | `CurbSide` |
|------------------|------------|
| FRONT_LEFT, FRONT_CENTER, FRONT_RIGHT | FRONT |
| REAR_LEFT, REAR_CENTER, REAR_RIGHT | REAR |
| LEFT_FRONT, LEFT_REAR | LEFT |
| RIGHT_FRONT, RIGHT_REAR | RIGHT |

### Aggregation (deterministic)

When multiple CURB zones map to one side:

1. **Zone severity:** keep the **worst** `level` (`DANGER` > `WARNING` > `SAFE` > `NONE`).
2. **Distance label:** among non-clear readings (`distanceM` not null), show the **minimum** distance (nearest curb).
3. **Source:** source of the zone that won severity; if tie, prefer `VISION` > `TEST_UI` > `ULTRASONIC` for display only.
4. **Validity:** `valid = false` if winning zone is `NONE` or `distanceM` is null.

Do not treat missing distance as `0` m.

### Freshness

1. Prefer middleware `zone == NONE` (stale).
2. Client fallback: `now - ts > CurbOverlayConfig.staleFallbackMs` greys the side.

## Rendering modes

| Mode | Class | When |
|------|-------|------|
| Vehicle-view (PR7 default) | `VehicleCurbOverlayView` | Four labels around vehicle outline |
| Panorama strips (existing) | `android/CurbOverlayView` | When `pano.xNorm` available |

NN magenta contours come from `/api/v1/vision/detections`, not from curb SSE alone — out of scope for PR7 vehicle HUD.

## Networking

- Subscribe to `/api/v1/signals?topics=curb` when supported, else full stream and filter `curb`.
- Bounded reconnect backoff (500 ms → 5 s).
- Lifecycle: `start()` / `stop()` on controller; cancel SSE on destroy.
- Reuse app `OkHttpClient` via `CurbOverlayConfig.httpClient` if already present (avoid duplicate sockets).
