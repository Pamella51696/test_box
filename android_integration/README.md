# Android integration — PR7 Curb Overlay Adapter Package

Standalone **additive** package for Android apps consuming WP5 middleware curb SSE.  
Does **not** modify `android/`, Java middleware, or vision sidecar.

| Doc | Purpose |
|-----|---------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Design, JSON contract, aggregation rules |
| [INTEGRATION.md](INTEGRATION.md) | Copy-in steps and minimal app hook |
| [fixtures/](fixtures/) | Real-schema JSON samples (from `docs/WP5_SIGNAL_API.md` + PR6 `TEST_UI`) |

## Package layout

```
android_integration/curboverlay/
  model/          CurbSide, CurbZone, CurbState
  parser/         CurbJsonParser (pure JSON)
  mapper/         CurbZoneMapper → four sides
  network/        CurbEventSource, SseCurbEventSource
  controller/     CurbOverlayController
  renderer/       CurbOverlayRenderer + android/VehicleCurbOverlayView (4-side HUD)
  CurbOverlayConfig.kt
  integration/    CurbOverlayIntegration.kt (single public hook)
```

## Middleware (unchanged)

- SSE: `GET /api/v1/signals` (event type `curb`)
- Schema: `docs/WP5_SIGNAL_API.md` §3
- Sources: `ULTRASONIC`, `VISION`, `TEST_UI` (PR6 test ingest)

## Quick integration

1. Copy `curboverlay/` into `app/src/main/java/<your.package>/curboverlay/`.
2. Add OkHttp SSE dependencies (same as existing `android/Wp5Client.kt`).
3. Call `CurbOverlayIntegration.attach(...)` from your screen (see INTEGRATION.md).

## Tests

Copy `tests/` into `app/src/test/.../curboverlay/` and run with JUnit. Fixtures live under `fixtures/`.

CI / local (parser, mapper, renderer — no Android SDK):

```bash
cd android_integration/jvm && gradle test
```
