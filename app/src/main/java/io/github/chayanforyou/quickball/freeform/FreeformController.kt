package io.github.chayanforyou.quickball.freeform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.domain.AppPreference
import io.github.chayanforyou.quickball.freeform.FreeformGeometry.Corner
import io.github.chayanforyou.quickball.freeform.FreeformProtocol.Reply
import io.github.chayanforyou.quickball.freeform.FreeformProtocol.TaskState
import io.github.chayanforyou.quickball.utils.ToastUtil
import io.github.chayanforyou.quickball.utils.performHapticFeedback
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * HyperOS-style small windows on top of the system's own freeform mode.
 *
 * The app's task really is a freeform window (so input, IME, secure content and multi-window
 * apps all behave natively), made always-on-top and given a lower per-task density so it keeps
 * the phone's layout. A root daemon ([RootDaemonClient]) makes those system calls. This class
 * owns the decoration drawn around the window: a caption bar (drag to move, maximize, minimize,
 * close), two resize grips, and bubbles for minimized windows.
 *
 * Only the system moves a task's surface, and only through a transition. So while a window is
 * dragged or resized, a snapshot of it follows the finger over a veil that hides the real
 * window, and the new bounds are committed once on release.
 *
 * One window is active at a time; opening another minimizes the current one into a bubble.
 * Main thread only.
 */
class FreeformController(private val service: AccessibilityService) : RootDaemonClient.Listener {

    companion object {
        private const val TAG = "FreeformController"

        /** Per-task density (WindowContainerTransaction#setDensityDpi) needs Android 13. */
        val isSupported: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        private const val MAX_BUBBLES = 3
        private const val CAPTION_DP = 32f
        private const val MARGIN_DP = 6f
        private const val BUBBLE_GAP_DP = 10f
        private const val FLING_UP_DP_PER_S = 1800f
        private const val WINDOWS_SCAN_DELAY_MS = 120L
        private const val DECOR_SHOW_DELAY_MS = 220L
        private const val LAUNCH_TIMEOUT_MS = 6_000L
        private const val CONFIRM_INTERVAL_MS = 120L
        private const val CONFIRM_ATTEMPTS = 8
        private const val RESIZE_SETTLE_MS = 350L
        private const val PROXY_FADE_MS = 120L
        private const val CONFIG_SYNC_DELAY_MS = 300L
        private const val OP_TIMEOUT_MS = 8_000L
        private const val SNAPSHOT_MAX_AGE_MS = 1_500L
    }

    private class SmallWindow(val taskId: Int, val packageName: String, var bounds: Box, var dpi: Int)

    private class Snapshot(val taskId: Int, val bitmap: Bitmap, val buffer: HardwareBuffer?) {
        private val takenAt = SystemClock.uptimeMillis()

        /** A frame from a press long ago would show stale content while dragging. */
        fun isFresh() = SystemClock.uptimeMillis() - takenAt < SNAPSHOT_MAX_AGE_MS

