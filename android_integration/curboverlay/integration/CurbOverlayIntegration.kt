package com.example.seethrough.wp5.curboverlay.integration

import com.example.seethrough.wp5.curboverlay.CurbOverlayConfig
import com.example.seethrough.wp5.curboverlay.controller.CurbOverlayController
import com.example.seethrough.wp5.curboverlay.network.CurbEventSource
import com.example.seethrough.wp5.curboverlay.renderer.android.VehicleCurbOverlayView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Single public hook: create, [start] with overlay view, [stop] on lifecycle end.
 * When [CurbOverlayConfig.enabled] is false, no network activity and the view stays empty.
 */
class CurbOverlayIntegration private constructor(
    private val controller: CurbOverlayController,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
) {
    private var collectJob: kotlinx.coroutines.Job? = null

    fun start(overlayView: VehicleCurbOverlayView, eventSource: CurbEventSource? = null) {
        collectJob?.cancel()
        controller.start(eventSource)
        collectJob = scope.launch(mainDispatcher) {
            controller.state.collectLatest { overlayView.state = it }
        }
    }

    fun stop() {
        collectJob?.cancel()
        controller.stop()
    }

    /** Option A: push curb JSON from an existing WP5 client instead of opening SSE here. */
    fun onCurbJson(data: String) = controller.onCurbJson(data)

    companion object {
        fun create(
            config: CurbOverlayConfig,
            scope: CoroutineScope,
            mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
            ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): CurbOverlayIntegration {
            val controller = CurbOverlayController(
                config = config,
                scope = scope,
                ioDispatcher = ioDispatcher,
                mainDispatcher = mainDispatcher,
            )
            return CurbOverlayIntegration(controller, scope, mainDispatcher)
        }
    }
}
