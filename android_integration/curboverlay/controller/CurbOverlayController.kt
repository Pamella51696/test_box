package com.example.seethrough.wp5.curboverlay.controller

import com.example.seethrough.wp5.curboverlay.CurbOverlayConfig
import com.example.seethrough.wp5.curboverlay.mapper.CurbZoneMapper
import com.example.seethrough.wp5.curboverlay.model.CurbState
import com.example.seethrough.wp5.curboverlay.network.CurbEventSource
import com.example.seethrough.wp5.curboverlay.network.SseCurbEventSource
import com.example.seethrough.wp5.curboverlay.parser.CurbJsonParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates parsing, side mapping, freshness, and UI-thread state publication.
 * Independent of steering / wheel-alignment modules.
 */
class CurbOverlayController(
    private val config: CurbOverlayConfig,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher,
    private val parser: CurbJsonParser = CurbJsonParser(),
    private val mapper: CurbZoneMapper = CurbZoneMapper(),
    private val eventSourceFactory: (() -> CurbEventSource)? = null,
) {
    private val _state = MutableStateFlow(CurbState.empty())
    val state: StateFlow<CurbState> = _state.asStateFlow()

    private var source: CurbEventSource? = null
    private var staleJob: Job? = null
    @Volatile private var lastEvent: com.example.seethrough.wp5.curboverlay.parser.ParsedCurbEvent? = null

    fun start(externalSource: CurbEventSource? = null) {
        if (!config.enabled) return
        stop()
        val src = externalSource ?: eventSourceFactory?.invoke()
            ?: SseCurbEventSource(config, scope)
        source = src
        src.start { json -> onCurbJson(json) }
        staleJob = scope.launch(ioDispatcher) {
            while (true) {
                kotlinx.coroutines.delay(250)
                refreshStaleIfNeeded()
            }
        }
    }

    fun stop() {
        staleJob?.cancel()
        source?.stop()
        source = null
    }

    /** Feed curb JSON from an existing [com.example.seethrough.wp5.Wp5Client] (Option A). */
    fun onCurbJson(json: String) {
        scope.launch(ioDispatcher) {
            val parsed = parser.parseCurbEvent(json) ?: return@launch
            lastEvent = parsed
            val state = mapper.toCurbState(
                parsed,
                receivedAtMs = System.currentTimeMillis(),
                applyClientStale = false,
                staleFallbackMs = config.staleFallbackMs,
            )
            withContext(mainDispatcher) {
                _state.value = state
            }
        }
    }

    private suspend fun refreshStaleIfNeeded() {
        val event = lastEvent ?: return
        val now = System.currentTimeMillis()
        if (event.timestampMs > 0 && now - event.timestampMs > config.staleFallbackMs) {
            val stale = mapper.toCurbState(
                event,
                receivedAtMs = now,
                applyClientStale = true,
                staleFallbackMs = config.staleFallbackMs,
            )
            withContext(mainDispatcher) {
                if (_state.value.seq == stale.seq) _state.value = stale
            }
        }
    }
}
