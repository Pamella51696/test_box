package com.example.seethrough.wp5.curboverlay.network

import com.example.seethrough.wp5.curboverlay.CurbOverlayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * OkHttp SSE client for WP5 curb events. Parses only event type `curb`; ignores steering and ping comments.
 */
class SseCurbEventSource(
    private val config: CurbOverlayConfig,
    private val scope: CoroutineScope,
) : CurbEventSource {

    private val http = config.buildHttpClient()
    private var eventSource: EventSource? = null
    private var watchdog: Job? = null
    private var listener: ((String) -> Unit)? = null
    private var stopped = false
    private var retryMs = config.initialReconnectMs
    @Volatile private var lastRxMs = 0L

    override fun start(onCurbJson: (String) -> Unit) {
        if (!config.enabled) return
        stopped = false
        listener = onCurbJson
        open()
    }

    override fun stop() {
        stopped = true
        watchdog?.cancel()
        eventSource?.cancel()
        listener = null
    }

    private fun open() {
        eventSource?.cancel()
        val req = Request.Builder().url(config.signalsUrl()).build()
        eventSource = EventSources.createFactory(http).newEventSource(req, object : EventSourceListener() {
            override fun onOpen(es: EventSource, response: Response) {
                retryMs = config.initialReconnectMs
                lastRxMs = System.currentTimeMillis()
            }

            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                lastRxMs = System.currentTimeMillis()
                if (type == "curb") {
                    try {
                        listener?.invoke(data)
                    } catch (_: Exception) {
                        // never crash SSE thread
                    }
                }
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                scheduleReconnect()
            }

            override fun onClosed(es: EventSource) {
                scheduleReconnect()
            }
        })

        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive && !stopped) {
                delay(5_000)
                if (System.currentTimeMillis() - lastRxMs > config.sseIdleReconnectMs) {
                    open()
                    return@launch
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        scope.launch {
            delay(retryMs)
            retryMs = (retryMs * 2).coerceAtMost(config.maxReconnectMs)
            if (!stopped) open()
        }
    }
}
