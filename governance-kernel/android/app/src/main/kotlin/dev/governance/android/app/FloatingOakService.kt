package dev.governance.android.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs

/**
 * Foreground service that shows a floating mic bubble on top of all apps.
 * Tapping the bubble launches [AssistantActivity] for full voice chat.
 * The bubble is draggable so the user can reposition it.
 *
 * Requires [android.Manifest.permission.SYSTEM_ALERT_WINDOW] (already
 * declared in manifest). Started automatically when the accessibility
 * service connects, or manually from the app.
 */
class FloatingOakService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        showBubble()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeBubble()
    }

    private fun showBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val bubble = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setPadding(16, 16, 16, 16)
            setBackgroundResource(android.R.drawable.dialog_holo_dark_frame)
            alpha = 0.9f
        }

        val params = WindowManager.LayoutParams(
            144, 144,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 400
        }

        // Drag + tap handling
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager?.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // Tap → launch assistant voice chat
                        launchAssistant()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(bubble, params)
            bubbleView = bubble
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating bubble", e)
        }
    }

    private fun removeBubble() {
        bubbleView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        bubbleView = null
    }

    private fun launchAssistant() {
        val intent = Intent(this, AssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Oak Assistant",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Floating assistant bubble"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Oak & Sparrow")
            .setContentText("Voice assistant active — tap bubble to talk")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "OakFloatingService"
        private const val CHANNEL_ID = "oak_floating_channel"
        private const val NOTIFICATION_ID = 2002

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, FloatingOakService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(
                Intent(context, FloatingOakService::class.java)
            )
        }
    }
}