        fun release() {
            bitmap.recycle()
            buffer?.close()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = AppPreference.getInstance(service)
    private val daemon = RootDaemonClient(service).also { it.listener = this }

    private var density = service.resources.displayMetrics.density
    private var captionPx = px(CAPTION_DP)
    private var handlePx = px(ResizeHandleView.SIZE_DP)
    private var handleInsetPx = px(ResizeHandleView.INSET_DP)
    private var bubblePx = px(BubbleView.SIZE_DP)
    private var marginPx = px(MARGIN_DP)
    private var palette = FreeformPalette.from(service)
    private var geometry = buildGeometry()

    private var active: SmallWindow? = null
    /** Minimized windows, oldest first; each has a bubble. */
    private val minimized = ArrayList<SmallWindow>()
    private val bubbles = HashMap<Int, Bubble>()
    private var decor: Decor? = null
    private var gesture: Gesture? = null
    private var snapshot: Snapshot? = null
    private var snapshotPending = false

    /** A structural operation (open, restore...) is in flight; others are ignored meanwhile. */
    private var busy = false
    private var pendingPackage: String? = null
    private var destroyed = false

    // Inputs of the decoration's visibility
    private var decorReady = false
    private var screenHidden = false
    private var hiddenForCapture = false
    private var shadeOpen = false
    private var imeBox: Box? = null
    private var taskVisible = true

    // Window-change events are only subscribed while a small window is shown.
    private val baseEventTypes: Int
    private val baseFlags: Int
    private var trackingWindows = false

    init {
        val info = service.serviceInfo
        baseEventTypes = info?.eventTypes ?: AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        baseFlags = info?.flags ?: 0
    }

    // ==================== Public API ====================

    /** Turns the app in front into a small window. */
    fun openCurrentApp() {
        if (!checkReady()) return
        refreshGeometry()
        val bounds = defaultBounds()
        val dpi = geometry.dpiFor(bounds.width)
        busy = true
        daemon.call(
            FreeformProtocol.CONVERT_TOP, bounds.encode(), dpi, service.packageName,
            timeoutMs = OP_TIMEOUT_MS
        ) { reply ->
            busy = false
            if (destroyed) return@call
            if (!reply.ok) {
                report(reply.code)
                return@call
            }
            val taskId = reply.arg(0)?.toIntOrNull()
            val pkg = reply.arg(1)
            if (taskId == null || pkg == null) {
                report(FreeformProtocol.ERR_FAILED)
                return@call
            }
            attach(SmallWindow(taskId, pkg, Box.decode(reply.arg(2)) ?: bounds, dpi))
        }
    }

    /**
     * Opens [packageName] as a small window. [fallback] opens it normally; it runs when small
     * windows are unavailable (no root, unsupported Android), so the shortcut still works.
     */
    fun launchApp(packageName: String, fallback: () -> Unit) {
        if (!isSupported) {
            fallback()
            return
        }
        if (busy || gesture != null || destroyed) return
        minimized.firstOrNull { it.packageName == packageName }?.let {
            restore(it)
            return
        }
        if (active?.packageName == packageName) return
        val intent = service.packageManager.getLaunchIntentForPackage(packageName) ?: run {
            fallback()
            return
        }
        refreshGeometry()
        val bounds = defaultBounds()
        val dpi = geometry.dpiFor(bounds.width)
        busy = true
        daemon.call(
            FreeformProtocol.LAUNCH, packageName, bounds.encode(), dpi,
            timeoutMs = OP_TIMEOUT_MS
        ) { reply ->
            if (destroyed) return@call
            if (!reply.ok) {
                busy = false
                report(reply.code)
                if (reply.code == FreeformProtocol.ERR_NO_ROOT ||
                    reply.code == FreeformProtocol.ERR_ROOT_DENIED
                ) {
                    fallback()
                }
                return@call
            }
            when (reply.arg(0)) {
                "TASK" -> {
                    busy = false
                    val taskId = reply.arg(1)?.toIntOrNull() ?: return@call report(FreeformProtocol.ERR_FAILED)
                    attach(SmallWindow(taskId, packageName, Box.decode(reply.arg(3)) ?: bounds, dpi))
                }
                "PENDING" -> {
                    pendingPackage = packageName
                    main.postDelayed(pendingTimeout, LAUNCH_TIMEOUT_MS)
                    if (!startInFreeform(intent, bounds)) {
                        clearPending()
                        report(FreeformProtocol.ERR_FAILED)
                    }
                }
                else -> {
                    busy = false
                    report(FreeformProtocol.ERR_FAILED)
                }
            }
        }
    }

    /** Starts the root daemon ahead of time (e.g. when the menu opens) so opening is instant. */
    fun prewarm() {
        if (isSupported && !destroyed) daemon.ensureStarted { }
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && active != null) {
            main.removeCallbacks(scanWindows)
            main.postDelayed(scanWindows, WINDOWS_SCAN_DELAY_MS)
        }
    }

    /** Rotation, display size or dark mode changed. */
    fun onConfigurationChanged() {
        if (destroyed) return
        cancelGesture()
        density = service.resources.displayMetrics.density
        captionPx = px(CAPTION_DP)
        handlePx = px(ResizeHandleView.SIZE_DP)
        handleInsetPx = px(ResizeHandleView.INSET_DP)
        bubblePx = px(BubbleView.SIZE_DP)
        marginPx = px(MARGIN_DP)
        palette = FreeformPalette.from(service)
        refreshGeometry()
        decor?.applyPalette()
        bubbles.values.forEach { it.applyPalette() }
        layoutBubbles()
        // WM settles freeform bounds after a rotation; fit the window afterwards.
        main.removeCallbacks(configSync)
        main.postDelayed(configSync, CONFIG_SYNC_DELAY_MS)
    }

    /** Screen off / locked (true) or back (false). */
    fun onScreenStateChanged(hidden: Boolean) {
        if (screenHidden == hidden) return
        screenHidden = hidden
        if (hidden) cancelGesture()
        refreshDecor()
    }

    /** Partial screenshots hide every overlay while capturing. */
    fun setHiddenForCapture(hidden: Boolean) {
        hiddenForCapture = hidden
        if (hidden) cancelGesture()
        refreshDecor()
    }

