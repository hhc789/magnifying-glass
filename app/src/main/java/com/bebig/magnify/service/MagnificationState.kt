package com.bebig.magnify.service

sealed class MagnificationState {
    data object Idle : MagnificationState()
    data object Unsupported : MagnificationState()
    data class Magnifying(val scale: Float) : MagnificationState()
}
