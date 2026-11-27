package com.example.camfocus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * Displays decoded CHDK live view frames scaled to the camera's physical LCD aspect ratio
 * (e.g. 4:3 for SX130 IS's 720x240 buffer) using tear-free double-buffered Bitmap swapping.
 */
class ViewfinderView(context: Context) : View(context) {
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private val hudBgPaint = Paint().apply {
        color = 0x88000000.toInt()
    }

    private val lock = Any()
    private var frontBmp: Bitmap? = null
    private var backBmp: Bitmap? = null
    private var aspectRatio = 4f / 3f
    private var hudText = ""

    private val srcRect = Rect()
    private val dstRect = RectF()

    init {
        setBackgroundColor(Color.BLACK)
        keepScreenOn = true
    }

    /** Copies [pixels] into the off-screen back bitmap, swaps buffers, and schedules redraw. */
    fun submitFrame(pixels: IntArray, width: Int, height: Int, aspect: Float, hud: String) {
        var b = backBmp
        if (b == null || b.width != width || b.height != height) {
            b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        b.setPixels(pixels, 0, width, 0, 0, width, height)

        synchronized(lock) {
            backBmp = frontBmp
            frontBmp = b
            aspectRatio = aspect
            hudText = hud
        }
        postInvalidate()
    }

    fun setHud(text: String) {
        synchronized(lock) { hudText = text }
        postInvalidate()
    }

    fun clearFrame() {
        synchronized(lock) {
            frontBmp = null
            hudText = ""
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp: Bitmap?
        val ar: Float
        val hud: String
        synchronized(lock) {
            bmp = frontBmp
            ar = aspectRatio
            hud = hudText
        }

        if (bmp != null && width > 0 && height > 0) {
            val viewW = width.toFloat()
            val viewH = height.toFloat()
            val drawW: Float
            val drawH: Float
            if (viewW / viewH > ar) {
                drawH = viewH
                drawW = drawH * ar
            } else {
                drawW = viewW
                drawH = drawW / ar
            }
            val left = (viewW - drawW) * 0.5f
            val top = (viewH - drawH) * 0.5f
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(bmp, srcRect, dstRect, bmpPaint)
        }

        if (hud.isNotEmpty()) {
            val pad = 16f
            val textW = hudPaint.measureText(hud)
            canvas.drawRoundRect(
                16f, 16f, 16f + textW + pad * 2, 16f + 46f, 10f, 10f, hudBgPaint
            )
            canvas.drawText(hud, 16f + pad, 16f + 34f, hudPaint)
        }
    }
}
