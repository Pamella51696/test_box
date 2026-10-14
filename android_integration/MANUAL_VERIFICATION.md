# PR7 manual verification

## Prerequisites

- Middleware running (`tools/run-middleware-with-vision.sh` or Maven with `-Dwp5.simulate=true`).
- Android app with `curboverlay` package copied per [INTEGRATION.md](INTEGRATION.md).
- Optional PR6: `-Dwp5.visionTest.enabled=true` and `python3 tools/fake_vehicle.py --mode curb-test`.

## Steps

1. Open `http://<host>:9091/wp5/viewer` and note curb zones and colours.
2. On Android, enable `CurbOverlayConfig(enabled = true)` and start integration in `onStart`.
3. Confirm **FRONT / REAR / LEFT / RIGHT** HUD values track viewer distances (± rounding).
4. Set a test side to ~0.4 m — **DANGER** red on that side.
5. Stop ingest or wait >500 ms server stale — side greys (**Unknown**), not green.
6. Set `enabled = false`, restart activity — no SSE from overlay package; steering/camera unchanged.

## Disable overlay

Remove or hide `VehicleCurbOverlayView` and do not call `integration.start()`. Existing `Wp5Client` + `CurbOverlayView` behavior is unchanged.
