package com.bebig.magnify.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Minimal foreground service required by Android 12+ for MediaProjection.
 * Started before screen capture, stopped immediately after.
 */
class MediaProjectionService : Service() {

    companion object {
        private const val TAG = "MediaProjSvc"
        private const val CHANNEL_ID = "media_projection"
        private const val NOTIFICATION_ID = 2001
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "屏幕截图",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕截图")
            .setContentText("正在截取屏幕...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build())
        Log.d(TAG, "Foreground service started")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Foreground service stopped")
        super.onDestroy()
    }
}
