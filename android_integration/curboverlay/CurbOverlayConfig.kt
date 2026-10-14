package com.example.seethrough.wp5.curboverlay

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class CurbOverlayConfig(
    val host: String,
    val port: Int,
    /** When false, no SSE connection and controller stays in [com.example.seethrough.wp5.curboverlay.model.CurbState.empty]. */
    val enabled: Boolean = true,
    /** SSE path; default WP5 signals stream. */
    val signalsPath: String = "/api/v1/signals",
    /** Prefer `?topics=curb` when the server supports topic filtering. */
    val curbTopicOnly: Boolean = true,
    /** Client-side grey-out if `now - ts` exceeds this and middleware did not already mark NONE. */
    val staleFallbackMs: Long = 800L,
    val initialReconnectMs: Long = 500L,
    val maxReconnectMs: Long = 5_000L,
    val sseIdleReconnectMs: Long = 15_000L,
    /** Reuse app OkHttp to avoid a second connection pool when sharing with [com.example.seethrough.wp5.Wp5Client]. */
    val httpClient: OkHttpClient? = null,
) {
    fun buildHttpClient(): OkHttpClient =
        httpClient ?: OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    fun signalsUrl(): String {
        val base = "http://$host:$port$signalsPath"
        return if (curbTopicOnly) "$base?topics=curb" else base
    }
}
