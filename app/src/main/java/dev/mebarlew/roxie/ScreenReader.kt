package dev.mebarlew.roxie

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Lets the buddy look at the screen, only when you tap it. It never reads anything on its own. */
class ScreenReader : AccessibilityService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    /**
     * JPEG of the screen, or null when it can't be taken (Android 10 or older, a secure app like banking). On a
     * failure [onResult] also gets Android's `ERROR_TAKE_SCREENSHOT_*` code, so the bubble can say why. Too soon
     * after the last one is retried once. Runs [onResult] on a worker thread.
     */
    fun capture(retry: Boolean = true, onResult: (jpeg: ByteArray?, error: Int?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null, null)
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val jpeg = try {
                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { toJpeg(it) }
                } finally {
                    buffer.close()
                }
                onResult(jpeg, null)
            }

            override fun onFailure(errorCode: Int) {
                if (retry && errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    main.postDelayed({ capture(retry = false, onResult) }, SCREENSHOT_INTERVAL_MS)
                } else {
                    onResult(null, errorCode)
                }
            }
        })
    }

    private fun toJpeg(hardware: Bitmap): ByteArray {
        val soft = hardware.copy(Bitmap.Config.ARGB_8888, false)
        val small = if (soft.width > MAX_WIDTH) {
            Bitmap.createScaledBitmap(soft, MAX_WIDTH, soft.height * MAX_WIDTH / soft.width, true)
        } else {
            soft
        }
        return ByteArrayOutputStream().use { out ->
            small.compress(Bitmap.CompressFormat.JPEG, 75, out)
            out.toByteArray()
        }
    }

    companion object {
        /** Screenshots are scaled down to this width: plenty for the AI, small to upload. */
        private const val MAX_WIDTH = 720

        /** Android allows one screenshot per this long (AOSP's ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS, hidden). */
        private const val SCREENSHOT_INTERVAL_MS = 333L

        @Volatile
        var instance: ScreenReader? = null
            private set
    }
}
