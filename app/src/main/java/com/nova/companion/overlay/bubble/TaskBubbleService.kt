package com.nova.companion.overlay.bubble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.nova.companion.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Overlay service that shows a floating task progress bubble
 * whenever WorkflowExecutor is running a multi-step task.
 *
 * The bubble appears on top of any app (requires SYSTEM_ALERT_WINDOW permission).
 */
class TaskBubbleService : Service() {

    companion object {
        private const val NOTIF_CHANNEL_ID = "task_bubble_channel"
        private const val NOTIF_ID = 42
        private const val AUTO_DISMISS_MS = 3000L

        fun start(context: Context) {
            val intent = Intent(context, TaskBubbleService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TaskBubbleService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        observeTaskProgress()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        removeBubble()
        super.onDestroy()
    }

    // ────────────────────────────────────────────────────────────
    // FOREGROUND NOTIFICATION (required for overlay service)
    // ────────────────────────────────────────────────────────────

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Task Progress",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Nova is working...")
            .setSmallIcon(R.drawable.ic_nova_bubble)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notif)
    }

    // ────────────────────────────────────────────────────────────
    // TASK PROGRESS OBSERVER
    // ────────────────────────────────────────────────────────────

    private fun observeTaskProgress() {
        serviceScope.launch {
            TaskProgressManager.activeTask.collectLatest { state ->
                when {
                    state == null -> removeBubble()
                    state.isComplete || state.isFailed -> {
                        updateBubble(state)
                        // Auto-dismiss after 3 seconds
                        delay(AUTO_DISMISS_MS)
                        removeBubble()
                        TaskProgressManager.clearTask()
                    }
                    else -> {
                        if (bubbleView == null) showBubble(state)
                        else updateBubble(state)
                    }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // BUBBLE UI
    // ────────────────────────────────────────────────────────────

    private fun showBubble(state: TaskProgressState) {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_task_bubble, null)
        bubbleView = view

        updateBubbleContent(view, state)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 80  // px from top
        }

        windowManager.addView(view, params)
    }

    private fun updateBubble(state: TaskProgressState) {
        val view = bubbleView ?: return
        updateBubbleContent(view, state)
    }

    private fun updateBubbleContent(view: View, state: TaskProgressState) {
        view.findViewById<TextView>(R.id.tvTaskTitle)?.text = state.title
        view.findViewById<TextView>(R.id.tvTaskStatus)?.text = state.statusMessage
        view.findViewById<ProgressBar>(R.id.pbTaskProgress)?.progress = state.progress

        val icon = view.findViewById<ImageView>(R.id.ivTaskIcon)
        when {
            state.isComplete -> icon?.setImageResource(R.drawable.ic_check_circle)
            state.isFailed -> icon?.setImageResource(R.drawable.ic_error)
            else -> icon?.setImageResource(R.drawable.ic_nova_bubble)
        }
    }

    private fun removeBubble() {
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            bubbleView = null
        }
    }
}
