package com.bebig.magnify.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * Circular magnifying lens. Displays a zoomed-in region of a source bitmap
 * inside a circular view with a border.
 */
class LensView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "LensView"
    }

    /** The full screenshot bitmap to magnify from. */
    var sourceBitmap: Bitmap? = null

    /** Magnification scale (e.g. 2.0 = 2x). */
    var scale: Float = 2.0f

    /** Center of the lens in screen coordinates. */
    var lensCenterX: Float = 0f
    var lensCenterY: Float = 0f

    /** Screen dimensions for coordinate mapping. */
    var screenWidth: Int = 0
    var screenHeight: Int = 0

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(200, 66, 133, 244) // blue ring
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.argb(60, 0, 0, 0) // dark shadow
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private val matrix = Matrix()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bmp = sourceBitmap ?: return
        if (bmp.isRecycled) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val cx = viewW / 2f
        val cy = viewH / 2f
        val radius = (viewW / 2f) - 4f

        // Clip to circle
        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        // Calculate sample region on source bitmap.
        // lensSize / scale = region on source bitmap that fills the lens
        val sampleW = viewW / scale
        val sampleH = viewH / scale

        // Map lens center (screen coords) → source bitmap coords
        val scaleX = bmp.width.toFloat() / screenWidth.toFloat()
        val scaleY = bmp.height.toFloat() / screenHeight.toFloat()

        val srcCenterX = lensCenterX * scaleX
        val srcCenterY = lensCenterY * scaleY

        val srcLeft = (srcCenterX - sampleW / 2f).coerceIn(0f, bmp.width - sampleW)
        val srcTop = (srcCenterY - sampleH / 2f).coerceIn(0f, bmp.height - sampleH)

        // Matrix: map source region → view bounds
        matrix.reset()
        val srcRect = RectF(srcLeft, srcTop, srcLeft + sampleW, srcTop + sampleH)
        val dstRect = RectF(0f, 0f, viewW, viewH)
        matrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.FILL)

        canvas.drawBitmap(bmp, matrix, bitmapPaint)
        canvas.restore()

        // Border
        canvas.drawCircle(cx, cy, radius, shadowPaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)

        Log.d(TAG, "onDraw: lens=(${lensCenterX.toInt()},${lensCenterY.toInt()}) " +
            "src=(${srcLeft.toInt()},${srcTop.toInt()}) sample=${sampleW.toInt()}x${sampleH.toInt()} " +
            "bmp=${bmp.width}x${bmp.height} screen=${screenWidth}x${screenHeight}")
    }
}
