package com.bebig.magnify.view

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.bebig.magnify.R

class FloatingMagnifyButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val ivIcon: ImageView
    private val touchSlop: Int
    private var downRawX = 0f
    private var downRawY = 0f
    private var moved = false

    var onToggleListener: (() -> Unit)? = null
    var onDragListener: ((Float, Float) -> Unit)? = null
    var onDragEndListener: (() -> Unit)? = null

    var isMagnifying: Boolean = false
        set(value) {
            field = value
            updateIcon()
        }

    init {
        background = context.getDrawable(R.drawable.bg_floating_button)
        elevation = 8f * context.resources.displayMetrics.density
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND

        LayoutInflater.from(context).inflate(R.layout.view_floating_button, this, true)
        ivIcon = findViewById(R.id.iv_icon)
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        isClickable = true
    }

    private fun updateIcon() {
        ivIcon.setImageResource(
            if (isMagnifying) R.drawable.ic_magnify else R.drawable.ic_magnify_off
        )
        contentDescription = context.getString(
            if (isMagnifying) R.string.floating_button_magnify_active
            else R.string.floating_button_magnify
        )
    }

    private fun setPressedVisual(pressed: Boolean) {
        val bg = background as? GradientDrawable
        if (pressed) {
            bg?.setColor(Color.RED)
        } else {
            bg?.setColor(context.getColor(R.color.floating_button_bg))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                Log.d("FloatingBtn", "ACTION_DOWN rawX=${event.rawX} rawY=${event.rawY}")
                setPressedVisual(true)
                downRawX = event.rawX
                downRawY = event.rawY
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!moved && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    moved = true
                    setPressedVisual(false)
                }
                if (moved) {
                    onDragListener?.invoke(dx, dy)
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                setPressedVisual(false)
                if (moved) {
                    onDragEndListener?.invoke()
                } else {
                    performClick()
                }
                moved = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                setPressedVisual(false)
                if (moved) {
                    onDragEndListener?.invoke()
                }
                moved = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        Log.d("FloatingBtn", "performClick — invoking onToggleListener")
        onToggleListener?.invoke()
        return super.performClick()
    }
}
