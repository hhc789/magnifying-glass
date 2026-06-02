package com.bebig.magnify.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureActivity : Activity() {

    companion object {
        private const val TAG = "ScreenCaptureAct"
        private const val REQUEST_CODE = 1001

        @Volatile
        var capturedBitmap: Bitmap? = null

        @Volatile
        var onCaptured: ((Bitmap?) -> Unit)? = null

        @Volatile
        var cachedResultCode: Int = 0

        @Volatile
        var cachedData: Intent? = null

        @Volatile
        var isCapturing = false
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var targetWidth = 0
    private var targetHeight = 0
    private var density = 240
    private var captureIndex = 0
    private var captureGeneration = 0
    private var captureActive = false
    private var diagDir: File? = null
    private var appContext: Context? = null
    private var needsRotationFix = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: cachedData=${cachedData != null}, isCapturing=$isCapturing")

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)

        // Log everything we can about the display for diagnosis
        val rotation = wm.defaultDisplay.rotation
        val mode = wm.defaultDisplay.mode
        val physW = mode.physicalWidth
        val physH = mode.physicalHeight
        val realW = dm.widthPixels
        val realH = dm.heightPixels
        val dpi = dm.densityDpi

        Log.d(TAG, "=== DISPLAY DIAGNOSTIC ===")
        Log.d(TAG, "getRealMetrics: ${realW}x${realH} dpi=$dpi")
        Log.d(TAG, "mode.physical: ${physW}x${physH}")
        Log.d(TAG, "rotation: $rotation")
        Log.d(TAG, "==========================")

        // On MuMu the physical framebuffer is 900x1600 rotated to logical
        // 1600x900. AUTO_MIRROR at logical resolution captures a 1600x900
        // buffer where content only fills the center (black borders on edges).
        // Use physical dimensions so content fills the entire capture area.
        if (rotation == 1 || rotation == 3) { // ROTATION_90 or ROTATION_270
            targetWidth = physW
            targetHeight = physH
            needsRotationFix = true
            Log.d(TAG, "Using physical dimensions for capture: ${targetWidth}x${targetHeight}")
        } else {
            targetWidth = realW
            targetHeight = realH
        }
        density = dpi

        // Increment generation to cancel stale callbacks from previous captures
        captureGeneration++
        captureIndex = (System.currentTimeMillis() % 100000).toInt()

        capturedBitmap = null

        if (cachedData != null && cachedResultCode == RESULT_OK) {
            Log.d(TAG, "Reusing cached MediaProjection permission")
            useMediaProjection(cachedResultCode, cachedData!!)
        } else {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = manager.createScreenCaptureIntent()
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_CODE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start capture intent: ${e.message}", e)
                done(null)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CODE || resultCode != RESULT_OK || data == null) {
            Log.w(TAG, "Permission denied or cancelled")
            done(null)
            return
        }

        cachedResultCode = resultCode
        cachedData = data

        useMediaProjection(resultCode, data)
    }

    private fun useMediaProjection(resultCode: Int, data: Intent) {
        val gen = captureGeneration
        Log.d(TAG, "useMediaProjection: gen=$gen, starting fg service...")

        // Store before finishing — used by captureFrame & done after onDestroy.
        appContext = applicationContext
        diagDir = externalCacheDir ?: cacheDir

        val fgIntent = Intent(this, MediaProjectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(fgIntent)
        } else {
            @Suppress("DEPRECATION")
            startService(fgIntent)
        }

        // 800ms — give the foreground service enough time to start on slow
        // emulators. If the service isn't ready, getMediaProjection throws
        // SecurityException and the capture fails.
        Handler(Looper.getMainLooper()).postDelayed({
            if (captureGeneration != gen) {
                Log.d(TAG, "useMediaProjection: stale gen=$gen, ignoring")
                return@postDelayed
            }
            try {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mgr.getMediaProjection(resultCode, data)
                Log.d(TAG, "getMediaProjection succeeded")
            } catch (e: SecurityException) {
                Log.e(TAG, "getMediaProjection SecurityException: ${e.message}")
                // Cached token is stale — clear it and request fresh permission
                cachedResultCode = 0
                cachedData = null
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val intent = manager.createScreenCaptureIntent()
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQUEST_CODE)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to start capture intent after stale token: ${e2.message}", e2)
                    done(null)
                }
                return@postDelayed
            } catch (e: Exception) {
                Log.e(TAG, "getMediaProjection failed: ${e.message}", e)
                done(null)
                return@postDelayed
            }

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped")
                }
            }, Handler(Looper.getMainLooper()))

            if (captureGeneration != gen) {
                Log.d(TAG, "useMediaProjection: stale after getMediaProjection")
                try { mediaProjection?.stop() } catch (_: Exception) {}
                mediaProjection = null
                return@postDelayed
            }

            // Immediately finish the activity so the user's previous app
            // is fully visible before we capture. moveTaskToBack is unreliable
            // on emulators like MuMu — it doesn't always reveal the previous
            // task. finish() guarantees our transparent window is gone.
            captureActive = true
            Log.d(TAG, "Finishing activity, will capture in 1500ms")
            finish()

            Handler(Looper.getMainLooper()).postDelayed({
                if (captureGeneration == gen) {
                    try {
                        captureFrame()
                    } catch (e: Exception) {
                        Log.e(TAG, "captureFrame crashed: ${e.message}", e)
                        done(null)
                    }
                } else {
                    Log.d(TAG, "captureFrame delayed: stale gen, ignoring")
                }
            }, 1500)
        }, 800)
    }

    private fun captureFrame() {
        val gen = captureGeneration
        val projection = mediaProjection ?: run { done(null); return }

        Log.d(TAG, "captureFrame: gen=$gen, creating ImageReader ${targetWidth}x${targetHeight}")

        imageReader = ImageReader.newInstance(
            targetWidth, targetHeight,
            PixelFormat.RGBA_8888, 2
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            if (capturedBitmap != null) return@setOnImageAvailableListener
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    var result = imageToBitmap(image)
                    if (result != null && needsRotationFix) {
                        // Physical 900x1600 → logical 1600x900 via 90° rotation
                        val rotated = Bitmap.createBitmap(result.height, result.width, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(rotated)
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(90f)
                        matrix.postTranslate((result.height).toFloat(), 0f)
                        canvas.drawBitmap(result, matrix, null)
                        result.recycle()
                        result = rotated
                        Log.d(TAG, "Rotated capture: ${result.width}x${result.height}")
                    }
                    if (result != null) {
                        saveDiagPng(result, "capture_${captureIndex}_raw")
                    }
                    Log.d(TAG, "Frame captured: ${result?.width}x${result?.height} config=${result?.config}")
                    done(result)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame extraction failed: ${e.message}", e)
                    done(null)
                } finally {
                    image.close()
                }
            } else {
                Log.w(TAG, "acquireLatestImage returned null")
            }
        }, Handler(Looper.getMainLooper()))

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCapture",
            targetWidth, targetHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )

        if (virtualDisplay == null) {
            Log.e(TAG, "createVirtualDisplay returned null")
            done(null)
            return
        }

        // Timeout guard — only fires if this generation is still current
        Handler(Looper.getMainLooper()).postDelayed({
            if (captureGeneration == gen && capturedBitmap == null) {
                Log.w(TAG, "Frame capture timed out (gen=$gen)")
                done(null)
            }
        }, 5000)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null

        val crop = image.cropRect
        val imgW = crop.width()
        val imgH = crop.height()
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val bufferSize = buffer.capacity()

        Log.d(TAG, "imageToBitmap: cropRect=${imgW}x${imgH} " +
            "pixelStride=$pixelStride rowStride=$rowStride bufferSize=$bufferSize " +
            "expected=${imgW * imgH * 4}")

        val rowPadding = rowStride - pixelStride * imgW

        // Create bitmap matching the buffer layout, then crop if needed
        val bmpWidth = if (rowPadding > 0) {
            imgW + rowPadding / pixelStride
        } else {
            imgW
        }

        val bmp = Bitmap.createBitmap(bmpWidth, imgH, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buffer)

        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bmp, 0, 0, imgW, imgH)
            bmp.recycle()
            cropped
        } else {
            bmp
        }
    }

    private fun saveDiagPng(bmp: Bitmap, tag: String) {
        try {
            val dir = diagDir ?: return
            val file = File(dir, "${tag}_${bmp.width}x${bmp.height}.png")
            FileOutputStream(file).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 90, fos)
            }
            Log.d(TAG, "Diagnostic PNG saved: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save diagnostic PNG: ${e.message}")
        }
    }

    private fun done(bitmap: Bitmap?) {
        captureActive = false
        capturedBitmap = bitmap

        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}

        virtualDisplay = null
        imageReader = null
        mediaProjection = null

        isCapturing = false

        // Keep cached permission token so subsequent captures skip the
        // permission dialog. Only clear if we detect the token is stale
        // (getMediaProjection throws SecurityException).
        onCaptured?.invoke(bitmap)
        onCaptured = null

        appContext?.let { ctx ->
            try { ctx.stopService(Intent(ctx, MediaProjectionService::class.java)) } catch (_: Exception) {}
        }

        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (captureActive) {
            Log.d(TAG, "onDestroy: capture active, deferring cleanup")
            return
        }
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
    }
}
