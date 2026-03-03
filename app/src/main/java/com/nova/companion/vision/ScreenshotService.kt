package com.nova.companion.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nova.companion.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Foreground service that holds a MediaProjection token for silent screenshot capture.
 *
 * The user grants screen capture permission once (via system consent dialog),
 * then this service can capture screenshots on demand without further prompts.
 *
 * Security: Screenshots are returned as in-memory Bitmaps only.
 * Nothing is written to disk or logged.
 */
class ScreenshotService : Service() {

    companion object {
        private const val TAG = "ScreenshotService"
        private const val CHANNEL_ID = "nova_screenshot"
        private const val NOTIFICATION_ID = 2003
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val VIRTUAL_DISPLAY_NAME = "NovaVisionCapture"
        private const val CAPTURE_TIMEOUT_MS = 3000L

        @Volatile
        private var instance: ScreenshotService? = null

        fun isRunning(): Boolean = instance != null

        /**
         * Launch the service with MediaProjection consent results.
         * Call this after the user approves the screen capture permission dialog.
         */
        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenshotService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenshotService::class.java))
        }

        /**
         * Capture a single screenshot of the current screen.
         *
         * Returns an in-memory Bitmap scaled to a max of 1024px on the longest dimension.
         * Returns null if the service is not running or capture fails.
         *
         * The caller is responsible for recycling the returned Bitmap.
         */
        suspend fun captureScreenshot(): Bitmap? {
            val service = instance ?: return null
            return service.capture()
        }
    }

    private var mediaProjection: MediaProjection? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        @Suppress("DEPRECATION")
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == -1 || resultData == null) {
            Log.e(TAG, "Invalid MediaProjection result")
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to obtain MediaProjection")
            stopSelf()
            return START_NOT_STICKY
        }

        instance = this
        Log.i(TAG, "ScreenshotService started — vision capture ready")

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        mediaProjection?.stop()
        mediaProjection = null
        Log.i(TAG, "ScreenshotService stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Capture a single frame from the screen via VirtualDisplay + ImageReader.
     *
     * Creates a temporary VirtualDisplay bound to an ImageReader, waits for one frame,
     * converts it to a Bitmap, then tears down the display. All in-memory.
     */
    private suspend fun capture(): Bitmap? {
        val projection = mediaProjection ?: return null

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        val imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )

        var virtualDisplay: VirtualDisplay? = null

        try {
            virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                screenWidth,
                screenHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null, // VirtualDisplay.Callback
                handler
            )

            // Wait for a frame with timeout
            val bitmap = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                waitForImage(imageReader)
            }

            return if (bitmap != null) {
                BitmapEncoder.scaleDown(bitmap, 1024)
            } else {
                Log.w(TAG, "Screenshot capture timed out")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot capture failed", e)
            return null
        } finally {
            virtualDisplay?.release()
            imageReader.close()
        }
    }

    /**
     * Suspend until ImageReader delivers a frame, then convert to Bitmap.
     */
    private suspend fun waitForImage(imageReader: ImageReader): Bitmap? {
        return suspendCancellableCoroutine { continuation ->
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: run {
                    if (continuation.isActive) continuation.resume(null)
                    return@setOnImageAvailableListener
                }

                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * image.width

                    val bitmap = Bitmap.createBitmap(
                        image.width + rowPadding / pixelStride,
                        image.height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    // Crop out the row padding if present
                    val cropped = if (rowPadding > 0) {
                        val result = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                        bitmap.recycle()
                        result
                    } else {
                        bitmap
                    }

                    if (continuation.isActive) continuation.resume(cropped)
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting image to bitmap", e)
                    if (continuation.isActive) continuation.resume(null)
                } finally {
                    image.close()
                }
            }, handler)

            continuation.invokeOnCancellation {
                imageReader.setOnImageAvailableListener(null, null)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nova Vision",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen capture for Nova's vision system"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nova Vision Active")
            .setContentText("Screen capture enabled for UI automation")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
