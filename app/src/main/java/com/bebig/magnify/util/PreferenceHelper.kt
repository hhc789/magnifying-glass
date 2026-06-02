package com.bebig.magnify.util

import android.content.Context
import android.graphics.Point

class PreferenceHelper(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var buttonX: Int
        get() = prefs.getInt(KEY_BUTTON_X, -1)
        set(value) = prefs.edit().putInt(KEY_BUTTON_X, value).apply()

    var buttonY: Int
        get() = prefs.getInt(KEY_BUTTON_Y, -1)
        set(value) = prefs.edit().putInt(KEY_BUTTON_Y, value).apply()

    var scale: Float
        get() = prefs.getFloat(KEY_SCALE, DEFAULT_SCALE)
        set(value) = prefs.edit().putFloat(KEY_SCALE, value.coerceIn(1.0f, 8.0f)).apply()

    val hasSavedPosition: Boolean
        get() = buttonX >= 0 && buttonY >= 0

    fun saveButtonPosition(x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_BUTTON_X, x)
            .putInt(KEY_BUTTON_Y, y)
            .apply()
    }

    fun getButtonPosition(): Point = Point(buttonX, buttonY)

    companion object {
        private const val PREFS_NAME = "magnify_prefs"
        private const val KEY_BUTTON_X = "button_x"
        private const val KEY_BUTTON_Y = "button_y"
        private const val KEY_SCALE = "magnification_scale"
        private const val DEFAULT_SCALE = 2.0f
    }
}
