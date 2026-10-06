package io.github.chayanforyou.quickball.core

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.PathInterpolator
import androidx.core.content.getSystemService
import io.github.chayanforyou.quickball.domain.AppPreference
import io.github.chayanforyou.quickball.domain.models.GestureBinding
import io.github.chayanforyou.quickball.domain.models.MenuAction
import io.github.chayanforyou.quickball.domain.handlers.QuickBallActionHandler
import io.github.chayanforyou.quickball.domain.models.QuickBallMenuItem
import io.github.chayanforyou.quickball.ui.floating.GestureListener
import io.github.chayanforyou.quickball.ui.floating.FloatTouchView
import io.github.chayanforyou.quickball.ui.floating.FloatPanelView
import io.github.chayanforyou.quickball.ui.floating.SideKickView
import io.github.chayanforyou.quickball.ui.floating.VolumeHud
import io.github.chayanforyou.quickball.ui.floating.WaveBarView
import io.github.chayanforyou.quickball.utils.DensityUtils
import io.github.chayanforyou.quickball.utils.ToastUtil
import io.github.chayanforyou.quickball.utils.getScreenSize
import io.github.chayanforyou.quickball.utils.performHapticFeedback
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@SuppressLint("AccessibilityPolicy")
class QuickBallService : AccessibilityService() {

    companion object {
        private const val TAG = "QuickBallService"

        const val ACTION_ENABLE = "io.github.chayanforyou.quickball.action.ENABLE"
        const val ACTION_DISABLE = "io.github.chayanforyou.quickball.action.DISABLE"
        const val ACTION_STASH = "io.github.chayanforyou.quickball.action.STASH"
        const val ACTION_UNSTASH = "io.github.chayanforyou.quickball.action.UNSTASH"
        const val ACTION_UPDATE_BALL = "io.github.chayanforyou.quickball.action.UPDATE_BALL"
        const val ACTION_UPDATE_PILL = "io.github.chayanforyou.quickball.action.UPDATE_PILL"
        const val ACTION_UPDATE_WAVE = "io.github.chayanforyou.quickball.action.UPDATE_WAVE"

        private const val APP_PACKAGE_PREFIX = "io.github.chayanforyou.quickball"
        private val EXCLUDED_APPS = setOf(
            "com.android.systemui",
            "com.android.intentresolver",
            // AOSP/LineageOS name; GrantPermissionsActivity is a real activity, so without it a
            // runtime-permission dialog counted as a new foreground app and showed the ball
            // inside an auto-hidden app.
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "android.uid.system:1000",
            "com.google.android.googlequicksearchbox",
            "android",
            "com.google.android.gms",
            "com.google.android.webview"
        )

        const val EDGE_PADDING_DP = 6f
        const val WAVE_GAP_DP = 8f
        const val STASH_DELAY_MS = 2500L
    }

    // Window Managers & Views
    private var windowManager: WindowManager? = null
    private var fabView: FloatTouchView? = null
    private var fabParams: WindowManager.LayoutParams? = null
    private var pillView: SideKickView? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var menuView: FloatPanelView? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var waveView: WaveBarView? = null
    private var waveParams: WindowManager.LayoutParams? = null
    private var actionHandler: QuickBallActionHandler? = null

    // Layout Boundaries & Sizing
    private val fabSizePx get() = DensityUtils.dp2px(floatingBallSize)
    private val edgePaddingPx by lazy { DensityUtils.dp2px(EDGE_PADDING_DP) }
    private val topBoundary by lazy { DensityUtils.dp2px(100f) }
    private val bottomBoundary by lazy { DensityUtils.dp2px(100f) }

    // Position State (Portrait vs Landscape)
    private data class EdgePosition(
        val isOnRight: Boolean = true,
        val yFraction: Float = 0.5f
    )

    private var portraitPosition = EdgePosition()
    private var landscapePosition = EdgePosition()

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private val prefs by lazy { AppPreference.getInstance(this) }

    private var currentPosition: EdgePosition
        get() = if (isLandscape) landscapePosition else portraitPosition
        set(value) {
            if (isLandscape) {
                landscapePosition = value
                prefs.saveLandscapePosition(value.isOnRight, value.yFraction)
            } else {
                portraitPosition = value
                prefs.savePortraitPosition(value.isOnRight, value.yFraction)
            }
        }

