package com.bebig.magnify

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bebig.magnify.databinding.ActivityMainBinding
import com.bebig.magnify.service.ScreenCaptureActivity
import com.bebig.magnify.util.PermissionHelper

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 2001
    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOverlay.setOnClickListener {
            PermissionHelper.openOverlaySettings(this)
        }

        binding.btnAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        binding.btnUnsupported.setOnClickListener {
            val success = PermissionHelper.enableSystemMagnificationGesture(this)
            binding.tvUnsupportedStatus.text = if (success) {
                getString(R.string.card_unsupported_fallback) + "\n\n已尝试开启，请在设置中验证。"
            } else {
                getString(R.string.card_unsupported_fallback) +
                    "\n\n无法自动开启，请手动前往 设置 → 辅助功能 → 放大 中开启。"
            }
        }

        // Grant screen capture permission from here so the system dialog
        // appears on the visible display (MuMu routes Service-launched
        // activities to phantom displays the user can't see).
        binding.btnGrantCapture.setOnClickListener {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = manager.createScreenCaptureIntent()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val options = ActivityOptions.makeBasic()
                    try {
                        val method = ActivityOptions::class.java
                            .getDeclaredMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                        method.isAccessible = true
                        method.invoke(options, 0)
                    } catch (_: Exception) {}
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQUEST_MEDIA_PROJECTION, options.toBundle())
                } else {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start capture intent: ${e.message}", e)
                Toast.makeText(this, "无法启动屏幕截图权限请求", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ScreenCaptureActivity.cachedResultCode = resultCode
                ScreenCaptureActivity.cachedData = data
                Toast.makeText(this, "屏幕截图权限已授予", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "MediaProjection permission granted and cached")
            } else {
                Toast.makeText(this, "需要授予屏幕截图权限才能使用放大镜", Toast.LENGTH_LONG).show()
                Log.w(TAG, "MediaProjection permission denied")
            }
            updatePermissionStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val overlayGranted = PermissionHelper.hasOverlayPermission(this)
        val accessibilityEnabled = PermissionHelper.isAccessibilityServiceEnabled(this)
        val isSupported = PermissionHelper.isMagnificationApiSupported()
        val captureGranted = ScreenCaptureActivity.cachedData != null &&
            ScreenCaptureActivity.cachedResultCode == Activity.RESULT_OK

        binding.cardOverlay.visibility = View.VISIBLE
        if (overlayGranted) {
            binding.ivOverlayStatus.setColorFilter(getColor(R.color.success))
            binding.tvOverlayStatus.text = getString(R.string.card_overlay_granted)
            binding.tvOverlayStatus.setTextColor(getColor(R.color.success))
        } else {
            binding.ivOverlayStatus.setColorFilter(getColor(R.color.error))
            binding.tvOverlayStatus.text = getString(R.string.card_overlay_required)
            binding.tvOverlayStatus.setTextColor(getColor(R.color.error))
        }

        // Show capture permission card
        binding.cardCapture.visibility = View.VISIBLE
        if (captureGranted) {
            binding.ivCaptureStatus.setColorFilter(getColor(R.color.success))
            binding.tvCaptureStatus.text = "屏幕截图权限已授予"
            binding.tvCaptureStatus.setTextColor(getColor(R.color.success))
            binding.btnGrantCapture.text = "重新授权"
        } else {
            binding.ivCaptureStatus.setColorFilter(getColor(R.color.warning))
            binding.tvCaptureStatus.text = "需要授予屏幕截图权限"
            binding.tvCaptureStatus.setTextColor(getColor(R.color.warning))
            binding.btnGrantCapture.text = "授予权限"
        }

        if (isSupported) {
            binding.cardAccessibility.visibility = View.VISIBLE
            if (accessibilityEnabled) {
                binding.ivAccessibilityStatus.setColorFilter(getColor(R.color.success))
                binding.tvAccessibilityStatus.text = getString(R.string.card_accessibility_enabled)
                binding.tvAccessibilityStatus.setTextColor(getColor(R.color.success))
            } else {
                binding.ivAccessibilityStatus.setColorFilter(getColor(R.color.on_surface_variant))
                binding.tvAccessibilityStatus.text = getString(R.string.card_accessibility_guide)
                binding.tvAccessibilityStatus.setTextColor(getColor(R.color.on_surface_variant))
            }
        } else {
            binding.cardAccessibility.visibility = View.GONE
        }

        binding.cardUnsupported.visibility = if (isSupported) View.GONE else View.VISIBLE

        val allReady = if (isSupported) {
            accessibilityEnabled && overlayGranted && captureGranted
        } else {
            false
        }

        if (!overlayGranted && isSupported) {
            binding.tvStatus.text = getString(R.string.status_pending)
            binding.tvStatus.setTextColor(getColor(R.color.warning))
        } else if (allReady) {
            binding.tvStatus.text = getString(R.string.status_all_ready)
            binding.tvStatus.setTextColor(getColor(R.color.success))
        } else if (!isSupported) {
            binding.tvStatus.text = getString(R.string.card_unsupported_fallback)
            binding.tvStatus.setTextColor(getColor(R.color.warning))
        } else {
            binding.tvStatus.text = getString(R.string.status_pending)
            binding.tvStatus.setTextColor(getColor(R.color.on_surface_variant))
        }
    }
}