    /** The service is going away: drop the decoration; the daemon restores the windows. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        main.removeCallbacksAndMessages(null)
        cancelGesture()
        decor?.remove()
        decor = null
        bubbles.values.forEach { it.remove() }
        bubbles.clear()
        active = null
        minimized.clear()
        releaseSnapshot()
        setWindowTracking(false)
        daemon.keepAlive = false
        daemon.listener = null
        daemon.stop()
    }

    // ==================== Daemon events ====================

    override fun onDaemonEvent(event: FreeformProtocol.Event) {
        if (destroyed) return
        when (event.type) {
            FreeformProtocol.EVT_REMOVED -> event.arg(0)?.toIntOrNull()?.let { forget(it) }
            FreeformProtocol.EVT_STATE -> TaskState.decode(event.args)?.let { applyState(it) }
            FreeformProtocol.EVT_FRONT -> event.arg(0)?.toIntOrNull()?.let { taskId ->
                minimized.firstOrNull { it.taskId == taskId }?.let { adoptFromOutside(it) }
            }
            FreeformProtocol.EVT_ATTACHED -> {
                val taskId = event.arg(0)?.toIntOrNull() ?: return
                val pkg = event.arg(1) ?: return
                val bounds = Box.decode(event.arg(2)) ?: return
                val dpi = event.arg(3)?.toIntOrNull() ?: geometry.dpiFor(bounds.width)
                val wasPending = pendingPackage == pkg
                if (wasPending) clearPending()
                // Also a trampoline's real task replacing the one just attached.
                if (wasPending || active?.packageName == pkg) attach(SmallWindow(taskId, pkg, bounds, dpi))
            }
            FreeformProtocol.EVT_LAUNCH_FAILED -> {
                val pkg = event.arg(0) ?: return
                if (pendingPackage == pkg) {
                    clearPending()
                    report(event.arg(1) ?: FreeformProtocol.ERR_FAILED)
                }
            }
        }
    }

    override fun onDaemonLost() {
        if (destroyed || !hasWindows()) return
        // A fresh daemon knows nothing; hand it the tasks again.
        daemon.call(FreeformProtocol.WATCH, FreeformProtocol.encodeIds(managedIds())) { reply ->
            if (destroyed) return@call
            if (reply.ok) {
                syncActive()
            } else {
                Log.w(TAG, "Daemon could not be restarted: ${reply.code}")
                dropEverything()
                report(reply.code)
            }
        }
    }

    // ==================== Window lifecycle ====================

    private fun attach(window: SmallWindow) {
        cancelGesture()
        val previous = active
        minimized.removeAll { it.taskId == window.taskId }
        bubbles.remove(window.taskId)?.remove()
        removeDecor()
        active = window
        taskVisible = true
        // After [active] is set, so the watch list sent from there already includes it.
        if (previous != null && previous.taskId != window.taskId) minimizeWindow(previous)
        showDecor(window)
        layoutBubbles()
        updateWatch()
        setWindowTracking(true)
    }

    private fun maximize() {
        val window = active ?: return
        detachActive()
        daemon.call(FreeformProtocol.MAXIMIZE, window.taskId) { reply ->
            if (!reply.ok && reply.code != FreeformProtocol.ERR_NO_TASK) report(reply.code)
        }
    }

    private fun close() {
        val window = active ?: return
        detachActive()
        daemon.call(FreeformProtocol.CLOSE, window.taskId) { reply ->
            if (!reply.ok && reply.code != FreeformProtocol.ERR_NO_TASK) report(reply.code)
        }
    }

    private fun minimizeActive() {
        val window = active ?: return
        // The watch list goes out from minimizeWindow, with this task still in it.
        detachActive(sendWatch = false)
        minimizeWindow(window)
    }

    /** Hides [window] behind everything and shows its bubble. */
    private fun minimizeWindow(window: SmallWindow) {
        minimized.removeAll { it.taskId == window.taskId }
        minimized += window
        while (minimized.size > MAX_BUBBLES) {
            val oldest = minimized.removeAt(0)
            bubbles.remove(oldest.taskId)?.remove()
            daemon.call(FreeformProtocol.RELEASE, oldest.taskId)
        }
        if (bubbles[window.taskId] == null) bubbles[window.taskId] = Bubble(window)
        layoutBubbles()
        refreshDecor()
        updateWatch()
        daemon.call(FreeformProtocol.MINIMIZE, window.taskId) { reply ->
            if (!reply.ok && reply.code == FreeformProtocol.ERR_NO_TASK) forget(window.taskId)
        }
    }

    /** Bubble tapped: bring the window back (the current one becomes a bubble). */
    private fun restore(window: SmallWindow) {
        if (busy || destroyed) return
        busy = true
        daemon.call(FreeformProtocol.RESTORE, window.taskId, window.dpi, timeoutMs = OP_TIMEOUT_MS) { reply ->
            busy = false
            if (destroyed) return@call
            if (!reply.ok) {
                if (reply.code == FreeformProtocol.ERR_NO_TASK) forget(window.taskId) else report(reply.code)
                return@call
            }
            Box.decode(reply.arg(0))?.let { window.bounds = it }
            attach(window)
        }
    }

    /** A minimized window was opened from elsewhere (launcher, recents): make it active again. */
    private fun adoptFromOutside(window: SmallWindow) {
        if (busy || destroyed) return
        daemon.call(FreeformProtocol.PIN, window.taskId, window.dpi) { reply ->
            if (destroyed) return@call
            if (reply.ok) attach(window) else if (reply.code == FreeformProtocol.ERR_NO_TASK) forget(window.taskId)
        }
    }

    private fun closeMinimized(window: SmallWindow) {
        minimized.remove(window)
        bubbles.remove(window.taskId)?.remove()
        layoutBubbles()
        updateWatch()
        daemon.call(FreeformProtocol.CLOSE, window.taskId)
    }

    private fun detachActive(sendWatch: Boolean = true) {
        cancelGesture()
        removeDecor()
        active = null
        if (sendWatch) updateWatch()
        setWindowTracking(false)
    }

    /** The task is gone (closed by the user, crashed, swiped away). */
    private fun forget(taskId: Int) {
        if (active?.taskId == taskId) {
            cancelGesture()
            removeDecor()
            active = null
            setWindowTracking(false)
        }
        if (minimized.removeAll { it.taskId == taskId }) {
            bubbles.remove(taskId)?.remove()
            layoutBubbles()
        }
        updateWatch()
    }

    private fun dropEverything() {
        cancelGesture()
        removeDecor()
        active = null
        minimized.clear()
        bubbles.values.forEach { it.remove() }
        bubbles.clear()
        setWindowTracking(false)
        daemon.keepAlive = false
    }

