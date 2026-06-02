package com.bebig.magnify.service

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView

/**
 * Fullscreen Activity that displays a magnified screenshot.
 * Launched by [ScreenshotMagnifier] instead of a WindowManager overlay,
 * because MuMu's SurfaceFlinger does not composite overlay windows into
 * the hardware framebuffer (they exist in WindowManager but are invisible
 * to root screencap and the user).
 */
class MagnifierOverlayActivity : Activity() {

    companion object {
        private const val TAG = "MagnifierOverlay"

        @Volatile var screenshot: Bitmap? = null
        @Volatile var scale: Float = 2.0f
        @Volatile var onDismissRunnable: Runnable? = null

        /** Launch this Activity on display 0 from a service context. */
        fun start(service: android.app.Service, scale: Float) {
            this.scale = scale
            val intent = Intent(service, MagnifierOverlayActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION)

            val optionsBundle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val opts = android.app.ActivityOptions.makeBasic()
                try {
                    val m = android.app.ActivityOptions::class.java
                        .getDeclaredMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                    m.isAccessible = true
                    m.invoke(opts, 0)
                    Log.d(TAG, "setLaunchDisplayId(0) succeeded via reflection")
                } catch (e: Exception) {
                    Log.w(TAG, "setLaunchDisplayId failed: ${e.message}")
                }
                opts.toBundle()
            } else null

            // Use display 0 context so the Activity lands on the user-visible screen.
            // On MuMu, a Service's startActivity() may route to a phantom display
            // unless we explicitly provide a display-0-bound context.
            val dm = service.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            val display0 = dm.getDisplay(0)
            val ctx = if (display0 != null) service.createDisplayContext(display0) else service

            if (optionsBundle != null) {
                ctx.startActivity(intent, optionsBundle)
            } else {
                ctx.startActivity(intent)
            }
        }
    }

    private var vpX = 0
    private var vpY = 0
    private var srcW = 0
    private var srcH = 0
    private var maxVpX = 0
    private var maxVpY = 0
    private var displayBmp: Bitmap? = null
    private var imageView: ImageView? = null
    private var bmp: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bmp = screenshot
        if (bmp == null || bmp!!.isRecycled) {
            Log.w(TAG, "No valid screenshot, finishing")
            finish()
            return
        }

        val s = scale.coerceIn(1f, 8f)
        val bmpW = bmp!!.width
        val bmpH = bmp!!.height
        val viewW = resources.displayMetrics.widthPixels
        val viewH = resources.displayMetrics.heightPixels

        srcW = (viewW / s).toInt().coerceAtMost(bmpW)
        srcH = (viewH / s).toInt().coerceAtMost(bmpH)
        maxVpX = (bmpW - srcW).coerceAtLeast(0)
        maxVpY = (bmpH - srcH).coerceAtLeast(0)
        vpX = maxVpX / 2
        vpY = maxVpY / 2

        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.FIT_XY
        imageView = iv

        refreshDisplay()
        setContentView(iv)

        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        var downX = 0f
        var downY = 0f
        var hasPanned = false

        iv.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y; hasPanned = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)
                        hasPanned = true
                    if (hasPanned) {
                        vpX = (vpX - (dx / s).toInt()).coerceIn(0, maxVpX)
                        vpY = (vpY - (dy / s).toInt()).coerceIn(0, maxVpY)
                        refreshDisplay()
                        downX = event.x; downY = event.y
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasPanned) {
                        onDismissRunnable?.run()
                        finish()
                    }
                    true
                }
                else -> true
            }
        }

        Log.d(TAG, "Created: bmp=${bmpW}x${bmpH} scale=$s viewport=($vpX,$vpY) ${srcW}x${srcH}")
    }

    private fun refreshDisplay() {
        val src = bmp ?: return
        val old = displayBmp
        displayBmp = Bitmap.createBitmap(src, vpX, vpY, srcW, srcH)
        imageView?.setImageBitmap(displayBmp)
        old?.recycle()
    }

    override fun onDestroy() {
        super.onDestroy()
        displayBmp?.recycle()
        displayBmp = null
        bmp?.recycle()
        bmp = null
        screenshot = null
        Log.d(TAG, "Destroyed")
    }
}
