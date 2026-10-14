# Integration guide (minimal additive hook)

## 1. Copy package

```text
android_integration/curboverlay/  →  app/src/main/java/<your.package>/curboverlay/
android_integration/tests/      →  app/src/test/java/<your.package>/curboverlay/
android_integration/fixtures/   →  app/src/test/resources/curb_fixtures/
```

## 2. Dependencies (match existing `Wp5Client.kt`)

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```

## 3. Layout XML (example)

```xml
<FrameLayout ...>
    <!-- existing camera / panorama view -->
    <com.your.package.curboverlay.renderer.android.VehicleCurbOverlayView
        android:id="@+id/curbVehicleOverlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />
</FrameLayout>
```

Optional: keep existing `CurbOverlayView` for panorama mode (`showOnPanorama = true`).

## 4. Activity / Fragment hook

```kotlin
val integration = CurbOverlayIntegration.create(
    config = CurbOverlayConfig(
        host = middlewareHost,
        port = 9091,
        enabled = true,
    ),
    scope = lifecycleScope,
    mainDispatcher = Dispatchers.Main,
)

override fun onStart() {
    super.onStart()
    integration.start(findViewById(R.id.curbVehicleOverlay))
}

override fun onStop() {
    integration.stop()
    super.onStop()
}
```

When `config.enabled == false`, **no SSE connection** is opened; screen behavior is unchanged.

## 4b. Coexist with existing `Wp5Client`

If the app already runs `Wp5Client` on `/api/v1/signals`:

- **Option A (recommended):** Feed curb JSON from the existing client into `CurbOverlayController.onCurbJson(data)` and do **not** start `SseCurbEventSource`.
- **Option B:** Use only `curboverlay` SSE with `?topics=curb` and keep `Wp5Client` for steering on a separate connection (two SSE sockets).

## 5. Manual E2E

1. Start middleware with `tools/run-middleware-with-vision.sh` and `-Dwp5.visionTest.enabled=true`.
2. Open `/wp5/viewer` and enable left test curb 0.7 m — note `source: TEST_UI`.
3. On Android, confirm FRONT/REAR/LEFT/RIGHT labels match viewer zones and colours.
4. Stop test updates — sides grey within TTL (~800 ms server + client fallback).

## 6. Acceptance checklist (PR7)

- [ ] SSE updates change four side indicators
- [ ] Colours/distances match WP5 viewer
- [ ] Stale → grey, never “safe” from null distance
- [ ] Steering / wheel rig / camera unchanged when overlay disabled
- [ ] Malformed JSON does not crash UI
