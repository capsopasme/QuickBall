package io.github.chayanforyou.quickball.ui.floating

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import io.github.chayanforyou.quickball.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * iOS style volume indicator: a black capsule that springs open just below the status bar, a
 * fill that springs to the new level, a short rubber-band stretch when the volume is already at
 * its limit, and a shrink/fade away. It deliberately stays clear of the camera hole and the
 * status bar (it used to grow out of the punch hole, which put the bar right across the camera).
 *
 * Power: nothing runs while it is not on screen. Animators only tick during their ~0.2–0.5 s,
 * drawing is one rounded rect, an icon and two bars on the GPU, and the overlay window is
 * removed as soon as the capsule has shrunk away, so no idle surface is kept around.
 */
object VolumeHud {

    private const val HIDE_DELAY_MS = 1500L

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var view: VolumeHudView? = null
    private var params: WindowManager.LayoutParams? = null

    private var revealAnim: ValueAnimator? = null
    private var levelAnim: ValueAnimator? = null
    private var stretchAnim: ValueAnimator? = null

    private var maxIndex = 1
    private var currentIndex = 0
    private var shown = false
    private var touching = false
    private var onVolumeChanged: ((Int) -> Unit)? = null

    private val hideRunnable = Runnable { hide() }

    /**
     * @param previous volume index before the change (the fill starts from here)
     * @param current volume index after the change
     * @param hitLimit true when the step could not move because the volume was already at 0/max
     */
    fun show(
        context: Context,
        previous: Int,
        current: Int,
        max: Int,
        hitLimit: Boolean,
        onVolumeChanged: (Int) -> Unit
    ) {
        this.onVolumeChanged = onVolumeChanged
        maxIndex = max.coerceAtLeast(1)
        currentIndex = current.coerceIn(0, maxIndex)

        val wasAttached = view?.isAttachedToWindow == true
        val v = ensureWindow(context) ?: return
        v.targetLevel = currentIndex / maxIndex.toFloat()

        if (!wasAttached || !shown) {
            // Fresh appearance: show the old level first so the change itself is animated.
            if (!wasAttached) v.level = previous.coerceIn(0, maxIndex) / maxIndex.toFloat()
            shown = true
            animateReveal(v, to = 1f, duration = 520L, interpolator = SpringInterpolator(0.62f))
        }
        animateLevel(v, v.targetLevel)
        if (hitLimit) playStretch(v)
        scheduleHide()
    }

    /** Must be called when the accessibility service goes away (its window token dies with it). */
    fun destroy() {
        handler.removeCallbacks(hideRunnable)
        revealAnim?.cancel()
        levelAnim?.cancel()
        stretchAnim?.cancel()
        revealAnim = null
        levelAnim = null
        stretchAnim = null
        removeWindow()
        windowManager = null
        onVolumeChanged = null
        shown = false
        touching = false
    }

    // -------------------- window --------------------

    private fun ensureWindow(context: Context): VolumeHudView? {
        val wm = windowManager
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.also { windowManager = it }
            ?: return null

        val v = view ?: VolumeHudView(context.applicationContext).also { created ->
            created.onDrag = ::onDrag
            created.onTouchChanged = ::onTouchChanged
            view = created
        }
        // Geometry is only (re)computed while hidden, so the capsule never jumps mid-show.
        if (!v.isAttachedToWindow) {
            val landscape = context.resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
            v.configure(landscape, statusBarHeight(context))
        }

        val p = params ?: WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }.also { params = it }

        p.width = v.windowWidth
        p.height = v.windowHeight
        p.y = v.windowTop

