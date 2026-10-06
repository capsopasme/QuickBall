package io.github.chayanforyou.quickball.core

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.ui.floating.CropOverlayView
import io.github.chayanforyou.quickball.utils.ScreenshotStore
import io.github.chayanforyou.quickball.utils.ToastUtil
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Partial screenshot: hides QuickBall's own overlays, captures the display through the
 * accessibility service (no MediaProjection prompt, Android 11+), freezes it in a crop overlay
 * and saves, copies or shares the selected region.
 *
 * The screenshot stays a hardware bitmap (GPU memory, no copy) while the user crops; only the
 * background save copies it once to cut the region out. It is recycled as soon as neither the
 * overlay nor a pending save needs it.
 */
class PartialScreenshot(
    private val service: AccessibilityService,
    private val host: Host,
) {

    interface Host {
        /** Hide the ball, pill, wave bar, menu and toasts so they are not in the capture. */
        fun hideOverlaysForCapture()
        fun restoreOverlaysAfterCapture()
    }

    companion object {
        private const val TAG = "PartialScreenshot"

        // Long enough for the hidden overlay windows to leave the composited frame.
        private const val CAPTURE_DELAY_MS = 160L

        // Lets RenderThread finish with the bitmap after the overlay window is gone.
        private const val RECYCLE_DELAY_MS = 500L

        // AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (API 34)
        private const val ERROR_SECURE_WINDOW = 6

        private const val WORKER_IDLE_SECONDS = 30L
    }

    /** The captured screenshot and how many users (overlay, pending saves) still hold it. */
    private class Shot(val bitmap: Bitmap) {
        var refs = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    private var busy = false
    private var destroyed = false
    private var overlay: CropOverlayView? = null
    private var shot: Shot? = null

    private val windowManager: WindowManager?
        get() = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private val captureRunnable = Runnable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) capture()
    }

    fun start() {
        if (busy || destroyed) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast(R.string.shot_unsupported)
            return
        }
        busy = true
        host.hideOverlaysForCapture()
        handler.postDelayed(captureRunnable, CAPTURE_DELAY_MS)
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacks(captureRunnable)
        overlay?.let { view ->
            overlay = null
            runCatching { windowManager?.removeView(view) }
        }
        shot?.let { release(it) }
        shot = null
        executor?.shutdown()
        executor = null
        busy = false
    }

    // ------------------------------------------------------------------ capture

    @RequiresApi(Build.VERSION_CODES.R)
    private fun capture() {
        if (!busy) return
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        onCaptured(screenshot)
                    }

                    override fun onFailure(errorCode: Int) {
                        fail(errorCode)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot failed", e)
            fail(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun onCaptured(result: AccessibilityService.ScreenshotResult) {
        val buffer = result.hardwareBuffer
        // The bitmap keeps its own reference to the buffer, so ours can be closed right away.
        val bitmap = try {
            if (busy) Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wrap screenshot", e)
            null
        } finally {
            buffer.close()
        }
        if (!busy) return
        if (bitmap == null) {
            fail(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
            return
        }
        showOverlay(Shot(bitmap))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun showOverlay(captured: Shot) {
        val wm = windowManager
        if (wm == null) {
            release(captured)
            fail(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
            return
        }
        val bounds = wm.maximumWindowMetrics.bounds
        val view = CropOverlayView(service, captured.bitmap, Rect(bounds), cropListener)
        val params = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable (no FLAG_NOT_FOCUSABLE) so the back gesture reaches the overlay.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            windowAnimations = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show crop overlay", e)
            release(captured)
            fail(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
            return
        }
        shot = captured
        overlay = view
    }

    private val cropListener = object : CropOverlayView.Listener {
        override fun onCancel() = finish(null, null)

        override fun onConfirm(bitmapRect: Rect, action: CropOverlayView.Action) =
            finish(bitmapRect, action)
    }

    private fun finish(rect: Rect?, action: CropOverlayView.Action?) {
        val view = overlay ?: return
        val captured = shot
        if (rect != null && action != null && captured != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) {
            captured.refs++
            val appContext = service.applicationContext
            // One worker that ends after a short idle time instead of living as long as the
            // service once the first screenshot has been saved.
            val worker = executor ?: ThreadPoolExecutor(
                1, 1, WORKER_IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()
            ).apply { allowCoreThreadTimeOut(true) }.also { executor = it }
            worker.execute {
                val result = runCatching {
                    if (action == CropOverlayView.Action.SAVE) {
                        ScreenshotStore.saveToGallery(appContext, captured.bitmap, rect)
                    } else {
                        ScreenshotStore.saveToCache(appContext, captured.bitmap, rect)
                    }
                }
                handler.post {
                    release(captured)
                    // After the service went away its window token is dead: no toast/activity.
                    if (!destroyed) deliver(result, action)
                }
            }
        }
        view.dismiss { closeOverlay(view) }
    }

    private fun closeOverlay(view: CropOverlayView) {
        if (overlay !== view) return
        overlay = null
        runCatching { windowManager?.removeView(view) }
        shot?.let { release(it) }
        shot = null
        busy = false
        host.restoreOverlaysAfterCapture()
    }

    private fun release(captured: Shot) {
        captured.refs--
        if (captured.refs <= 0) {
            handler.postDelayed({ captured.bitmap.recycle() }, RECYCLE_DELAY_MS)
        }
    }

    private fun deliver(result: Result<Uri>, action: CropOverlayView.Action) {
        val uri = result.getOrElse {
            Log.e(TAG, "Failed to store screenshot", it)
            toast(R.string.shot_save_failed)
            return
        }
        when (action) {
            CropOverlayView.Action.SAVE -> toast(R.string.shot_saved)
            CropOverlayView.Action.COPY -> {
                try {
                    val clipboard =
                        service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newUri(service.contentResolver, "Screenshot", uri)
                    )
                    // Android 13+ shows its own clipboard confirmation.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        toast(R.string.shot_copied)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy screenshot", e)
                    toast(R.string.shot_save_failed)
                }
            }

            CropOverlayView.Action.SHARE -> {
                try {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    service.startActivity(
                        Intent.createChooser(send, service.getString(R.string.shot_share_title))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to share screenshot", e)
                }
            }
        }
    }

    private fun fail(errorCode: Int) {
        if (destroyed) return
        busy = false
        host.restoreOverlaysAfterCapture()
        toast(
            when (errorCode) {
                AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> R.string.shot_no_permission
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> R.string.shot_too_fast
                ERROR_SECURE_WINDOW -> R.string.shot_secure
                else -> R.string.shot_failed
            }
        )
    }

    private fun toast(@StringRes message: Int) {
        ToastUtil.show(service, service.getString(message))
    }
}
