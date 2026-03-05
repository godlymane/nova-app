package com.nova.companion.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nova.companion.R
import com.nova.companion.ui.aura.AuraState

/**
 * Dynamic Island overlay service.
 *
 * Displays a pill-shaped overlay at the top-center of the screen that persists across all apps.
 * The overlay visualizes Nova's voice state:
 *   - DORMANT:   Tiny dot or invisible pill (non-intrusive)
 *   - LISTENING:  Expanded pill with energetic ripples
 *   - THINKING:   Expanded pill with iridescent swirl
 *   - SPEAKING:   Expanded pill with amplitude-synced waveforms
 *
 * Interactive controls:
 *   - Tap:        Force-listen (activate wake word → mic)
 *   - Double-tap:  Interrupt/stop current action
 *   - Swipe down:  Dismiss/hide overlay temporarily
 *
 * Uses SYSTEM_ALERT_WINDOW (TYPE_APPLICATION_OVERLAY) for cross-app visibility.
 *
 * Fixes:
 * - Implements ViewModelStoreOwner so ComposeView doesn't crash in newer Compose versions
 * - onStartCommand returns START_STICKY so Android restarts after killing
 * - isRunning() guard prevents double-adding the overlay window
 */
class AuraOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    companion object {
        private const val TAG = "AuraOverlay"
        private const val CHANNEL_ID = "nova_aura_overlay"
        private const val NOTIFICATION_ID = 2001

        // ── Pill dimensions (dp values, converted at runtime) ──
        private const val PILL_WIDTH_DORMANT_DP = 48
        private const val PILL_HEIGHT_DORMANT_DP = 12
        private const val PILL_WIDTH_ACTIVE_DP = 200
        private const val PILL_HEIGHT_ACTIVE_DP = 44
        private const val PILL_MARGIN_TOP_DP = 12  // extra margin so it's below status bar

        // ── Shared state (updated from ChatViewModel) ──
        private val _auraState = mutableStateOf(AuraState.DORMANT)
        private val _amplitude = mutableFloatStateOf(0f)

        @Volatile
        private var instance: AuraOverlayService? = null

        fun isRunning(): Boolean = instance != null

        fun updateAuraState(state: AuraState) {
            _auraState.value = state
            instance?.animatePillSize(state)
        }

        fun updateAmplitude(amp: Float) {
            _amplitude.floatValue = amp
        }