        try {
            if (v.isAttachedToWindow) wm.updateViewLayout(v, p) else wm.addView(v, p)
        } catch (_: Exception) {
            return null
        }
        return v
    }

    private fun removeWindow() {
        view?.let { v ->
            if (v.isAttachedToWindow) {
                runCatching { windowManager?.removeView(v) }
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun statusBarHeight(context: Context): Int {
        val res = context.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else (24 * res.displayMetrics.density).roundToInt()
    }

    // -------------------- animation --------------------

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        if (!touching) handler.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private fun hide() {
        val v = view ?: return
        shown = false
        animateReveal(
            v, to = 0f, duration = 260L,
            interpolator = PathInterpolator(0.4f, 0f, 0.7f, 1f)
        ) {
            // Only drop the window if nothing re-showed it meanwhile.
            if (!shown) {
                levelAnim?.cancel()
                stretchAnim?.cancel()
                removeWindow()
            }
        }
    }

    private fun animateReveal(
        v: VolumeHudView,
        to: Float,
        duration: Long,
        interpolator: Interpolator,
        onEnd: (() -> Unit)? = null
    ) {
        revealAnim?.cancel()
        revealAnim = ValueAnimator.ofFloat(v.reveal, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { v.reveal = it.animatedValue as Float }
            if (onEnd != null) {
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) onEnd()
                    }
                })
            }
            start()
        }
    }

    private fun animateLevel(v: VolumeHudView, to: Float) {
        levelAnim?.cancel()
        if (v.level == to) return
        levelAnim = ValueAnimator.ofFloat(v.level, to).apply {
            duration = 420L
            interpolator = SpringInterpolator(0.78f)
            addUpdateListener { v.level = it.animatedValue as Float }
            start()
        }
    }

    /** Rubber band: the capsule stretches a little and snaps back, like iOS at 0 % / 100 %. */
    private fun playStretch(v: VolumeHudView) {
        stretchAnim?.cancel()
        stretchAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            addUpdateListener {
                val t = it.animatedValue as Float
                v.stretch = sin(PI * t).toFloat() * (1f - 0.35f * t)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.stretch = 0f
                }
            })
            start()
        }
    }

    // -------------------- dragging the bar --------------------

    private fun onTouchChanged(down: Boolean) {
        touching = down
        val v = view ?: return
        if (down) {
            handler.removeCallbacks(hideRunnable)
            levelAnim?.cancel()
            if (!shown) {
                shown = true
                animateReveal(v, to = 1f, duration = 300L, interpolator = SpringInterpolator(0.8f))
            }
        } else {
            v.targetLevel = currentIndex / maxIndex.toFloat()
            animateLevel(v, v.targetLevel)
            scheduleHide()
        }
    }

    private fun onDrag(level: Float) {
        val v = view ?: return
        val clamped = level.coerceIn(0f, 1f)
        v.level = clamped
        val index = (clamped * maxIndex).roundToInt()
        if (index != currentIndex) {
            currentIndex = index
            v.targetLevel = index / maxIndex.toFloat()
            onVolumeChanged?.invoke(index)
        }
    }

    /**
     * Critically-under-damped spring step response mapped onto [0, 1]: overshoots a few percent
     * and settles, the way iOS UI springs feel. Lower damping = more bounce.
     */
    private class SpringInterpolator(private val damping: Float) : Interpolator {
        private val omega = 10f
        private val omegaD = omega * sqrt(1f - damping * damping)

        override fun getInterpolation(input: Float): Float {
            if (input >= 1f) return 1f
            val decay = exp(-damping * omega * input)
            return 1f - decay * (cos(omegaD * input) + (damping * omega / omegaD) * sin(omegaD * input))
        }
    }
}

/** The capsule itself; all geometry is derived from the three animated values. */
@SuppressLint("ViewConstructor")
internal class VolumeHudView(context: Context) : View(context) {

    var onDrag: ((Float) -> Unit)? = null
    var onTouchChanged: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val fullW = dp(200f)
    private val fullH = dp(38f)
    private val maxStretchW = dp(14f)
    private val sideMargin = dp(10f)
    private val iconSize = dp(18f)
    private val trackH = dp(6f)

    // Closed size the capsule springs open from (and shrinks back to).
    private val compactW = fullW * 0.5f
    private val compactH = fullH * 0.6f
    // The window only spans the capsule, so the status bar above stays touchable.
    private val centerY = sideMargin + fullH / 2f

    /** Screen y of the window's top edge; set by [configure]. */
    var windowTop = 0
        private set

    val windowWidth: Int get() = (fullW + maxStretchW + 2 * sideMargin).roundToInt()
    val windowHeight: Int get() = (fullH + 2 * sideMargin).roundToInt()