    private var isOnRight: Boolean
        get() = currentPosition.isOnRight
        set(value) {
            currentPosition = currentPosition.copy(isOnRight = value)
        }

    private var savedYFraction: Float
        get() = currentPosition.yFraction
        set(value) {
            currentPosition = currentPosition.copy(yFraction = value)
        }

    // State Variables
    private var isExpanded = false
    private var fabX = 0
    private var fabY = 0

    // Stash / Auto-Hide State
    private var isDragging = false
    private var isStashed = false
    private var stashAlpha = 0.4f
    private var isStashing = false
    private var fabAnimator: ValueAnimator? = null
    private val stashHandler = Handler(Looper.getMainLooper())
    private val stashRunnable = Runnable { onInactivityTimeout() }
    private var lastForegroundPackage = ""
    // Last app screen the visibility engine reacted to; null forces the next app event through.
    private var lastHandledPackage: String? = null
    private val recentAppTracker by lazy { RecentAppTracker(this) }
    private var isScreenReceiverRegistered = false

    // System Services & State
    private val keyguard by lazy { getSystemService<KeyguardManager>() as KeyguardManager }
    private val isLocked get() = keyguard.isKeyguardLocked

    // Preference Getters
    private val floatingBallSize get() = prefs.ballSize
    private val isStickToEdge get() = prefs.isStickToEdgeEnabled
    private val isEnabled get() = prefs.isQuickBallEnabled
    private val autoHideApps get() = prefs.autoHideApps
    private val showOnLockScreen get() = prefs.isShowOnLockScreenEnabled
    private val hideForLandscape get() = prefs.isHideOnLandscapeEnabled && isLandscape

