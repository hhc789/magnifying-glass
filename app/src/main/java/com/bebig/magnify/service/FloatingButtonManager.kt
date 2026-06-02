package com.bebig.magnify.service

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import com.bebig.magnify.util.PreferenceHelper
import com.bebig.magnify.view.FloatingMagnifyButton

class FloatingButtonManager(private val context: Context) {

    companion object {
        private const val TAG = "FloatingBtnMgr"
    }

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val preferenceHelper = PreferenceHelper(context)

    private data class ButtonEntry(
        val button: FloatingMagnifyButton,
        val windowManager: WindowManager,
        var layoutParams: WindowManager.LayoutParams?
    )

    private val entries = mutableMapOf<Int, ButtonEntry>()

    var onToggleCallback: ((displayId: Int) -> Unit)? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.d(TAG, "onDisplayAdded: $displayId")
            addButtonToDisplay(displayId)
        }

        override fun onDisplayRemoved(displayId: Int) {
            Log.d(TAG, "onDisplayRemoved: $displayId")
            removeButtonFromDisplay(displayId)
        }

        override fun onDisplayChanged(displayId: Int) {
            Log.d(TAG, "onDisplayChanged: $displayId")
            removeButtonFromDisplay(displayId)
            addButtonToDisplay(displayId)
        }
    }

    fun show() {
        if (entries.isNotEmpty()) return
        Log.d(TAG, "show() — enumerating displays")
        displayManager.registerDisplayListener(displayListener, null)
        for (display in displayManager.displays) {
            val id = display.displayId
            Log.d(TAG, "Attempting to add button to display $id: ${display.name}")
            addButtonToDisplay(id)
        }
        Log.d(TAG, "show() done — ${entries.size} buttons placed")
    }

    private fun addButtonToDisplay(displayId: Int) {
        if (entries.containsKey(displayId)) return

        val display = try {
            displayManager.getDisplay(displayId)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot get display $displayId: ${e.message}")
            return
        }

        val displayContext = try {
            context.createDisplayContext(display)
        } catch (e: Exception) {
            Log.w(TAG, "createDisplayContext($displayId) failed: ${e.message}")
            return
        }

        val wm = try {
            displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } catch (e: Exception) {
            Log.w(TAG, "getSystemService(WINDOW) failed for display $displayId: ${e.message}")
            return
        }

        val button = FloatingMagnifyButton(displayContext)
        button.onToggleListener = {
            Log.d(TAG, "Toggle on display $displayId")
            onToggleCallback?.invoke(displayId)
        }
        button.onDragListener = { dx, dy ->
            handleDrag(displayId, dx, dy)
        }
        button.onDragEndListener = { handleDragEnd(displayId) }

        val params = createLayoutParams(wm, displayContext, displayId)

        try {
            wm.addView(button, params)
            entries[displayId] = ButtonEntry(button, wm, params)
            Log.d(TAG, "Button added to display $displayId at (${params.x}, ${params.y})")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException for display $displayId: ${e.message}")
            tryAddWithFallbackType(displayId, button, wm, displayContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add button to display $displayId: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun tryAddWithFallbackType(
        displayId: Int,
        button: FloatingMagnifyButton,
        wm: WindowManager,
        displayContext: Context
    ) {
        val fallbackType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val fallbackParams = createLayoutParams(wm, displayContext, displayId, forceType = fallbackType)
        try {
            wm.addView(button, fallbackParams)
            entries[displayId] = ButtonEntry(button, wm, fallbackParams)
            Log.d(TAG, "Button added to display $displayId with fallback type")
        } catch (e: Exception) {
            Log.w(TAG, "Fallback also failed for display $displayId: ${e.message}")
        }
    }

    private fun removeButtonFromDisplay(displayId: Int) {
        val entry = entries.remove(displayId) ?: return
        try {
            entry.windowManager.removeView(entry.button)
        } catch (_: Exception) {}
    }

    fun remove() {
        Log.d(TAG, "remove()")
        try { displayManager.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        for (displayId in entries.keys.toList()) {
            removeButtonFromDisplay(displayId)
        }
    }

    fun updateIcon(magnifying: Boolean) {
        for ((displayId, entry) in entries) {
            try {
                entry.button.isMagnifying = magnifying
            } catch (e: Exception) {
                Log.w(TAG, "updateIcon failed for display $displayId: ${e.message}")
            }
        }
    }

    /**
     * Called on every window state change (app switch).
     * Re-attaches detached overlays across all displays.
     */
    fun ensureAttached() {
        for ((displayId, entry) in entries) {
            if (entry.button.isAttachedToWindow) continue
            val lp = entry.layoutParams ?: continue
            try {
                if (entry.button.parent != null) {
                    entry.windowManager.removeView(entry.button)
                }
            } catch (_: Exception) {}
            try {
                entry.windowManager.addView(entry.button, lp)
                entries[displayId] = entry.copy(layoutParams = lp)
                Log.d(TAG, "Re-attached button on display $displayId")
            } catch (e: Exception) {
                Log.w(TAG, "Re-attach failed for display $displayId: ${e.message}")
            }
        }
    }

    private fun createLayoutParams(
        wm: WindowManager,
        displayContext: Context,
        @Suppress("UNUSED_PARAMETER") displayId: Int,
        forceType: Int? = null
    ): WindowManager.LayoutParams {
        val dm = getDisplayMetrics(wm)
        val density = displayContext.resources.displayMetrics.density
        val buttonSize = (56 * density).toInt()
        val margin = (16 * density).toInt()
        val navBarHeight = getNavBarHeight(displayContext)
        val statusBarHeight = getStatusBarHeight(displayContext)

        val type = forceType ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val params = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START

        if (preferenceHelper.hasSavedPosition) {
            val saved = preferenceHelper.getButtonPosition()
            params.x = clampX(saved.x, dm.widthPixels, buttonSize)
            params.y = clampY(saved.y, dm.heightPixels, buttonSize, statusBarHeight, navBarHeight)
        } else {
            params.x = dm.widthPixels - buttonSize - margin
            params.y = dm.heightPixels - buttonSize - margin - navBarHeight
        }

        return params
    }

    private fun handleDrag(displayId: Int, dx: Float, dy: Float) {
        val entry = entries[displayId] ?: return
        val lp = entry.layoutParams ?: return
        val dm = getDisplayMetrics(entry.windowManager)
        val displayContext = entry.button.context
        val density = displayContext.resources.displayMetrics.density
        val buttonSize = (56 * density).toInt()
        val statusBarHeight = getStatusBarHeight(displayContext)
        val navBarHeight = getNavBarHeight(displayContext)

        lp.x = clampX(lp.x + dx.toInt(), dm.widthPixels, buttonSize)
        lp.y = clampY(lp.y + dy.toInt(), dm.heightPixels, buttonSize, statusBarHeight, navBarHeight)

        try {
            entry.windowManager.updateViewLayout(entry.button, lp)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "updateViewLayout failed for display $displayId: view not attached")
        }
    }

    private fun handleDragEnd(displayId: Int) {
        val entry = entries[displayId] ?: return
        val lp = entry.layoutParams ?: return
        preferenceHelper.saveButtonPosition(lp.x, lp.y)
    }

    @Suppress("DEPRECATION")
    private fun getDisplayMetrics(wm: WindowManager): DisplayMetrics {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun clampX(x: Int, screenWidth: Int, buttonSize: Int): Int {
        return x.coerceIn(0, screenWidth - buttonSize)
    }

    private fun clampY(y: Int, screenHeight: Int, buttonSize: Int, statusBarHeight: Int, navBarHeight: Int): Int {
        return y.coerceIn(statusBarHeight, screenHeight - buttonSize - navBarHeight)
    }

    private fun getNavBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun getStatusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}