        fun start(context: Context) {
            val intent = Intent(context, AuraOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AuraOverlayService::class.java))
        }

        // ── Callbacks for interactive controls ──
        var onTapToListen: (() -> Unit)? = null
        var onDoubleTapToStop: (() -> Unit)? = null
    }

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var glowView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var savedStateRegistryController: SavedStateRegistryController

    // ViewModelStoreOwner required by ComposeView in newer Compose versions
    override val viewModelStore: ViewModelStore = ViewModelStore()

    // Current pill pixel dimensions for animation
    private var currentWidthPx = 0
    private var currentHeightPx = 0
    private var sizeAnimator: ValueAnimator? = null

    // Dismiss state
    private var isDismissed = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        instance = this
        lifecycleRegistry = LifecycleRegistry(this)
        savedStateRegistryController = SavedStateRegistryController.create(this)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        setupOverlay()
        setupEdgeGlow()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        Log.i(TAG, "AuraOverlayService created and overlay added")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If we're already running (overlay already added), just return — don't double-add
        // This is called every time AuraOverlayService.start() is called (e.g. from onResume)
        Log.d(TAG, "onStartCommand — already running, overlay stays put")
        return START_STICKY
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density

        // Start with dormant pill size
        currentWidthPx = (PILL_WIDTH_DORMANT_DP * density).toInt()
        currentHeightPx = (PILL_HEIGHT_DORMANT_DP * density).toInt()
        val marginTopPx = (PILL_MARGIN_TOP_DP * density).toInt()

        val params = WindowManager.LayoutParams(
            currentWidthPx,
            currentHeightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // Touchable but not focusable — receives taps, doesn't steal keyboard
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = marginTopPx
        }
        layoutParams = params

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AuraOverlayService)
            setViewTreeSavedStateRegistryOwner(this@AuraOverlayService)
            setViewTreeViewModelStoreOwner(this@AuraOverlayService)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            clipToOutline = true
            setContent {
                DynamicIslandContent(
                    auraState = _auraState.value,
                    amplitude = _amplitude.floatValue,
                    onTap = { handleTap() },
                    onDoubleTap = { handleDoubleTap() },
                    onSwipeDown = { handleSwipeDown() }
                )
            }
        }

        try {
            windowManager?.addView(overlayView, params)
            Log.i(TAG, "Dynamic Island overlay added (${currentWidthPx}x${currentHeightPx})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay", e)
        }
    }

    /**
     * Full-screen transparent edge-glow layer.
     * FLAG_NOT_TOUCHABLE = all touches pass through.
     * Only visible when Nova is LISTENING/THINKING/SPEAKING.
     */
    private fun setupEdgeGlow() {
        val glowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        glowView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AuraOverlayService)
            setViewTreeSavedStateRegistryOwner(this@AuraOverlayService)
            setViewTreeViewModelStoreOwner(this@AuraOverlayService)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setContent {
                val state = _auraState.value
                val amp  = _amplitude.floatValue

                // Animated glow intensity
                val infiniteTransition = rememberInfiniteTransition(label = "glow")
                val breathAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f, targetValue = 0.7f,
                    animationSpec = infiniteRepeatable(tween(durationMillis = 1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "breathAlpha"
                )

                val (c1, c2) = when (state) {
                    AuraState.DORMANT   -> Color.Transparent to Color.Transparent
                    AuraState.LISTENING -> Color(0xFF00FFF5) to Color(0xFF0066FF)
                    AuraState.THINKING  -> Color(0xFF9B00FF) to Color(0xFFFF00B0)
                    AuraState.SPEAKING  -> Color(0xFFFF00B0) to Color(0xFF6A0DAD)
                }

                val edgeAlpha = if (state == AuraState.DORMANT) 0f
                    else breathAlpha * (0.5f + amp * 0.5f)

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val edgeW = 24f
                    // Left edge
                    drawRect(
                        brush = Brush.horizontalGradient(listOf(c1.copy(alpha = edgeAlpha), Color.Transparent)),
                        topLeft = Offset.Zero, size = Size(edgeW * 2, size.height)
                    )
                    // Right edge
                    drawRect(
                        brush = Brush.horizontalGradient(listOf(Color.Transparent, c2.copy(alpha = edgeAlpha))),
                        topLeft = Offset(size.width - edgeW * 2, 0f), size = Size(edgeW * 2, size.height)
                    )
                    // Bottom edge
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color.Transparent, c1.copy(alpha = edgeAlpha * 0.6f))),
                        topLeft = Offset(0f, size.height - edgeW * 3), size = Size(size.width, edgeW * 3)
                    )
                }
            }
        }
        try {
            windowManager?.addView(glowView, glowParams)
            Log.i(TAG, "Edge glow overlay added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add edge glow", e)
        }
    }

    // ── Pill size animation ─────────────────────────────────────

    fun animatePillSize(state: AuraState) {
        if (isDismissed) return

        val density = resources.displayMetrics.density
        val targetWidth: Int
        val targetHeight: Int

        when (state) {
            AuraState.DORMANT -> {
                targetWidth = (PILL_WIDTH_DORMANT_DP * density).toInt()
                targetHeight = (PILL_HEIGHT_DORMANT_DP * density).toInt()
            }
            AuraState.LISTENING, AuraState.THINKING, AuraState.SPEAKING -> {
                targetWidth = (PILL_WIDTH_ACTIVE_DP * density).toInt()
                targetHeight = (PILL_HEIGHT_ACTIVE_DP * density).toInt()
            }
        }

        if (targetWidth == currentWidthPx && targetHeight == currentHeightPx) return

        sizeAnimator?.cancel()

        val startW = currentWidthPx
        val startH = currentHeightPx

        sizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (state == AuraState.DORMANT) 500L else 300L
            interpolator = OvershootInterpolator(
                if (state == AuraState.LISTENING) 1.2f else 0.5f
            )
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                currentWidthPx = (startW + (targetWidth - startW) * fraction).toInt()
                currentHeightPx = (startH + (targetHeight - startH) * fraction).toInt()

                layoutParams?.let { lp ->
                    lp.width = currentWidthPx
                    lp.height = currentHeightPx
                    try {
                        overlayView?.let { windowManager?.updateViewLayout(it, lp) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update overlay layout", e)
                    }
                }
            }
            start()
        }
    }

    // ── Interactive controls ─────────────────────────────────────

    private fun handleTap() {
        Log.d(TAG, "Tap → force-listen")
        onTapToListen?.invoke()
    }

    private fun handleDoubleTap() {
        Log.d(TAG, "Double-tap → interrupt/stop")
        onDoubleTapToStop?.invoke()
    }

    private fun handleSwipeDown() {
        Log.d(TAG, "Swipe down → dismiss temporarily")
        isDismissed = true
        // Shrink to zero then hide
        sizeAnimator?.cancel()
        val startW = currentWidthPx
        val startH = currentHeightPx
        sizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                currentWidthPx = (startW * (1f - fraction)).toInt().coerceAtLeast(1)
                currentHeightPx = (startH * (1f - fraction)).toInt().coerceAtLeast(1)
                layoutParams?.let { lp ->
                    lp.width = currentWidthPx
                    lp.height = currentHeightPx
                    try {
                        overlayView?.let { windowManager?.updateViewLayout(it, lp) }
                    } catch (_: Exception) {}
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    overlayView?.visibility = View.GONE
                    // Auto-restore after 10 seconds or on next non-dormant state
                    android.os.Handler(mainLooper).postDelayed({
                        restoreOverlay()
                    }, 10_000)
                }
            })
            start()
        }
    }

    private fun restoreOverlay() {
        if (!isDismissed) return
        isDismissed = false
        overlayView?.visibility = View.VISIBLE
        animatePillSize(_auraState.value)
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onDestroy() {
        instance = null
        sizeAnimator?.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        try { overlayView?.let { windowManager?.removeView(it) } } catch (e: Exception) {
            Log.w(TAG, "Error removing overlay", e)
        }
        try { glowView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlayView = null
        glowView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nova Aura",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Nova's ambient aura overlay"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Nova")
            .setContentText("Aura active")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }
}