    private fun applyState(state: TaskState) {
        val window = active
        if (window != null && window.taskId == state.taskId) {
            if (state.windowingMode != FreeformProtocol.WINDOWING_MODE_FREEFORM) {
                // Left freeform by other means: give it back its normal density.
                detachActive()
                daemon.call(FreeformProtocol.RESET, window.taskId)
                return
            }
            taskVisible = state.visible
            if (gesture == null && !busy && !state.bounds.approxEquals(window.bounds)) {
                window.bounds = state.bounds
                decor?.layout(window.bounds)
            }
            refreshDecor()
            return
        }
        val parked = minimized.firstOrNull { it.taskId == state.taskId } ?: return
        when {
            state.windowingMode != FreeformProtocol.WINDOWING_MODE_FREEFORM -> {
                minimized.remove(parked)
                bubbles.remove(parked.taskId)?.remove()
                layoutBubbles()
                updateWatch()
                daemon.call(FreeformProtocol.RESET, parked.taskId)
            }
            state.visible -> adoptFromOutside(parked)
        }
    }

    // ==================== Pending launch ====================

    private val pendingTimeout = Runnable {
        if (pendingPackage != null) {
            clearPending()
            report(FreeformProtocol.ERR_FAILED)
        }
    }

    private fun clearPending() {
        pendingPackage = null
        busy = false
        main.removeCallbacks(pendingTimeout)
    }

