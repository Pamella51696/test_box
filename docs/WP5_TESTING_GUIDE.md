# WP5 testing guide (PR6 curb-in-frame controls)

## Enable test ingest (off by default)

```bash
java ... -Dwp5.visionTest.enabled=true -Dwp5.visionTest.ttlMs=800 ...
```

`tools/run-middleware-with-vision.sh` sets `visionTest.enabled=true` for local dev.

## Browser viewer (`/wp5/viewer`)

1. Start middleware (with flag above) and open `/wp5/viewer`.
2. Enable **Drive from this page**.
3. Under **Curb in the frame (TEST)**, tick a side and move the slider (0.1–2.4 m).
4. Panorama zones should show **`source: TEST_UI`** in the curb SSE debug block.
5. NN overlay (magenta) and vision pills are unchanged.

Test traffic uses:

`POST /api/v1/ingest/vision-test` (alias `/api/v1/ingest/vision`)

**Not** the Python sidecar and **not** `/api/v1/ingest/range`.

## fake_vehicle.py

```bash
# Ultrasonic regression (PR1–PR3)
python3 tools/fake_vehicle.py --mode sensors --scenario park

# Side-based test curb (PR6)
python3 tools/fake_vehicle.py --mode curb-test --left 0.7 --right 0.4 --steer 0
```

Ctrl+C in curb-test mode sends `detected:false` for all sides to clear overrides.

## Source isolation

| Source     | Endpoint                      | Stored channel   |
|------------|-------------------------------|------------------|
| TEST_UI    | `/api/v1/ingest/vision-test`  | `TEST_UI`        |
| VISION     | `/api/v1/ingest/range`        | `VISION`         |
| ULTRASONIC | `/api/v1/ingest/range`        | `ULTRASONIC`     |

With fusion enabled (`-Dwp5.fusion.enabled=true`), active **TEST_UI** wins display for that side until cleared or TTL expires; underlying VISION/ULTRASONIC readings remain stored.

## Regression

```bash
mvn test
cd vision && python3 -m pytest tests/ -q
tools/run-artificial-calibration-ci.sh
```