    /* -------------------- Lifecycle -------------------- */

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The system may hand a reconnect to an instance that is still alive from an earlier
        // connection; drop anything left from it (stale views, a registered receiver) first.
        teardown()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initFloatingBall()
        registerScreenReceiver()
    }

    /**
     * The settings screen talks to the service with startService(). Handle the command, then
     * drop the "started" state right away: the system's accessibility binding alone must decide
     * the service's lifetime. Left started (and sticky), the instance outlived a disabled
     * accessibility service, so a later re-enable reused its stale views (no ball appeared) and
     * its still-registered screen receiver tried to add overlay windows without a token.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (windowManager != null) {
            when (intent?.action) {
                ACTION_ENABLE -> showBall()
                ACTION_DISABLE -> hideBall()
                ACTION_STASH -> stashFab()
                ACTION_UNSTASH -> unstashFab()
                ACTION_UPDATE_BALL -> updateBall()
                ACTION_UPDATE_PILL -> updatePill()
                ACTION_UPDATE_WAVE -> updateWave()
            }
        }
        // While the system is bound this only clears the started state; it never stops a
        // connected accessibility service.
        stopSelf(startId)
        return START_NOT_STICKY
    }

    /** Accessibility was turned off (or the system dropped the binding): release everything. */
    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        stopSelf()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    /** Idempotent: removes every window, timer, receiver and callback this instance owns. */
    private fun teardown() {
        stopInactivityTimer()
        removePill()
        removeFabWindow()
        removeMenuWindow()
        removeWave()
        if (isScreenReceiverRegistered) {
            unregisterReceiverSafe(screenReceiver)
            isScreenReceiverRegistered = false
        }
        stashHandler.removeCallbacksAndMessages(null)
        actionHandler?.cleanup()
        actionHandler = null
        ToastUtil.destroy()
        VolumeHud.destroy()
        isExpanded = false
        isStashed = false
        isDragging = false
        lastHandledPackage = null
        // Without a connection there is no window token; every add/show path checks this.
        windowManager = null
    }

    /* -------------------- Initialization -------------------- */

    private fun initFloatingBall() {
        portraitPosition = EdgePosition(
            isOnRight = prefs.portraitIsOnRight,
            yFraction = prefs.portraitYFraction
        )
        landscapePosition = EdgePosition(
            isOnRight = prefs.landscapeIsOnRight,
            yFraction = prefs.landscapeYFraction
        )

        actionHandler = QuickBallActionHandler(this, recentAppTracker) {
            startCollapsingMenu()
            stashFab()
        }

        createFabWindow()
    }

    /* -------------------- Accessibility & System Events -------------------- */

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        recentAppTracker.onAccessibilityEvent(event)

        val packageName = event.packageName?.toString() ?: return

        if (packageName == APP_PACKAGE_PREFIX) {
            // Our own settings screen: settings (auto-hide list, enable switch...) may change
            // there, so re-evaluate on the next app screen even if it is the same app as before.
            lastHandledPackage = null
            return
        }
        if (packageName in EXCLUDED_APPS) return

        // Only a real activity coming to the front changes the foreground app. The keyboard
        // (e.g. a third-party IME), popups, dialogs and other overlays fire the same event type
        // but must not make the ball reappear inside an auto-hidden app.
        if (!recentAppTracker.isAppScreen(event)) return
        if (packageName == lastHandledPackage) return
        lastHandledPackage = packageName

        try {
            onForegroundPackageChanged(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing foreground package change", e)
        }
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshBallVisibility()
        recalculatePosition()
    }

    private fun onForegroundPackageChanged(packageName: String) {
        lastForegroundPackage = packageName
        refreshBallVisibility()
    }

    /* -------------------- Visibility Engine -------------------- */

    private fun refreshBallVisibility() {
        if (!isEnabled) {
            hideBall()
            return
        }

        // Lock state
        if (isLocked) {
            if (showOnLockScreen) {
                startCollapsingMenu()
                showBall()
                stashFab(animated = false)
            } else {
                hideBall()
            }
            return
        }

        // Unlock state
        if (hideForLandscape || isAutoHideApp()) {
            hideBall()
            return
        }

        showBall()
    }

    private fun isAutoHideApp(): Boolean {
        val pkg = lastForegroundPackage
        return pkg in autoHideApps
    }

    private fun showBall() {
        if (fabView == null) {
            createFabWindow()
            isStashed = false
            stashFab(animated = false)
            resetInactivityTimer()
        }
        showWave()
    }

    private fun hideBall() {
        stopInactivityTimer()
        removePill()
        removeFabWindow()
        removeMenuWindow()
        removeWave()
    }

    private fun updateBall() {
        val fab = fabView ?: return
        val params = fabParams ?: return

        params.width = fabSizePx
        params.height = fabSizePx

        fab.update()

        recalculatePosition()
    }

    private fun updatePill() {
        val pill = pillView ?: return
        val params = pillParams ?: return
        val wm = windowManager ?: return

        val pillWidth = DensityUtils.dp2px(prefs.pillTouchWidth)
        val pillHeight = DensityUtils.dp2px(prefs.pillHeight)
        val (screenW, _) = getScreenSize()

        params.width = pillWidth
        params.height = pillHeight
        params.x = if (isOnRight) screenW - pillWidth else 0
        params.y = fabY + (fabSizePx - pillHeight) / 2

        pill.update()

        try {
            wm.updateViewLayout(pill, params)
        } catch (_: Exception) {
        }
        layoutWave()
    }

    private open inner class QuickBallGestureListener : GestureListener {
        override fun onLongPress() = executeGestureAction(prefs.longPressAction)
        override fun onSwipeUp() = executeGestureAction(prefs.swipeUpAction)
        override fun onSwipeDown() = executeGestureAction(prefs.swipeDownAction)
    }

    private fun executeGestureAction(binding: String) {
        if (!prefs.isGestureEnabled) return
        if (executeBinding(binding)) resetInactivityTimer()
    }

    /** Runs a gesture binding (an action, or "open app"); returns false if it was invalid. */
    private fun executeBinding(binding: String): Boolean {
        val handler = actionHandler ?: return false
        GestureBinding.appPackage(binding)?.let { pkg ->
            performHapticFeedback()
            handler.launchAppNow(pkg)
            return true
        }
        val action = GestureBinding.action(binding) ?: return false
        performHapticFeedback()
        handler.onMenuAction(QuickBallMenuItem(action = action))
        return true
    }

    /* -------------------- FAB Window & Gestures -------------------- */

    @SuppressLint("ClickableViewAccessibility")
    private fun createFabWindow() {
        if (fabView != null) return
        val wm = windowManager ?: return

        fabX = getEdgeX()
        fabY = getEdgeY()

        var initialWindowX = 0
        var initialWindowY = 0
        var screenW = 0
        var screenH = 0

        fabParams = createSystemWindowParams(
            width = fabSizePx,
            height = fabSizePx,
        ).apply {
            x = fabX
            y = fabY
        }

        fabView = FloatTouchView(this).apply {
            setExpanded(isExpanded, animate = false)

            listener = object : QuickBallGestureListener() {
                override fun onTouchDown() {
                    // A touch during the stash animation used to return early here, leaving the
                    // drag origin and screen bounds unset (0 on the first touch). If the finger
                    // was still down when the animation ended, onDragMove then coerced into an
                    // empty range and crashed the service, or dragged a ball that was still
                    // flagged as stashed. Bring the ball back instantly and start a normal touch.
                    if (isStashing) unstashFab(animated = false)
                    stopInactivityTimer()
                    fabAnimator?.cancel()
                    isStashed = false
                    initialWindowX = fabX
                    initialWindowY = fabY

                    val (sw, sh) = getScreenSize()
                    screenW = sw
                    screenH = sh
                }

                override fun onTouchCancel() {
                    isDragging = false
                    resetInactivityTimer()
                }

                override fun onDragMove(dx: Float, dy: Float) {
                    if (screenW <= fabSizePx || screenH <= fabSizePx + topBoundary + bottomBoundary) {
                        return
                    }
                    isDragging = true
                    stopInactivityTimer()
                    alpha = 1.0f

                    fabX = (initialWindowX + dx).roundToInt().coerceIn(0, screenW - fabSizePx)
                    fabY = (initialWindowY + dy).roundToInt()
                        .coerceIn(topBoundary, screenH - fabSizePx - bottomBoundary)

                    fabParams?.x = fabX
                    fabParams?.y = fabY
                    updateFabViewLayout(this@apply, fabParams)
                }

                override fun onDragEnd() {
                    isDragging = false
                    if (!isStashing) snapToEdge()
                }

                override fun onSingleTap() {
                    isDragging = false
                    if (!isStickToEdge) alpha = 1.0f
                    if (isStashed || isStashing) {
                        unstashFab()
                    }
                    performHapticFeedback()
                    expandMenu()
                }
            }
        }

        try {
            wm.addView(fabView, fabParams)
        } catch (e: Exception) {
            // e.g. BadTokenException when the accessibility connection is already gone.
            Log.e(TAG, "Failed to add floating ball", e)
            fabView = null
            fabParams = null
            return
        }

        resetInactivityTimer()
    }

    private fun removeFabWindow() {
        // A stash animation still running here would finish on a detached view and then call
        // showPill(), leaving an orphan edge handle on screen while the ball is meant to be hidden.
        fabAnimator?.cancel()
        fabAnimator = null
        isStashing = false
        fabView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: IllegalArgumentException) {
            }
            fabView = null
        }
    }

    private fun snapToEdge() {
        val (screenW, screenH) = getScreenSize()

        currentPosition = EdgePosition(
            isOnRight = (fabX + fabSizePx / 2) > screenW / 2,
            yFraction = if (screenH > 0) (fabY.toFloat() / screenH) else 0.5f
        )

        val targetX = getEdgeX(screenW)

        val distance = abs(targetX - fabX)
        val duration = (distance.toFloat() / screenW * 350f).coerceIn(180f, 320f).toLong()

        animateFabX(targetX, duration) {
            resetInactivityTimer()
        }
        layoutWave()
    }

    private fun stashFab(animated: Boolean = true) {
        if (isExpanded) return

        val view = fabView ?: return
        val params = fabParams ?: return

        if (!isStickToEdge) {
            if (animated) {
                animateFabX(fabX, 250L, alpha = stashAlpha)
            } else {
                view.alpha = stashAlpha
            }
            return
        }
        if (isStashed) return

        val (screenW, _) = getScreenSize()

        val targetX = if (isOnRight) {
            screenW
        } else {
            -fabSizePx
        }

        fabAnimator?.cancel()

        if (animated) {
            isStashing = true
            animateFabX(targetX, 250L, alpha = stashAlpha) {
                isStashing = false
                isStashed = true
                showPill()
            }
        } else {
            isStashing = false
            view.alpha = stashAlpha
            fabX = targetX
            params.x = targetX
            updateFabViewLayout(view, params)
            isStashed = true
            showPill()
        }
    }

    private fun unstashFab(animated: Boolean = true, onFinished: (() -> Unit)? = null) {
        if (!isStashed && !isStashing) {
            onFinished?.invoke()
            return
        }
        val view = fabView ?: return
        val params = fabParams ?: return

        val (screenW, _) = getScreenSize()
        val targetX = getEdgeX(screenW)

        isStashing = false
        removePill()
        fabAnimator?.cancel()

        if (animated) {
            params.x = if (isOnRight) screenW - fabSizePx else 0
            updateFabViewLayout(view, params)
            fabX = params.x

            animateFabX(targetX, 250L, alpha = 1.0f) {
                isStashed = false
                resetInactivityTimer()
                onFinished?.invoke()
            }
        } else {
            view.alpha = 1.0f
            fabX = targetX
            params.x = targetX
            updateFabViewLayout(view, params)
            isStashed = false
            resetInactivityTimer()
            onFinished?.invoke()
        }
    }

    private fun animateFabX(
        targetX: Int,
        duration: Long,
        alpha: Float? = null,
        onEnd: (() -> Unit)? = null
    ) {
        val view = fabView ?: return
        val params = fabParams ?: return

        val startAlpha = view.alpha

        fabAnimator?.cancel()
        val animator = ValueAnimator.ofInt(fabX, targetX).apply {
            this.duration = duration
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { anim ->
                val currentX = anim.animatedValue as Int
                fabX = currentX
                params.x = currentX
                updateFabViewLayout(view, params)

                if (alpha != null) {
                    val fraction = anim.animatedFraction
                    view.alpha = startAlpha + (alpha - startAlpha) * fraction
                }
            }
            var isCancelled = false
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    isCancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!isCancelled) {
                        onEnd?.invoke()
                    }
                }
            })
        }
        fabAnimator = animator
        animator.start()
    }

    private fun updateFabViewLayout(view: View, params: ViewGroup.LayoutParams?) {
        val wm = windowManager ?: return
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    /* -------------------- Stashed Pill Management -------------------- */

    @SuppressLint("ClickableViewAccessibility")
    private fun showPill() {
        if (pillView != null || fabView == null) return
        val wm = windowManager ?: return

        val pillWidth = DensityUtils.dp2px(prefs.pillTouchWidth)
        val pillHeight = DensityUtils.dp2px(prefs.pillHeight)

        val (screenW, _) = getScreenSize()
        val targetX = if (isOnRight) screenW - pillWidth else 0
        val targetY = fabY + (fabSizePx - pillHeight) / 2

        pillView = SideKickView(this).apply {
            onRight = isOnRight
            listener = object : QuickBallGestureListener() {
                override fun onSingleTap() {
                    performHapticFeedback()
                    removePill()
                    unstashFab()
                    expandMenu()
                }
            }
        }

        pillParams = createSystemWindowParams(
            width = pillWidth,
            height = pillHeight,
        ).apply {
            x = targetX
            y = targetY
        }

        try {
            wm.addView(pillView, pillParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add edge handle", e)
            pillView = null
            pillParams = null
        }
    }

    private fun removePill() {
        pillView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
            pillView = null
        }
        pillParams = null
    }

    /* -------------------- Wave Edge Bar -------------------- */

    /**
     * The wave bar is a second, swipe-only handle. It is shown and hidden together with the ball
     * (same lock-screen, landscape and auto-hide rules) and only when enabled in settings.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showWave() {
        if (!prefs.isWaveEnabled) {
            removeWave()
            return
        }
        if (waveView != null) {
            layoutWave()
            return
        }
        val wm = windowManager ?: return

        val view = WaveBarView(this).apply {
            listener = object : WaveBarView.Listener {
                override fun onSwipeUp() = executeWaveAction(prefs.waveSwipeUpAction)
                override fun onSwipeDown() = executeWaveAction(prefs.waveSwipeDownAction)
            }
        }
        val params = createSystemWindowParams(width = 1, height = 1)
        applyWaveLayout(view, params)

        try {
            wm.addView(view, params)
            waveView = view
            waveParams = params
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add wave bar", e)
        }
    }

    /** Re-reads settings: creates, updates or removes the bar as needed. */
    private fun updateWave() {
        if (prefs.isWaveEnabled && fabView != null) {
            showWave()
            waveView?.update()
        } else {
            removeWave()
        }
    }

    /** Moves/resizes the bar, e.g. after the ball was dragged; no-op when nothing changed. */
    private fun layoutWave() {
        val view = waveView ?: return
        val params = waveParams ?: return
        if (applyWaveLayout(view, params)) {
            updateFabViewLayout(view, params)
        }
    }

    /** Writes the bar's geometry into [params]; returns true if anything changed. */
    private fun applyWaveLayout(view: WaveBarView, params: WindowManager.LayoutParams): Boolean {
        val (screenW, screenH) = getScreenSize()
        val width = DensityUtils.dp2px(prefs.waveTouchWidth)
        val height = DensityUtils.dp2px(prefs.waveHeight).coerceAtMost(screenH)
        val onRight = prefs.waveOnRight
        val x = if (onRight) screenW - width else 0
        val y = computeWaveY(screenH, height, onRight)

        view.onRight = onRight
        val changed = params.width != width || params.height != height ||
                params.x != x || params.y != y
        params.width = width
        params.height = height
        params.x = x
        params.y = y
        return changed
    }

    /**
     * Places the bar at its configured position, but when it shares an edge with the ball, moves
     * it just above or below the ball/pill (whichever is closer and fits) so they never overlap.
     */
    private fun computeWaveY(screenH: Int, waveH: Int, waveOnRight: Boolean): Int {
        val maxY = max(0, screenH - waveH)
        val desired = (prefs.waveYFraction * screenH - waveH / 2f).roundToInt().coerceIn(0, maxY)
        if (fabView == null || waveOnRight != isOnRight) return desired

        val gap = DensityUtils.dp2px(WAVE_GAP_DP)
        val pillH = DensityUtils.dp2px(prefs.pillHeight)
        val center = fabY + fabSizePx / 2
        val half = max(fabSizePx, pillH) / 2
        val occupiedTop = center - half - gap
        val occupiedBottom = center + half + gap
        if (desired + waveH <= occupiedTop || desired >= occupiedBottom) return desired

        val above = occupiedTop - waveH
        val below = occupiedBottom
        val fitsAbove = above >= 0
        val fitsBelow = below + waveH <= screenH
        return when {
            fitsAbove && fitsBelow -> if (desired - above <= below - desired) above else below
            fitsAbove -> above
            fitsBelow -> below
            else -> desired
        }
    }

    private fun removeWave() {
        waveView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        waveView = null
        waveParams = null
    }

    private fun executeWaveAction(binding: String) {
        executeBinding(binding)
    }

    /* -------------------- Menu Window Management -------------------- */

    private fun expandMenu() {
        val wm = windowManager ?: return

        isExpanded = true
        fabView?.setExpanded(true)
        stopInactivityTimer()

        val existingView = menuView
        val existingParams = menuParams
        if (existingView != null && existingParams != null) {
            existingParams.flags =
                existingParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                wm.updateViewLayout(existingView, existingParams)
            } catch (_: Exception) {
            }
            existingView.animateExpand()
            return
        }

        menuView = FloatPanelView(
            context = this,
            fabX = getEdgeX(),
            fabY = fabY,
            fabSize = fabSizePx,
            items = getMenuItems(),
            onDismiss = {
                startCollapsingMenu()
            },
            onDismissFinished = {
                removeMenuWindow()
                resetInactivityTimer()
            },
            onMenuItemClicked = { menuItem ->
                performHapticFeedback()
                actionHandler?.onMenuAction(menuItem)
                if (menuItem.action !in setOf(
                        MenuAction.VOLUME_UP,
                        MenuAction.VOLUME_DOWN,
                        MenuAction.BRIGHTNESS_UP,
                        MenuAction.BRIGHTNESS_DOWN
                    )
                ) {
                    startCollapsingMenu()
                }
            }
        )

        menuParams = createSystemWindowParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
        ).apply {
            x = 0
            y = 0
        }

        try {
            wm.addView(menuView, menuParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add menu", e)
            menuView = null
            menuParams = null
            isExpanded = false
            fabView?.setExpanded(false, animate = false)
            resetInactivityTimer()
            return
        }
        menuView?.animateExpand()
    }

    private fun startCollapsingMenu() {
        isExpanded = false
        fabView?.setExpanded(false)

        val wm = windowManager ?: return
        val view = menuView ?: return
        val params = menuParams ?: return

        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }

        view.animateCollapse()
    }

    private fun removeMenuWindow() {
        isExpanded = false
        fabView?.setExpanded(false)

        menuView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: IllegalArgumentException) {
            }
            menuView = null
        }
        menuParams = null
    }

    private fun getMenuItems(): List<QuickBallMenuItem> {
        return prefs.selectedMenuItems
    }

    /* -------------------- Timers & Helpers -------------------- */

    private fun onInactivityTimeout() {
        if (!isDragging && !isExpanded) {
            stashFab()
        }
    }

    private fun resetInactivityTimer() {
        stashHandler.removeCallbacks(stashRunnable)
        if (!isExpanded && !isStashed) {
            stashHandler.postDelayed(stashRunnable, STASH_DELAY_MS)
        }
    }

    private fun stopInactivityTimer() {
        stashHandler.removeCallbacks(stashRunnable)
    }

    private fun recalculatePosition() {
        if (isExpanded) {
            removeMenuWindow()
        }
        // Hidden (disabled, auto-hide app, landscape, lock screen): nothing to lay out. Without
        // this, a rotation or dark-mode switch re-created the pill of a hidden, stashed ball.
        if (fabView == null) return

        val (screenW, screenH) = getScreenSize()

        if (isStashed) {
            fabX = getEdgeX(screenW)
            fabY = getEdgeY(screenH)

            fabParams?.let { p ->
                p.x = if (isOnRight) screenW else -fabSizePx
                p.y = fabY
                fabView?.let { v ->
                    v.alpha = stashAlpha
                    updateFabViewLayout(v, p)
                }
            }

            removePill()
            showPill()
        } else {
            removePill()

            fabX = getEdgeX(screenW)
            fabY = getEdgeY(screenH)

            fabParams?.let { p ->
                p.x = fabX
                p.y = fabY
                fabView?.let { v ->
                    v.alpha = 1.0f
                    updateFabViewLayout(v, p)
                }
            }
            isStashed = false
            resetInactivityTimer()
        }
        layoutWave()
    }

    private fun getEdgeX(screenW: Int = getScreenSize().first): Int {
        return if (isOnRight) screenW - fabSizePx - edgePaddingPx else edgePaddingPx
    }

    private fun getEdgeY(screenH: Int = getScreenSize().second): Int {
        return (savedYFraction * screenH).roundToInt()
            .coerceIn(topBoundary, screenH - fabSizePx - bottomBoundary)
    }

    private fun registerScreenReceiver() {
        if (isScreenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
        isScreenReceiverRegistered = true
    }

    private fun unregisterReceiverSafe(receiver: BroadcastReceiver) {
        runCatching { unregisterReceiver(receiver) }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                onScreenOff()
            } else {
                refreshBallVisibility()
            }
        }
    }

    /**
     * The keyguard usually locks only after SCREEN_OFF (or much later with a lock delay), so
     * isKeyguardLocked is still false here and refreshBallVisibility() would leave the ball as
     * it is. It then showed for a frame on the lock screen at the next SCREEN_ON before being
     * hidden. Apply the lock-screen state now instead; SCREEN_ON / USER_PRESENT re-evaluate it,
     * so turning the screen back on before it actually locks just shows the ball again.
     */
    private fun onScreenOff() {
        if (!isEnabled || !showOnLockScreen) {
            hideBall()
            return
        }
        if (fabView == null) return
        // Animations don't advance while the display is off; drop the menu without one.
        removeMenuWindow()
        stopInactivityTimer()
        stashFab(animated = false)
    }

    private fun createSystemWindowParams(
        width: Int,
        height: Int,
    ): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }

        return WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            } else {
                @Suppress("DEPRECATION")
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }
    }
}
