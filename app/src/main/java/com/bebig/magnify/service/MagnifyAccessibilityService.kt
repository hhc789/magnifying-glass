package com.bebig.magnify.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.MagnificationController
import android.accessibilityservice.MagnificationConfig
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.bebig.magnify.util.PreferenceHelper

class MagnifyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MagnifySvc"
        private const val DEBOUNCE_MS = 300L
    }

    private var magnificationController: MagnificationController? = null
    private var floatingButtonManager: FloatingButtonManager? = null
    private var screenshotMagnifier: ScreenshotMagnifier? = null
    private var state: MagnificationState = MagnificationState.Idle
    private var lastToggleTime = 0L
    private var useScreenshotFallback = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Handler(Looper.getMainLooper()).post {
                floatingButtonManager?.ensureAttached()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            state = MagnificationState.Unsupported
            Log.w(TAG, "API < 28 — unsupported")
            Toast.makeText(this, "设备不支持 (需要 Android 9+)", Toast.LENGTH_LONG).show()
            return
        }

        try {
            magnificationController = getMagnificationController()
            Log.d(TAG, "magnificationController obtained: $magnificationController")
            state = MagnificationState.Idle

            // Always use screenshot fallback — MagnificationController is a broken
            // stub on MuMu and many OEM ROMs (all write methods return false).
            useScreenshotFallback = true
            Log.d(TAG, "Using screenshot fallback mode (system magnification bypassed)")

            Toast.makeText(this, "放大镜服务已启动", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            state = MagnificationState.Unsupported
            Log.e(TAG, "Failed to get magnification controller: ${e.message}", e)
            Toast.makeText(this, "获取放大控制器失败: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        initFloatingButton()
    }

    override fun onRebind(intent: android.content.Intent?) {
        super.onRebind(intent)
        Log.d(TAG, "onRebind")
        if (floatingButtonManager == null) {
            initFloatingButton()
        }
    }

    private fun initFloatingButton() {
        if (floatingButtonManager != null) return
        Log.d(TAG, "initFloatingButton")
        try {
            floatingButtonManager = FloatingButtonManager(this)
            floatingButtonManager?.onToggleCallback = { displayId -> toggleMagnification(displayId) }
            floatingButtonManager?.show()
        } catch (e: Exception) {
            floatingButtonManager = null
            Log.e(TAG, "initFloatingButton failed: ${e.message}", e)
            Toast.makeText(this, "初始化悬浮按钮失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.d(TAG, "onUnbind — returning true (keep button)")
        return true
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        destroy()
        super.onDestroy()
    }

    private fun destroy() {
        try { floatingButtonManager?.remove() } catch (_: Exception) {}
        floatingButtonManager = null
        try { screenshotMagnifier?.remove() } catch (_: Exception) {}
        screenshotMagnifier = null
        magnificationController = null
    }

    fun toggleMagnification(displayId: Int = 0) {
        val now = System.currentTimeMillis()
        if (now - lastToggleTime < DEBOUNCE_MS) {
            Log.d(TAG, "toggleMagnification debounced")
            return
        }
        lastToggleTime = now

        if (state is MagnificationState.Unsupported) {
            Log.w(TAG, "toggleMagnification: state is Unsupported")
            return
        }

        // Prevent starting a new capture while one is already in flight
        if (ScreenCaptureActivity.isCapturing) {
            Log.d(TAG, "toggleMagnification: capture in flight, ignoring")
            return
        }

        Log.d(TAG, "toggleMagnification: current state=$state, useScreenshotFallback=$useScreenshotFallback, displayId=$displayId")

        try {
            if (state is MagnificationState.Idle) {
                if (useScreenshotFallback) {
                    enableScreenshotMagnification(displayId)
                } else {
                    val controller = magnificationController ?: run {
                        Log.w(TAG, "toggleMagnification: controller is null, switching to screenshot fallback")
                        useScreenshotFallback = true
                        enableScreenshotMagnification(displayId)
                        return
                    }
                    enableMagnification(controller, displayId)
                }
            } else {
                disableMagnification()
            }
        } catch (e: Exception) {
            Log.e(TAG, "toggleMagnification failed: ${e.message}", e)
            Toast.makeText(this, "切换放大失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enableScreenshotMagnification(displayId: Int) {
        val scale = PreferenceHelper(this).scale
        Log.d(TAG, "enableScreenshotMagnification: scale=$scale, displayId=$displayId")
        if (screenshotMagnifier == null) {
            screenshotMagnifier = ScreenshotMagnifier(this) {
                state = MagnificationState.Idle
                floatingButtonManager?.updateIcon(false)
                Log.d(TAG, "Screenshot overlay dismissed by user tap")
            }
        }
        screenshotMagnifier?.show(scale, displayId)
        state = MagnificationState.Magnifying(scale)
        floatingButtonManager?.updateIcon(true)
        Toast.makeText(this, "已放大 ${scale}x (截图模式)", Toast.LENGTH_SHORT).show()
    }

    private fun disableMagnification() {
        // Cancel any in-flight MediaProjection capture
        if (ScreenCaptureActivity.isCapturing) {
            Log.d(TAG, "disableMagnification: cancelling in-flight capture")
            ScreenCaptureActivity.onCaptured = null
            ScreenCaptureActivity.isCapturing = false
        }

        if (useScreenshotFallback || screenshotMagnifier?.isShowing == true) {
            screenshotMagnifier?.remove()
            state = MagnificationState.Idle
            floatingButtonManager?.updateIcon(false)
            Toast.makeText(this, "已还原", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "disableMagnification: screenshot overlay removed")
            return
        }

        val controller = magnificationController ?: return
        disableMagnificationSystem(controller)
    }

    @Suppress("DEPRECATION", "NewApi")
    private fun enableMagnification(controller: MagnificationController, displayId: Int) {
        val scale = PreferenceHelper(this).scale
        Log.d(TAG, "enableMagnification: target scale=$scale, centerX=${controller.centerX}, centerY=${controller.centerY}")

        // First, test if center change works at all
        var centerSetOk = controller.setCenter(controller.centerX, controller.centerY, false)
        Log.d(TAG, "enableMagnification: setCenter(current) → $centerSetOk")

        val beforeReset = controller.scale
        val resetOk = controller.reset(false)
        val afterReset = controller.scale
        Log.d(TAG, "enableMagnification: reset(false) → $resetOk, beforeReset=$beforeReset, afterReset=$afterReset")

        @Suppress("DEPRECATION")
        var success = controller.setScale(scale, false)
        Log.d(TAG, "enableMagnification: setScale($scale, false) → $success")

        if (!success) {
            // Try with center adjusted
            centerSetOk = controller.setCenter(800f, 450f, false)
            Log.d(TAG, "enableMagnification: setCenter(800,450) → $centerSetOk")
            @Suppress("DEPRECATION")
            success = controller.setScale(scale, false)
            Log.d(TAG, "enableMagnification: setScale($scale, false) after center → $success")
        }

        if (!success) {
            @Suppress("DEPRECATION")
            success = controller.setScale(scale, true)
            Log.d(TAG, "enableMagnification: setScale($scale, true) → $success")
        }

        if (!success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val config = MagnificationConfig.Builder()
                    .setScale(scale)
                    .build()
                success = controller.setMagnificationConfig(config, false)
                Log.d(TAG, "enableMagnification: setMagnificationConfig → $success")
            } catch (e: Exception) {
                Log.e(TAG, "enableMagnification: setMagnificationConfig threw: ${e.message}", e)
            }
        }

        val finalScale = controller.scale
        Log.d(TAG, "enableMagnification: finalScale=$finalScale, success=$success")

        if (success) {
            state = MagnificationState.Magnifying(scale)
            floatingButtonManager?.updateIcon(true)
            Toast.makeText(this, "已放大 ${scale}x", Toast.LENGTH_SHORT).show()
        } else {
            // System magnification failed — switch to screenshot fallback permanently
            Log.w(TAG, "Magnification FAILED: centerSetOk=$centerSetOk, resetOk=$resetOk, scale=$finalScale. Switching to screenshot fallback.")
            useScreenshotFallback = true
            enableScreenshotMagnification(displayId)
        }
    }

    @Suppress("DEPRECATION", "NewApi")
    private fun disableMagnificationSystem(controller: MagnificationController) {
        Log.d(TAG, "disableMagnification: current scale=${controller.scale}")

        @Suppress("DEPRECATION")
        var success = controller.reset(true)
        Log.d(TAG, "disableMagnification: reset(true) → $success")

        if (!success) {
            @Suppress("DEPRECATION")
            success = controller.reset(false)
            Log.d(TAG, "disableMagnification: reset(false) → $success")
        }

        if (!success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val config = MagnificationConfig.Builder()
                    .setScale(1.0f)
                    .build()
                success = controller.setMagnificationConfig(config, false)
                Log.d(TAG, "disableMagnification: setMagnificationConfig → $success")
            } catch (e: Exception) {
                Log.e(TAG, "disableMagnification: setMagnificationConfig threw: ${e.message}", e)
            }
        }

        Log.d(TAG, "disableMagnification: final scale=${controller.scale}, success=$success")

        if (success) {
            state = MagnificationState.Idle
            floatingButtonManager?.updateIcon(false)
            Toast.makeText(this, "已还原", Toast.LENGTH_SHORT).show()
        }
    }
}
