package com.example.seethrough.wp5.curboverlay.model

/**
 * Middleware zone classification. Levels match [com.middleware.panorama.wp5.Zone] on the server.
 * NONE must never be shown as a safe reading.
 */
enum class CurbZoneLevel(val level: Int) {
    NONE(0),
    SAFE(1),
    WARNING(2),
    DANGER(3),
    ;

    companion object {
        fun fromName(raw: String?): CurbZoneLevel =
            when (raw?.uppercase()) {
                "DANGER" -> DANGER
                "WARNING" -> WARNING
                "SAFE" -> SAFE
                "NONE" -> NONE
                else -> NONE
            }

        /** Worse = more dangerous. NONE never wins over a real reading. */
        fun worst(a: CurbZoneLevel, b: CurbZoneLevel): CurbZoneLevel {
            if (a == NONE) return b
            if (b == NONE) return a
            return if (a.level >= b.level) a else b
        }
    }
}
