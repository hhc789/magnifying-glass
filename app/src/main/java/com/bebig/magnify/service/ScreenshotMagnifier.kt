package com.bebig.magnify.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast


/**
 * Fallback magnifier that uses screenshots when the system MagnificationController
 * is broken (e.g., on MuMu emulator and certain OEM ROMs).
 *
 * Shows a fullscreen zoomed screenshot overlay. User drags to pan, taps to dismiss.
 */
class ScreenshotMagnifier(
    private val service: AccessibilityService,
    private val onDismiss: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "ScreenshotMag"
    }

    private var screenshot: Bitmap? = null
    private var scale: Float = 2.0f

    fun show(scale: Float, displayId: Int = 0) {
        this.scale = scale
        Log.d(TAG, "show() — buttonDisplay=$displayId")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureScreen()
        } else {
            Log.e(TAG, "takeScreenshot requires API 30+")
        }
    }

    /**
     * Capture screen: try root screencap first (works on MuMu), then
     * SurfaceControl reflection, then takeScreenshot API, then MediaProjection.
     */
    private fun captureScreen() {
        // 1) Root screencap — captures full composited screen correctly
        val scBmp = captureViaScreencap()
        if (scBmp != null && scBmp.width > 0 && scBmp.blackPixelRatio() < 0.95f) {
            Log.d(TAG, "Root screencap SUCCESS: ${scBmp.width}x${scBmp.height}")
            saveDiagPng(scBmp, "screencap_capture")
            screenshot?.recycle()
            screenshot = scBmp
            showOverlay()
            return
        }
        scBmp?.recycle()

        // 2) SurfaceControl reflection — works on some devices
        Log.d(TAG, "Screencap failed, trying SurfaceControl reflection")
        val scBitmap = captureViaReflection()
        if (scBitmap != null && scBitmap.width > 0 && scBitmap.blackPixelRatio() < 0.95f) {
            Log.d(TAG, "SurfaceControl SUCCESS: ${scBitmap.width}x${scBitmap.height}")
            screenshot?.recycle()
            screenshot = scBitmap
            showOverlay()
            return
        }
        scBitmap?.recycle()

        // 3) AccessibilityService.takeScreenshot (works on real devices)
        Log.d(TAG, "SurfaceControl failed, trying takeScreenshot displayId=0")
        takeScreenshotForDisplay(0)
    }

    /** Last resort: MediaProjection. Launches transparent Activity for permission. */
    private fun requestMediaProjection() {
        if (ScreenCaptureActivity.isCapturing) {
            Log.d(TAG, "requestMediaProjection: already capturing, ignoring")
            return
        }

        // If no cached permission, tell user to grant via the main app first.
        // Launching the activity from the service context places it on a
        // MuMu [KeepAlive] phantom display the user can't see.
        if (ScreenCaptureActivity.cachedData == null ||
            ScreenCaptureActivity.cachedResultCode != android.app.Activity.RESULT_OK) {
            Log.w(TAG, "requestMediaProjection: no cached permission, asking user to open app")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(service,
                    "请先打开应用并授予屏幕截图权限", Toast.LENGTH_LONG).show()
                onDismiss?.invoke()
            }
            return
        }

        ScreenCaptureActivity.isCapturing = true
        Log.d(TAG, "requestMediaProjection: starting capture activity")
        ScreenCaptureActivity.onCaptured = { bitmap ->
            if (bitmap != null) {
                Log.d(TAG, "MediaProjection captured: ${bitmap.width}x${bitmap.height}")
                screenshot?.recycle()
                screenshot = bitmap
                showOverlay()
            } else {
                Log.w(TAG, "MediaProjection capture failed or denied")
                if (screenshot != null) {
                    showOverlay()
                } else {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(service, "屏幕截图失败", Toast.LENGTH_SHORT).show()
                        onDismiss?.invoke()
                    }
                }
            }
        }
        try {
            val intent = android.content.Intent(service, ScreenCaptureActivity::class.java)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)

            val optionsBundle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val opts = android.app.ActivityOptions.makeBasic()
                try {
                    val method = android.app.ActivityOptions::class.java
                        .getDeclaredMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                    method.isAccessible = true
                    method.invoke(opts, 0)
                } catch (_: Exception) {}
                opts.toBundle()
            } else null

            // Use display 0 context so the Activity lands on the visible screen
            val dm = service.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            val display0 = dm.getDisplay(0)
            val ctx = if (display0 != null) service.createDisplayContext(display0) else service

            if (optionsBundle != null) {
                ctx.startActivity(intent, optionsBundle)
            } else {
                ctx.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ScreenCaptureActivity: ${e.message}", e)
            ScreenCaptureActivity.onCaptured = null
            ScreenCaptureActivity.isCapturing = false
            if (screenshot != null) {
                showOverlay()
            }
        }
    }

    private fun captureViaScreencap(): Bitmap? {
        return try {
            val outFile = java.io.File(service.cacheDir, "screencap_temp.png")
            outFile.delete()

            // Try multiple su binaries — /data/local/tmp/su is a copy of the
            // setuid su with world-readable permissions (4755).
            val suCandidates = listOf(
                "/data/local/tmp/su",
                "/system/xbin/su",
                "/system/bin/su",
                "su"
            )
            var suBinary: String? = null
            for (candidate in suCandidates) {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf(candidate, "-c", "id"))
                    if (p.waitFor() == 0) {
                        suBinary = candidate
                        Log.d(TAG, "captureViaScreencap: su found at $candidate")
                        break
                    }
                } catch (_: Exception) {}
            }
            Log.d(TAG, "captureViaScreencap: suAvailable=${suBinary != null}, suBinary=$suBinary")

            // Try root screencap first, then non-root
            val cmds = if (suBinary != null) {
                listOf(
                    arrayOf(suBinary, "-c", "/system/bin/screencap -p ${outFile.absolutePath}"),
                    arrayOf("/system/bin/screencap", "-p", outFile.absolutePath)
                )
            } else {
                listOf(
                    arrayOf("/system/bin/screencap", "-p", outFile.absolutePath)
                )
            }

            var succeeded = false
            for (cmd in cmds) {
                if (succeeded) break
                val proc = Runtime.getRuntime().exec(cmd, null, null)
                val stderr = proc.errorStream.bufferedReader().use { it.readText() }
                proc.waitFor()
                val exitCode = proc.exitValue()
                Log.d(TAG, "screencap cmd=${cmd.take(2).joinToString(" ")} exit=$exitCode" +
                    " fileLen=${outFile.length()} stderr=${stderr.take(200)}")

                if (exitCode == 0 && outFile.exists() && outFile.length() > 1000) {
                    succeeded = true
                }
            }

            if (succeeded) {
                var bmp = android.graphics.BitmapFactory.decodeFile(outFile.absolutePath)
                outFile.delete()
                if (bmp != null) {
                    Log.d(TAG, "screencap: ${bmp.width}x${bmp.height}")
                    if (bmp.config == Bitmap.Config.HARDWARE) {
                        val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                        bmp.recycle()
                        bmp = copy
                    }
                    bmp
                } else null
            } else {
                Log.w(TAG, "screencap: all attempts failed, fileLen=${outFile.length()}")
                outFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "captureViaScreencap error: ${e.message}")
            null
        }
    }

    private fun captureViaReflection(): Bitmap? {
        return try {
            val scClass = Class.forName("android.view.SurfaceControl")
            // Try all screenshot() overloads — signature varies by Android version
            for (method in scClass.declaredMethods) {
                if (method.name != "screenshot" && method.name != "screenshotToBuffer") continue
                method.isAccessible = true
                val paramTypes = method.parameterTypes
                try {
                    val bmp: Bitmap? = when {
                        // screenshot(Rect, int, int, int) — Android 9-12
                        paramTypes.size == 4 &&
                            paramTypes[0] == android.graphics.Rect::class.java &&
                            paramTypes[1] == Int::class.javaPrimitiveType -> {
                            val dm = android.util.DisplayMetrics()
                            @Suppress("DEPRECATION")
                            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                            wm.defaultDisplay.getRealMetrics(dm)
                            val rect = android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
                            method.invoke(null, rect, dm.widthPixels, dm.heightPixels, 0) as? Bitmap
                        }
                        // screenshot(int, int) — older Android
                        paramTypes.size == 2 &&
                            paramTypes[0] == Int::class.javaPrimitiveType -> {
                            val dm = android.util.DisplayMetrics()
                            @Suppress("DEPRECATION")
                            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                            wm.defaultDisplay.getRealMetrics(dm)
                            method.invoke(null, dm.widthPixels, dm.heightPixels) as? Bitmap
                        }
                        // screenshot(IBinder, ...) — Android 11+
                        paramTypes.size >= 5 &&
                            paramTypes[0] == android.os.IBinder::class.java -> {
                            // Get display token via DisplayListenerHack or SurfaceControl.getBuiltInDisplay
                            val getDisplayMethod = scClass.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType)
                            val displayToken = getDisplayMethod.invoke(null, 0) as? android.os.IBinder
                            if (displayToken != null) {
                                val dm = android.util.DisplayMetrics()
                                @Suppress("DEPRECATION")
                                val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                                wm.defaultDisplay.getRealMetrics(dm)
                                val args = arrayOfNulls<Any?>(paramTypes.size)
                                args[0] = displayToken
                                args[1] = dm.widthPixels
                                args[2] = dm.heightPixels
                                for (i in 3 until args.size) {
                                    args[i] = when (paramTypes[i]) {
                                        Boolean::class.javaPrimitiveType -> false
                                        Int::class.javaPrimitiveType -> 0
                                        else -> null
                                    }
                                }
                                method.invoke(null, *args) as? Bitmap
                            } else null
                        }
                        else -> null
                    }
                    if (bmp != null && bmp.width > 0) {
                        Log.d(TAG, "SurfaceControl.${method.name}(${paramTypes.map{it.simpleName}}) OK: ${bmp.width}x${bmp.height}")
                        if (bmp.config == Bitmap.Config.HARDWARE) {
                            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            bmp.recycle()
                            return copy
                        }
                        return bmp
                    }
                } catch (e: Exception) {
                    // Try next overload
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "captureViaReflection error: ${e.message}")
            null
        }
    }

    private fun takeScreenshotForDisplay(
        screenshotDisplayId: Int,
        retryCount: Int = 0
    ) {
        val maxRetries = 1
        Log.d(TAG, "takeScreenshot: displayId=$screenshotDisplayId, retry=$retryCount")
        service.takeScreenshot(
            screenshotDisplayId,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hwBuffer = result.hardwareBuffer
                    Log.d(TAG, "takeScreenshot SUCCESS: hwBuffer=${hwBuffer.width}x${hwBuffer.height} " +
                        "format=${hwBuffer.format} usage=${hwBuffer.usage}")
                    val hwBmp = Bitmap.wrapHardwareBuffer(
                        hwBuffer,
                        result.colorSpace
                    )
                    if (hwBmp == null) {
                        Log.e(TAG, "wrapHardwareBuffer returned null for display $screenshotDisplayId")
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(service, "截图转换失败", Toast.LENGTH_SHORT).show()
                            onDismiss?.invoke()
                        }
                        return
                    }

                    val swBmp = if (hwBmp.config == Bitmap.Config.HARDWARE) {
                        try {
                            val copy = hwBmp.copy(Bitmap.Config.ARGB_8888, false)
                            hwBmp.recycle()
                            copy
                        } catch (e: Exception) {
                            Log.e(TAG, "HARDWARE -> SOFTWARE copy failed: ${e.message}")
                            hwBmp.recycle()
                            null
                        }
                    } else {
                        hwBmp
                    }

                    if (swBmp == null) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(service, "截图处理失败", Toast.LENGTH_SHORT).show()
                            onDismiss?.invoke()
                        }
                        return
                    }

                    val blackRatio = swBmp.blackPixelRatio()
                    Log.d(TAG, "Screenshot: ${swBmp.width}x${swBmp.height} config=${swBmp.config}, blackRatio=$blackRatio")

                    if (blackRatio > 0.95f && retryCount < maxRetries) {
                        Log.w(TAG, "Screenshot black, retrying (${retryCount + 1}/$maxRetries)...")
                        swBmp.recycle()
                        Handler(Looper.getMainLooper()).postDelayed({
                            takeScreenshotForDisplay(screenshotDisplayId, retryCount + 1)
                        }, 300)
                        return
                    }

                    if (blackRatio > 0.95f) {
                        Log.w(TAG, "Screenshot still black after $maxRetries retries, trying MediaProjection")
                        swBmp.recycle()
                        requestMediaProjection()
                        return
                    }

                    screenshot?.recycle()
                    screenshot = swBmp
                    showOverlay()
                }
                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshot failed for display $screenshotDisplayId, error=$errorCode, retry=$retryCount")
                    if (screenshotDisplayId != 0 && retryCount == 0) {
                        Log.w(TAG, "Retrying with display 0")
                        takeScreenshotForDisplay(0)
                    } else if (retryCount < maxRetries) {
                        Log.w(TAG, "Retrying screenshot (${retryCount + 1}/$maxRetries)...")
                        Handler(Looper.getMainLooper()).postDelayed({
                            takeScreenshotForDisplay(screenshotDisplayId, retryCount + 1)
                        }, 300)
                    } else {
                        Log.w(TAG, "All retries exhausted, trying MediaProjection")
                        requestMediaProjection()
                    }
                }
            }
        )
    }

    /**
     * Returns the fraction of pixels that are nearly black (all RGB channels < 8).
     * Samples on a 10x10 grid across the bitmap. Returns 0f for HARDWARE bitmaps
     * (pixel read not supported) — we assume they're valid.
     */
    private fun Bitmap.blackPixelRatio(): Float {
        if (config == Bitmap.Config.HARDWARE) return 0f
        val gridSize = 10
        var blackCount = 0
        var totalCount = 0
        try {
            for (gy in 0 until gridSize) {
                val y = gy * height / gridSize
                for (gx in 0 until gridSize) {
                    val x = gx * width / gridSize
                    val pixel = getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (r < 8 && g < 8 && b < 8) blackCount++
                    totalCount++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "blackPixelRatio sampling failed: ${e.message}")
            return 0f
        }
        return if (totalCount > 0) blackCount.toFloat() / totalCount else 1f
    }

    /**
     * Launch the [MagnifierOverlayActivity] to display the magnified screenshot.
     * We use a fullscreen Activity instead of a WindowManager overlay because
     * MuMu's SurfaceFlinger does not composite overlay windows into the hardware
     * framebuffer — they exist in WindowManager but are invisible to the user.
     */
    private fun showOverlay() {
        val bmp = screenshot ?: run {
            Log.w(TAG, "showOverlay: screenshot is null, nothing to show")
            return
        }

        Log.d(TAG, "showOverlay: bitmap=${bmp.width}x${bmp.height} scale=$scale")

        MagnifierOverlayActivity.screenshot = bmp
        MagnifierOverlayActivity.onDismissRunnable = Runnable {
            onDismiss?.invoke()
            // screenshot is recycled by MagnifierOverlayActivity.onDestroy
            screenshot = null
        }
        MagnifierOverlayActivity.start(service, scale)
    }

    fun remove() {
        ScreenCaptureActivity.onCaptured = null
        ScreenCaptureActivity.isCapturing = false

        screenshot?.recycle()
        screenshot = null
        Log.d(TAG, "remove() done")
    }

    private fun saveDiagPng(bmp: Bitmap, tag: String) {
        try {
            val dir = service.externalCacheDir ?: service.cacheDir
            val file = java.io.File(dir, "${tag}_${bmp.width}x${bmp.height}_${System.currentTimeMillis()}.png")
            java.io.FileOutputStream(file).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 90, fos)
            }
            Log.d(TAG, "Diagnostic PNG saved: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save diagnostic PNG: ${e.message}")
        }
    }

    val isShowing: Boolean get() = MagnifierOverlayActivity.screenshot != null
}
