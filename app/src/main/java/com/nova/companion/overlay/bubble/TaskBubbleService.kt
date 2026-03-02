package com.nova.companion.overlay.bubble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
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
 * Uses programmatic views (no XML layout required).
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
    private var tvTitle: TextView? = null
    private var tvStatus: TextView? = null
    private var progressBar: ProgressBar? = null
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
            .setSmallIcon(R.drawable.ic_nova_notification)
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
    // BUBBLE UI (programmatic — no XML layout needed)
    // ────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun buildBubbleView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            setBackgroundColor(Color.parseColor("#E0202020"))
        }

        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        tvTitle = title
        container.addView(title)

        val status = TextView(this).apply {
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 12f
        }
        tvStatus = status
        container.addView(status)

        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(4)
            )
            lp.topMargin = dpToPx(6)
            layoutParams = lp
        }
        progressBar = pb
        container.addView(pb)

        return container
    }

    private fun showBubble(state: TaskProgressState) {
        val view = buildBubbleView()
        bubbleView = view

        updateBubbleContent(state)

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
        if (bubbleView == null) return
        updateBubbleContent(state)
    }

    private fun updateBubbleContent(state: TaskProgressState) {
        tvTitle?.text = state.title
        tvStatus?.text = state.statusMessage
        progressBar?.progress = state.progress

        // Update title color based on status
        when {
            state.isComplete -> tvTitle?.setTextColor(Color.parseColor("#4CAF50"))
            state.isFailed -> tvTitle?.setTextColor(Color.parseColor("#F44336"))
            else -> tvTitle?.setTextColor(Color.WHITE)
        }
    }

    private fun removeBubble() {
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            bubbleView = null
            tvTitle = null
            tvStatus = null
            progressBar = null
        }
    }
}
