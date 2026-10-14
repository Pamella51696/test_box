package com.example.seethrough.wp5.curboverlay.model

/** Optional panorama placement from middleware `pano` block (for future image-aligned mode). */
data class PanoramaCoords(
    val x: Int?,
    val w: Int?,
    val xNorm: Float?,
    val wNorm: Float?,
)
