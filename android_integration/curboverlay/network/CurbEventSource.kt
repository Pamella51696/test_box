package com.example.seethrough.wp5.curboverlay.network

/** Transport for raw curb JSON strings (SSE or test fixtures). */
interface CurbEventSource {
    fun start(onCurbJson: (String) -> Unit)
    fun stop()
}

/** In-memory / unit-test source. */
class FakeCurbEventSource(private val payloads: List<String>) : CurbEventSource {
    private var listener: ((String) -> Unit)? = null

    override fun start(onCurbJson: (String) -> Unit) {
        listener = onCurbJson
        payloads.forEach { onCurbJson(it) }
    }

    fun emit(json: String) {
        listener?.invoke(json)
    }

    override fun stop() {
        listener = null
    }
}
