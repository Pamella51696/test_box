package com.example.seethrough.wp5.curboverlay.renderer.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.seethrough.wp5.curboverlay.model.CurbSide
import com.example.seethrough.wp5.curboverlay.model.CurbState
import com.example.seethrough.wp5.curboverlay.renderer.CurbOverlayRenderer

/**
 * Transparent four-side vehicle HUD (PR7 default). Does not modify camera frames.
 * Lay over the existing screen (e.g. top child in a [android.widget.FrameLayout]).
 */
class VehicleCurbOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var state: CurbState? = null
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    private val dp = resources.displayMetrics.density
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 11f * dp
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 13f * dp
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val carPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
        color = Color.argb(120, 255, 255, 255)
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val s = state ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val carW = w * 0.22f
        val carH = h * 0.28f
        rect.set(cx - carW / 2, cy - carH / 2, cx + carW / 2, cy + carH / 2)
        canvas.drawRoundRect(rect, 8f * dp, 8f * dp, carPaint)

        drawSide(canvas, s.zone(CurbSide.FRONT), cx, cy - carH / 2 - 36f * dp)
        drawSide(canvas, s.zone(CurbSide.REAR), cx, cy + carH / 2 + 48f * dp)
        drawSide(canvas, s.zone(CurbSide.LEFT), cx - carW / 2 - 56f * dp, cy)
        drawSide(canvas, s.zone(CurbSide.RIGHT), cx + carW / 2 + 56f * dp, cy)
    }

    private fun drawSide(canvas: Canvas, zone: com.example.seethrough.wp5.curboverlay.model.CurbZone, x: Float, y: Float) {
        val padH = 10f * dp
        val padV = 6f * dp
        val title = CurbOverlayRenderer.sideTitle(zone.side)
        val value = CurbOverlayRenderer.label(zone)
        val sub = CurbOverlayRenderer.subtitle(zone)

        val tw = maxOf(titlePaint.measureText(title), valuePaint.measureText(value), titlePaint.measureText(sub))
        val boxW = tw + padH * 2
        val boxH = 44f * dp
        rect.set(x - boxW / 2, y - boxH / 2, x + boxW / 2, y + boxH / 2)

        pill.color = CurbOverlayRenderer.indicatorColor(zone)
        pill.alpha = 220
        canvas.drawRoundRect(rect, 6f * dp, 6f * dp, pill)

        canvas.drawText(title, x, y - 10f * dp, titlePaint)
        canvas.drawText(value, x, y + 4f * dp, valuePaint)
        titlePaint.textSize = 9f * dp
        canvas.drawText(sub, x, y + 18f * dp, titlePaint)
        titlePaint.textSize = 11f * dp
    }
}
