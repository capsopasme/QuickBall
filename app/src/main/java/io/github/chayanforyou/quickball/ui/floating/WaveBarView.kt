package io.github.chayanforyou.quickball.ui.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import io.github.chayanforyou.quickball.domain.AppPreference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A second, swipe-only edge handle drawn as a vertical sine wave.
 *
 * Unlike [SideKickView] it has no tap, double tap or long press, so there is nothing to
 * disambiguate: the action fires the moment a vertical swipe crosses the threshold, before the
 * finger lifts. Each touch sequence fires at most once. Taps are consumed and ignored.
 */
class WaveBarView(context: Context) : View(context) {

    interface Listener {
        fun onSwipeUp()
        fun onSwipeDown()
    }

    var listener: Listener? = null

    /** Screen edge the bar is docked to; the wave is drawn hugging that edge. */
    var onRight: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                update()
            }
        }

    private val prefs by lazy { AppPreference.getInstance(context) }
    private val density = resources.displayMetrics.density
    private val amplitudePx = 3f * density
    private val targetWavelengthPx = 22f * density
    private val edgeInsetPx = 2f * density
    private val swipeThresholdPx = 24f * density
    private val sampleStepPx = max(1f, 1.5f * density)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    private var startX = 0f
    private var startY = 0f
    private var fired = false

    /** Re-reads colour/thickness and rebuilds the wave; call after settings change. */
    fun update() {
        rebuildPath()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPath()
    }

    private fun rebuildPath() {
        path.reset()
        val stroke = prefs.waveThickness * density
        paint.strokeWidth = stroke
        paint.color = prefs.waveColor

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val top = stroke / 2f
        val length = h - stroke
        if (length <= 0f) return

        // Whole number of periods so both ends sit on the centre line.
        val periods = max(1, (length / targetWavelengthPx).roundToInt())
        val wavelength = length / periods
        val centerX = if (onRight) {
            w - edgeInsetPx - amplitudePx - stroke / 2f
        } else {
            edgeInsetPx + amplitudePx + stroke / 2f
        }

        path.moveTo(centerX, top)
        var y = sampleStepPx
        while (y < length) {
            path.lineTo(centerX + amplitudePx * sin(2.0 * PI * y / wavelength).toFloat(), top + y)
            y += sampleStepPx
        }
        path.lineTo(centerX, top + length)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                fired = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (fired) return true
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                if (abs(dy) > swipeThresholdPx && abs(dy) > abs(dx) * 1.5f) {
                    fired = true
                    if (dy < 0) listener?.onSwipeUp() else listener?.onSwipeDown()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fired = false
        }
        return true
    }
}