    private fun startInFreeform(intent: Intent, bounds: Box): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = ActivityOptions.makeBasic()
            .setLaunchBounds(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom))
            .toBundle()
        options.putInt(FreeformProtocol.KEY_LAUNCH_WINDOWING_MODE, FreeformProtocol.WINDOWING_MODE_FREEFORM)
        service.startActivity(intent, options)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to start ${intent.component}", e)
        false
    }

    // ==================== Caption & resize input ====================

    private val captionListener = object : CaptionBarView.Listener {
        override fun onCaptionPressed() = captureSnapshot()

        override fun onDragStart() {
            beginGesture(null)
        }

        override fun onDragMove(dx: Float, dy: Float) {
            val g = gesture ?: return
            if (g.committing || g.corner != null) return
            g.current = geometry.moved(g.start, dx, dy)
            g.proxy.layout(g.current)
        }

        override fun onDragEnd(velocityY: Float, cancelled: Boolean) {
            val g = gesture ?: return
            if (g.committing || g.corner != null) return
            val flungUp = !cancelled && velocityY < -FLING_UP_DP_PER_S * density
            if (flungUp && active === g.window) {
                endGesture(settleMs = 0)
                minimizeActive()
                return
            }
            commit(g, resize = false)
        }

        override fun onCaptionButton(button: CaptionButton) {
            if (gesture != null || busy) return
            service.performHapticFeedback()
            when (button) {
                CaptionButton.MAXIMIZE -> maximize()
                CaptionButton.MINIMIZE -> minimizeActive()
                CaptionButton.CLOSE -> close()
            }
        }
    }

    private val resizeListener = object : ResizeHandleView.Listener {
        override fun onResizePressed(corner: Corner) = captureSnapshot()

        override fun onResizeStart(corner: Corner) {
            beginGesture(corner)
        }

        override fun onResizeMove(corner: Corner, dx: Float, dy: Float) {
            val g = gesture ?: return
            if (g.committing || g.corner != corner) return
            g.current = geometry.resized(g.start, corner, dx, dy)
            g.proxy.layout(g.current)
        }

        override fun onResizeEnd(corner: Corner, cancelled: Boolean) {
            val g = gesture ?: return
            if (g.committing || g.corner != corner) return
            commit(g, resize = true)
        }
    }

    private fun beginGesture(corner: Corner?) {
        val window = active ?: return
        if (gesture != null || busy || destroyed) return
        captureSnapshot()
        val icon = loadIcon(window.packageName)
        val g = Gesture(window, corner, icon)
        gesture = g
        usableSnapshot(window.taskId)?.let { g.proxy.setBitmap(it.bitmap) }
        decor?.setGestureHidden(true)
    }

    /** Sends the final bounds once; the proxy stays until the window has really moved. */
    private fun commit(g: Gesture, resize: Boolean) {
        val window = g.window
        val target = g.current
        if (target.approxEquals(window.bounds, 1) || active !== window) {
            endGesture(settleMs = 0)
            return
        }
        g.committing = true
        val dpi = if (resize) geometry.dpiFor(target.width) else window.dpi
        val callback = { reply: Reply ->
            if (gesture === g) {
                if (!reply.ok) {
                    endGesture(settleMs = 0)
                    if (reply.code == FreeformProtocol.ERR_NO_TASK) forget(window.taskId) else report(reply.code)
                } else {
                    window.bounds = target
                    window.dpi = dpi
                    saveWindowPrefs(target)
                    confirmBounds(g, target, attempt = 0, settleMs = if (resize) RESIZE_SETTLE_MS else 0)
                }
            }
        }
        if (resize) {
            daemon.call(FreeformProtocol.RESIZE, window.taskId, target.encode(), dpi, callback = callback)
        } else {
            daemon.call(FreeformProtocol.MOVE, window.taskId, target.encode(), callback = callback)
        }
    }

    private fun confirmBounds(g: Gesture, target: Box, attempt: Int, settleMs: Long) {
        daemon.call(FreeformProtocol.INFO, g.window.taskId) { reply ->
            if (gesture !== g) return@call
            val state = if (reply.ok) TaskState.decode(reply.args) else null
            when {
                state == null -> endGesture(settleMs)
                state.bounds.approxEquals(target) -> endGesture(settleMs)
                attempt + 1 >= CONFIRM_ATTEMPTS -> {
                    // The system placed it elsewhere; follow it.
                    g.window.bounds = state.bounds
                    endGesture(settleMs)
                }
                else -> main.postDelayed(
                    { if (gesture === g) confirmBounds(g, target, attempt + 1, settleMs) },
                    CONFIRM_INTERVAL_MS
                )
            }
        }
    }

    /** Removes the veil, fades the proxy out and brings the decoration back. */
    private fun endGesture(settleMs: Long) {
        val g = gesture ?: return
        gesture = null
        if (settleMs > 0) {
            // A relaunched app needs a moment to draw at its new size; keep covering it.
            settling = g
            main.postDelayed(settleDone, settleMs)
        } else {
            finishGesture(g)
        }
    }

    /** A committed gesture whose proxy still covers the window while the app redraws. */
    private var settling: Gesture? = null

    private val settleDone = Runnable {
        val g = settling ?: return@Runnable
        settling = null
        finishGesture(g)
    }

    private fun finishGesture(g: Gesture) {
        g.veil.remove()
        g.proxy.fadeOut { releaseSnapshot() }
        active?.let { decor?.layout(it.bounds) }
        decor?.setGestureHidden(false)
        refreshDecor()
    }

    private fun cancelGesture() {
        settling?.let { g ->
            settling = null
            main.removeCallbacks(settleDone)
            g.veil.remove()
            g.proxy.remove()
        }
        val g = gesture
        gesture = null
        if (g != null) {
            g.veil.remove()
            g.proxy.remove()
        }
        releaseSnapshot()
        decor?.setGestureHidden(false)
        active?.let { decor?.layout(it.bounds) }
    }

    // ==================== Snapshot ====================

    private fun captureSnapshot() {
        val window = active ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (snapshotPending) return
        val current = snapshot
        if (current != null && current.taskId == window.taskId && current.isFresh()) return
        captureWindow(window)
    }

    private fun usableSnapshot(taskId: Int): Snapshot? =
        snapshot?.takeIf { it.taskId == taskId && it.isFresh() }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun captureWindow(window: SmallWindow) {
        val windowId = findAppWindowId(window.bounds) ?: return
        snapshotPending = true
        try {
            service.takeScreenshotOfWindow(
                windowId,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        snapshotPending = false
                        val buffer = result.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        if (bitmap == null || destroyed || active?.taskId != window.taskId) {
                            bitmap?.recycle()
                            buffer.close()
                            return
                        }
                        val previous = snapshot
                        snapshot = Snapshot(window.taskId, bitmap, buffer)
                        gesture?.takeIf { it.window.taskId == window.taskId }?.proxy?.setBitmap(bitmap)
                        // Nothing draws the old frame any more (a fading proxy skips recycled ones).
                        previous?.release()
                    }

                    override fun onFailure(errorCode: Int) {
                        snapshotPending = false
                    }
                }
            )
        } catch (e: Exception) {
            snapshotPending = false
            Log.w(TAG, "Window screenshot failed", e)
        }
    }

    /** The app window filling the task, matched by bounds (task ids aren't public here). */
    private fun findAppWindowId(bounds: Box): Int? {
        val windows = try {
            service.windows
        } catch (_: Exception) {
            return null
        }
        val r = Rect()
        var bestId: Int? = null
        var bestArea = 0L
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            w.getBoundsInScreen(r)
            val inside = r.left >= bounds.left - 2 && r.top >= bounds.top - 2 &&
                    r.right <= bounds.right + 2 && r.bottom <= bounds.bottom + 2
            if (!inside) continue
            val area = r.width().toLong() * r.height()
            if (area > bestArea) {
                bestArea = area
                bestId = w.id
            }
        }
        return bestId
    }

    private fun releaseSnapshot() {
        // Never while a proxy still draws it.
        if (gesture != null) return
        snapshot?.release()
        snapshot = null
    }

    // ==================== Visibility ====================

    private val scanWindows = Runnable { scanWindowsNow() }

    /** Notification shade / keyguard over the window, and the keyboard over the grips. */
    private fun scanWindowsNow() {
        if (active == null || destroyed) return
        val windows: List<AccessibilityWindowInfo> = try {
            service.windows
        } catch (_: Exception) {
            emptyList()
        }
        val expected = active?.bounds ?: return
        val r = Rect()
        var shade = false
        var ime: Box? = null
        var windowWhereExpected = false
        val screen = geometry.screen
        for (w in windows) {
            when (w.type) {
                AccessibilityWindowInfo.TYPE_SYSTEM -> {
                    w.getBoundsInScreen(r)
                    if (r.height() >= screen.height * 0.6f && r.width() >= screen.width * 0.6f) shade = true
                }
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> {
                    w.getBoundsInScreen(r)
                    ime = Box(r.left, r.top, r.right, r.bottom)
                }
                AccessibilityWindowInfo.TYPE_APPLICATION -> {
                    w.getBoundsInScreen(r)
                    if (Box(r.left, r.top, r.right, r.bottom).approxEquals(expected)) windowWhereExpected = true
                }
            }
        }
        val changed = shade != shadeOpen || ime != imeBox
        shadeOpen = shade
        imeBox = ime
        if (shade && gesture != null) cancelGesture()
        if (changed) refreshDecor()
        // Ask the daemon only when the window isn't where we think it is (moved, hidden, gone).
        if (!windowWhereExpected || !taskVisible) syncActive()
    }

    private fun refreshDecor() {
        val window = active
        val showWindowDecor = window != null && decorReady && !screenHidden &&
                !hiddenForCapture && !shadeOpen && taskVisible
        decor?.let { d ->
            // Visibility can't change mid-gesture: hiding the touched window would cancel it.
            if (gesture == null) {
                d.setShown(showWindowDecor)
                d.setHandlesShown(showWindowDecor && window != null && !imeCoversGrips(window.bounds))
            }
        }
        val showBubbles = !screenHidden && !hiddenForCapture && !shadeOpen
        bubbles.values.forEach { it.setShown(showBubbles) }
    }

    private fun imeCoversGrips(bounds: Box): Boolean {
        val ime = imeBox ?: return false
        val reach = handlePx - handleInsetPx
        val grips = Box(bounds.left - reach, bounds.bottom - handleInsetPx, bounds.right + reach, bounds.bottom + reach)
        return ime.intersects(grips)
    }

    private fun showDecor(window: SmallWindow) {
        removeDecor()
        decorReady = false
        decor = Decor().also { it.add(window.bounds) }
        main.removeCallbacks(decorReadyRunnable)
        // Roughly when the open transition settles.
        main.postDelayed(decorReadyRunnable, DECOR_SHOW_DELAY_MS)
    }

    private val decorReadyRunnable = Runnable {
        decorReady = true
        refreshDecor()
    }

    private fun removeDecor() {
        main.removeCallbacks(decorReadyRunnable)
        decor?.remove()
        decor = null
        decorReady = false
    }

    private fun syncActive() {
        val window = active ?: return
        daemon.call(FreeformProtocol.INFO, window.taskId) { reply ->
            if (destroyed || active !== window) return@call
            when {
                reply.ok -> TaskState.decode(reply.args)?.let { applyState(it) }
                reply.code == FreeformProtocol.ERR_NO_TASK -> forget(window.taskId)
            }
        }
    }

    private val configSync = Runnable { fitActiveToScreen() }

    /** After a rotation or display size change: keep the window on screen at the right density. */
    private fun fitActiveToScreen() {
        val window = active ?: return
        daemon.call(FreeformProtocol.INFO, window.taskId) { reply ->
            if (destroyed || active !== window || gesture != null) return@call
            val state = (if (reply.ok) TaskState.decode(reply.args) else null) ?: return@call
            val fitted = geometry.clamp(state.bounds)
            val dpi = geometry.dpiFor(fitted.width)
            window.bounds = state.bounds
            if (!fitted.approxEquals(state.bounds) || dpi != window.dpi) {
                val apply = { r: Reply ->
                    if (r.ok && active === window) {
                        window.bounds = fitted
                        window.dpi = dpi
                        decor?.layout(fitted)
                    }
                }
                if (dpi != window.dpi) {
                    daemon.call(FreeformProtocol.RESIZE, window.taskId, fitted.encode(), dpi, callback = apply)
                } else {
                    daemon.call(FreeformProtocol.MOVE, window.taskId, fitted.encode(), callback = apply)
                }
            } else {
                decor?.layout(window.bounds)
            }
        }
    }

    // ==================== Bookkeeping ====================

    private fun hasWindows() = active != null || minimized.isNotEmpty()

    private fun managedIds(): List<Int> = buildList {
        active?.let { add(it.taskId) }
        minimized.forEach { add(it.taskId) }
    }

    /** Tells the daemon which tasks to watch (and restore if this process dies). */
    private fun updateWatch() {
        val managed = hasWindows()
        daemon.keepAlive = managed
        if (managed || daemon.isRunning) {
            daemon.call(FreeformProtocol.WATCH, FreeformProtocol.encodeIds(managedIds()))
        }
    }

    private fun setWindowTracking(enabled: Boolean) {
        if (enabled == trackingWindows) return
        val info: AccessibilityServiceInfo = service.serviceInfo ?: return
        if (enabled) {
            info.eventTypes = info.eventTypes or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        } else {
            info.eventTypes = baseEventTypes
            info.flags = baseFlags
            shadeOpen = false
            imeBox = null
        }
        try {
            service.serviceInfo = info
            trackingWindows = enabled
        } catch (e: Exception) {
            Log.w(TAG, "Could not update accessibility service info", e)
        }
    }

    private fun checkReady(): Boolean {
        if (destroyed || busy || gesture != null) return false
        if (!isSupported) {
            report(FreeformProtocol.ERR_NO_FREEFORM)
            return false
        }
        return true
    }

    private fun report(code: String) {
        val message = when (code) {
            FreeformProtocol.ERR_NO_ROOT, FreeformProtocol.ERR_ROOT_DENIED -> R.string.toast_freeform_no_root
            FreeformProtocol.ERR_NO_TASK -> R.string.toast_freeform_no_app
            FreeformProtocol.ERR_NO_FREEFORM -> R.string.toast_freeform_unsupported
            else -> R.string.toast_freeform_failed
        }
        ToastUtil.show(service, service.getString(message))
    }

    // ==================== Geometry ====================

    private fun px(dp: Float): Int = (dp * density + 0.5f).toInt()

    private fun refreshGeometry() {
        geometry = buildGeometry()
    }

    private fun buildGeometry(): FreeformGeometry {
        var width: Int
        var height: Int
        var insetLeft = 0
        var insetTop = 0
        var insetRight = 0
        var insetBottom = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Same source as the ball's own screen size (works from the service context).
            val metrics = windowManager.maximumWindowMetrics
            width = metrics.bounds.width()
            height = metrics.bounds.height()
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            insetLeft = insets.left
            insetTop = insets.top
            insetRight = insets.right
            insetBottom = insets.bottom
        } else {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            width = size.x
            height = size.y
        }
        width = max(1, width)
        height = max(1, height)
        val screen = ScreenSpec(
            width = width,
            height = height,
            densityDpi = service.resources.configuration.densityDpi,
            insetLeft = insetLeft,
            insetTop = insetTop,
            insetRight = insetRight,
            insetBottom = insetBottom,
        )
        return FreeformGeometry(
            screen = screen,
            captionPx = captionPx,
            bottomReservePx = handlePx - handleInsetPx,
            marginPx = marginPx,
        )
    }

    private fun defaultBounds(): Box = geometry.initialBounds(
        scale = prefs.freeformScale,
        centerXFraction = prefs.freeformCenterX,
        topFraction = prefs.freeformTop,
    )

    private fun saveWindowPrefs(bounds: Box) {
        val screen = geometry.screen
        prefs.saveFreeformWindow(
            scale = geometry.scaleOf(bounds.width),
            centerX = bounds.centerX.toFloat() / screen.width,
            top = bounds.top.toFloat() / screen.height,
        )
    }

    private fun loadIcon(packageName: String): Drawable? = try {
        service.packageManager.getApplicationIcon(packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    // ==================== Overlay helpers ====================

    private fun addOverlay(view: View, params: WindowManager.LayoutParams): Boolean = try {
        windowManager.addView(view, params)
        true
    } catch (e: Exception) {
        // e.g. BadTokenException once the accessibility connection is gone.
        Log.w(TAG, "Failed to add overlay", e)
        false
    }

    private fun updateOverlay(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun removeOverlay(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    // ==================== Overlay windows ====================

    /** Caption bar above the window plus the two resize grips below its corners. */
    private inner class Decor {
        private val caption = CaptionBarView(service, captionListener)
        private val captionParams = freeformOverlayParams(1, 1, touchable = true)
        private val left = ResizeHandleView(service, Corner.BOTTOM_LEFT, resizeListener)
        private val leftParams = freeformOverlayParams(handlePx, handlePx, touchable = true)
        private val right = ResizeHandleView(service, Corner.BOTTOM_RIGHT, resizeListener)
        private val rightParams = freeformOverlayParams(handlePx, handlePx, touchable = true)
        private var added = false

        fun add(bounds: Box) {
            applyPalette()
            setParams(bounds)
            caption.visibility = View.INVISIBLE
            left.visibility = View.INVISIBLE
            right.visibility = View.INVISIBLE
            added = addOverlay(caption, captionParams)
            if (added) {
                addOverlay(left, leftParams)
                addOverlay(right, rightParams)
            }
        }

        fun layout(bounds: Box) {
            if (!added) return
            setParams(bounds)
            updateOverlay(caption, captionParams)
            updateOverlay(left, leftParams)
            updateOverlay(right, rightParams)
        }

        fun setShown(shown: Boolean) {
            val visibility = if (shown) View.VISIBLE else View.INVISIBLE
            if (caption.visibility != visibility) caption.visibility = visibility
        }

        fun setHandlesShown(shown: Boolean) {
            val visibility = if (shown) View.VISIBLE else View.INVISIBLE
            if (left.visibility != visibility) left.visibility = visibility
            if (right.visibility != visibility) right.visibility = visibility
        }

        /** Transparent, not invisible: the touched window must keep its touch stream. */
        fun setGestureHidden(hidden: Boolean) {
            val alpha = if (hidden) 0f else 1f
            caption.alpha = alpha
            left.alpha = alpha
            right.alpha = alpha
        }

        fun applyPalette() {
            caption.palette = palette
            left.palette = palette
            right.palette = palette
        }

        fun remove() {
            if (!added) return
            added = false
            removeOverlay(caption)
            removeOverlay(left)
            removeOverlay(right)
        }

        private fun setParams(bounds: Box) {
            captionParams.x = bounds.left
            captionParams.y = bounds.top - captionPx
            captionParams.width = bounds.width
            captionParams.height = captionPx
            leftParams.width = handlePx
            leftParams.height = handlePx
            leftParams.x = bounds.left - (handlePx - handleInsetPx)
            leftParams.y = bounds.bottom - handleInsetPx
            rightParams.width = handlePx
            rightParams.height = handlePx
            rightParams.x = bounds.right - handleInsetPx
            rightParams.y = bounds.bottom - handleInsetPx
        }
    }

    /** Snapshot (or veil) window used while dragging or resizing. */
    private inner class SnapshotWindow(bounds: Box, private val withCaption: Boolean, icon: Drawable?) {
        val view = SnapshotView(service, icon, withCaption, captionPx).also { it.palette = palette }
        private val params = freeformOverlayParams(1, 1, touchable = false)
        private var added = false

        init {
            setParams(bounds)
            added = addOverlay(view, params)
        }

        fun layout(bounds: Box) {
            if (!added) return
            setParams(bounds)
            updateOverlay(view, params)
        }

        fun setBitmap(bitmap: Bitmap?) = view.setBitmap(bitmap)

        fun fadeOut(onDone: () -> Unit) {
            if (!added) {
                onDone()
                return
            }
            view.animate().alpha(0f).setDuration(PROXY_FADE_MS).withEndAction {
                remove()
                onDone()
            }.start()
        }

        fun remove() {
            if (!added) return
            added = false
            view.animate().cancel()
            view.setBitmap(null)
            removeOverlay(view)
        }

        private fun setParams(bounds: Box) {
            params.x = bounds.left
            params.y = if (withCaption) bounds.top - captionPx else bounds.top
            params.width = bounds.width
            params.height = if (withCaption) bounds.height + captionPx else bounds.height
        }
    }

    /** A drag or resize in progress. */
    private inner class Gesture(val window: SmallWindow, val corner: Corner?, icon: Drawable?) {
        val start: Box = window.bounds
        var current: Box = window.bounds
        var committing = false
        /** Hides the real window, which stays where it is until the commit. */
        val veil = SnapshotWindow(start, withCaption = false, icon = icon)
        /** Follows the finger; added after the veil, so drawn above it. */
        val proxy = SnapshotWindow(start, withCaption = true, icon = icon)
    }

    // ==================== Bubbles ====================

    private var bubbleDragOrigin: Pair<Int, Int>? = null

    private val bubbleListener = object : BubbleView.Listener {
        override fun onBubbleTap(view: BubbleView) {
            val window = windowOf(view) ?: return
            service.performHapticFeedback()
            restore(window)
        }

        override fun onBubbleLongPress(view: BubbleView) {
            val window = windowOf(view) ?: return
            service.performHapticFeedback()
            closeMinimized(window)
        }

        override fun onBubbleDrag(view: BubbleView, dx: Float, dy: Float) {
            val origin = bubbleDragOrigin ?: Pair(bubbleX(), bubbleStackTop()).also { bubbleDragOrigin = it }
            val x = (origin.first + dx).roundToInt()
            val top = (origin.second + dy).roundToInt()
            minimized.forEachIndexed { index, window ->
                bubbles[window.taskId]?.moveTo(x, top + index * bubbleStep())
            }
        }

        override fun onBubbleDragEnd(view: BubbleView) {
            val origin = bubbleDragOrigin ?: return
            bubbleDragOrigin = null
            val first = minimized.firstOrNull()?.let { bubbles[it.taskId] } ?: return
            val screen = geometry.screen
            prefs.freeformBubbleOnRight = first.x + bubblePx / 2 > screen.width / 2
            prefs.freeformBubbleY = first.y.toFloat() / screen.height
            if (origin.first != first.x || origin.second != first.y) layoutBubbles()
        }
    }

    private fun windowOf(view: BubbleView): SmallWindow? {
        val taskId = bubbles.entries.firstOrNull { it.value.view === view }?.key ?: return null
        return minimized.firstOrNull { it.taskId == taskId }
    }

    private fun bubbleStep() = bubblePx + px(BUBBLE_GAP_DP)

    private fun bubbleX(): Int {
        val screen = geometry.screen
        return if (prefs.freeformBubbleOnRight) {
            screen.width - screen.insetRight - bubblePx - marginPx
        } else {
            screen.insetLeft + marginPx
        }
    }

    private fun bubbleStackTop(): Int {
        val screen = geometry.screen
        val stackHeight = max(1, minimized.size) * bubbleStep()
        val minTop = screen.insetTop + marginPx
        val maxTop = max(minTop, screen.height - screen.insetBottom - marginPx - stackHeight)
        return (prefs.freeformBubbleY * screen.height).roundToInt().coerceIn(minTop, maxTop)
    }

    private fun layoutBubbles() {
        if (bubbleDragOrigin != null) return
        val x = bubbleX()
        val top = bubbleStackTop()
        minimized.forEachIndexed { index, window ->
            bubbles[window.taskId]?.moveTo(x, top + index * bubbleStep())
        }
    }

    private inner class Bubble(window: SmallWindow) {
        val view = BubbleView(service, loadIcon(window.packageName), bubbleListener).also { it.palette = palette }
        private val params = freeformOverlayParams(bubblePx, bubblePx, touchable = true)
        private var added = false
        val x: Int get() = params.x
        val y: Int get() = params.y

        init {
            params.x = bubbleX()
            params.y = bubbleStackTop()
            if (screenHidden || hiddenForCapture || shadeOpen) view.visibility = View.INVISIBLE
            added = addOverlay(view, params)
        }

        fun moveTo(x: Int, y: Int) {
            if (!added || (params.x == x && params.y == y)) return
            params.x = x
            params.y = y
            params.width = bubblePx
            params.height = bubblePx
            updateOverlay(view, params)
        }

        fun setShown(shown: Boolean) {
            val visibility = if (shown) View.VISIBLE else View.INVISIBLE
            if (view.visibility != visibility) view.visibility = visibility
        }

        fun applyPalette() {
            view.palette = palette
        }

        fun remove() {
            if (!added) return
            added = false
            removeOverlay(view)
        }
    }
}
