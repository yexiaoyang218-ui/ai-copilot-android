package com.aisocial.copilot.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aisocial.copilot.MainActivity
import com.aisocial.copilot.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Grabs exactly one frame through MediaProjection, then shuts the projection down.
 *
 * Running inside a `mediaProjection` foreground service is mandatory on Android 14+:
 * `startForeground()` has to happen before `getMediaProjection()`.
 */
class MediaProjectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForegroundSafely()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            CaptureBus.publishError("没有获得屏幕捕获授权，请重试。")
            stopSelf()
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                captureFrame(resultCode, resultData)
            } catch (error: Throwable) {
                CaptureBus.publishError("截取屏幕失败：${error.message ?: "未知错误"}")
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafely() {
        val notification = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure {
            // Some OEMs reject the type constant; a plain startForeground keeps the
            // service alive long enough to snap the frame.
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    private suspend fun captureFrame(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
            ?: throw IllegalStateException("MediaProjectionManager 不可用")

        val projection = manager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("无法创建 MediaProjection")

        val handlerThread = HandlerThread("capture-handler").apply { start() }
        val handler = Handler(handlerThread.looper)

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Projection torn down by the system; nothing left to release here.
            }
        }, handler)

        val bounds = currentDisplayBounds()
        val width = (bounds.width()).coerceAtLeast(1)
        val height = (bounds.height()).coerceAtLeast(1)
        val densityDpi = resources.displayMetrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        var virtualDisplay: VirtualDisplay? = null

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "ai-copilot-capture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )

            val startedAt = System.currentTimeMillis()
            var result: Bitmap? = null
            while (System.currentTimeMillis() - startedAt < CAPTURE_TIMEOUT_MS) {
                val image: Image? = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        // Ignore the very first frames so the system UI settles after the
                        // permission dialog disappears.
                        val elapsed = System.currentTimeMillis() - startedAt
                        if (elapsed >= SETTLE_MS) {
                            result = imageToBitmap(image)
                        }
                    } finally {
                        image.close()
                    }
                }
                if (result != null) break
                delay(POLL_INTERVAL_MS)
            }

            if (result == null) {
                CaptureBus.publishError("截图超时，没有拿到画面。请重试。")
            } else {
                val frame: Bitmap = result
                CaptureBus.publishFrame(frame)
            }
        } finally {
            runCatching { virtualDisplay?.release() }
            runCatching { reader.close() }
            runCatching { projection.stop() }
            runCatching { handlerThread.quitSafely() }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayBounds(): Rect {
        val windowManager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)

        return if (paddedWidth == image.width) {
            padded
        } else {
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            cropped
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(getString(R.string.notif_capture_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_capture),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "ai_copilot_capture"
        private const val NOTIFICATION_ID = 0x5C11
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val SETTLE_MS = 320L
        private const val POLL_INTERVAL_MS = 40L
        private const val CAPTURE_TIMEOUT_MS = 5_000L

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MediaProjectionService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