    /** 0 = closed (invisible), 1 = fully open. Spring values may overshoot slightly. */
    var reveal = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Drawn fill level, 0..1 (may overshoot during the spring; clamped when drawn). */
    var level = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Level the icon reflects (the real volume, not the in-flight fill). */
    var targetLevel = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 0..1 rubber-band stretch. */
    var stretch = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val bgMaxAlpha = 235f
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val iconOff = icon(R.drawable.ic_volume_off)
    private val iconLow = icon(R.drawable.ic_volume_down)
    private val iconHigh = icon(R.drawable.ic_volume_up)

    private val capsule = RectF()
    private val track = RectF()

    private fun icon(res: Int): Drawable? =
        ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(Color.WHITE) }

    /**
     * Puts the capsule a little below the status bar in portrait (the status bar is at least as
     * tall as the camera hole, so the two never overlap), or near the top edge in landscape where
     * the status bar is usually hidden and the camera sits on a side edge.
     */
    fun configure(landscape: Boolean, statusBarPx: Int) {
        val gap = dp(6f)
        val top = if (landscape) gap else statusBarPx + gap
        windowTop = (top - sideMargin).roundToInt().coerceAtLeast(0)
    }

    override fun onDraw(canvas: Canvas) {
        val r = reveal
        if (r <= 0.001f && stretch <= 0f) return

        val w = compactW + (fullW - compactW) * r + maxStretchW * stretch
        val h = (compactH + (fullH - compactH) * r) * (1f - 0.08f * stretch)
        val cx = width / 2f
        capsule.set(cx - w / 2f, centerY - h / 2f, cx + w / 2f, centerY + h / 2f)
        val radius = h / 2f
        // The capsule fades in while it opens instead of emerging from the camera hole.
        bgPaint.alpha = ((r / 0.35f).coerceIn(0f, 1f) * bgMaxAlpha).roundToInt()
        canvas.drawRoundRect(capsule, radius, radius, bgPaint)

        // Content fades in once the capsule is mostly open, and out early when it closes.
        val contentAlpha = ((r - 0.5f) / 0.5f).coerceIn(0f, 1f)
        if (contentAlpha <= 0f) return
        val alpha255 = (contentAlpha * 255).roundToInt()

        val iconLeft = capsule.left + dp(13f)
        val iconTop = centerY - iconSize / 2f
        val icon = when {
            targetLevel <= 0f -> iconOff
            targetLevel <= 0.5f -> iconLow
            else -> iconHigh
        }
        icon?.let {
            it.setBounds(
                iconLeft.roundToInt(), iconTop.roundToInt(),
                (iconLeft + iconSize).roundToInt(), (iconTop + iconSize).roundToInt()
            )
            it.alpha = alpha255
            it.draw(canvas)
        }

        val trackLeft = iconLeft + iconSize + dp(10f)
        val trackRight = capsule.right - dp(16f)
        if (trackRight <= trackLeft) return
        track.set(trackLeft, centerY - trackH / 2f, trackRight, centerY + trackH / 2f)
        trackPaint.alpha = (alpha255 * 0.28f).roundToInt()
        canvas.drawRoundRect(track, trackH / 2f, trackH / 2f, trackPaint)

        val fillW = track.width() * level.coerceIn(0f, 1f)
        if (fillW > 0.5f) {
            fillPaint.alpha = alpha255
            val save = canvas.save()
            canvas.clipRect(track.left, track.top, track.left + fillW, track.bottom)
            canvas.drawRoundRect(track, trackH / 2f, trackH / 2f, fillPaint)
            canvas.restoreToCount(save)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the visible capsule reacts; touches on the transparent margin are ignored.
                if (reveal < 0.5f || !capsule.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                onTouchChanged?.invoke(true)
                dragTo(event.x)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                dragTo(event.x)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onTouchChanged?.invoke(false)
                return true
            }
        }
        return false
    }

    private fun dragTo(x: Float) {
        if (track.width() <= 0f) return
        onDrag?.invoke((x - track.left) / track.width())
    }
}
