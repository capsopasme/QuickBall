package io.github.chayanforyou.quickball.ui.floating

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Interface for views or listeners that handle floating gesture actions.
 */
interface GestureListener {
    fun onTouchDown() {}
    fun onTouchCancel() {}
    fun onSingleTap() {}
    fun onLongPress() {}
    fun onSwipeUp() {}
    fun onSwipeDown() {}
    fun onDragMove(dx: Float, dy: Float) {}
    fun onDragEnd() {}
}

/**
 * Reusable gesture detector for floating overlay views (e.g. Floating Button and Pill View).
 *
 * Recognizes single tap, long press, and vertical swipe gestures. There are no multi-tap
 * gestures, so a single tap fires on ACTION_UP with no waiting window, and nothing is left
 * scheduled on the main looper after a tap.
 *
 * A tap only counts when the finger stayed within the touch slop. The edge handle sits on top of
 * the system back-gesture zone: when SystemUI claims a swipe it cancels our stream, but a swipe
 * it does not claim (too short or slow to commit, or started just inside our touch area but
 * outside the system's edge width) still ends with ACTION_UP here. Counting that as a tap made a
 * back swipe occasionally expand the ball.
 */
class GestureDetector(
    context: Context,
    var listener: GestureListener? = null,
    var isGestureEnabled: () -> Boolean = { true }
) {
    private val handler = Handler(Looper.getMainLooper())
    private val swipeThreshold = 24f * context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()

    private var startX = 0f
    private var startY = 0f
    private var movedBeyondSlop = false
    private var isLongPressPending = false
    private var isSwipeTriggered = false
    private var isLongPressTriggered = false

    private val longPressRunnable = Runnable {
        isLongPressPending = false
        isLongPressTriggered = true
        listener?.onLongPress()
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val gesturesOn = isGestureEnabled()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                listener?.onTouchDown()
                startX = event.rawX
                startY = event.rawY
                movedBeyondSlop = false
                isSwipeTriggered = false
                isLongPressTriggered = false
                cancelLongPress()
                if (gesturesOn) {
                    handler.postDelayed(longPressRunnable, longPressTimeoutMs)
                    isLongPressPending = true
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isSwipeTriggered || isLongPressTriggered) return true

                val dx = event.rawX - startX
                val dy = event.rawY - startY

                if (!movedBeyondSlop && hypot(dx, dy) > touchSlop) {
                    movedBeyondSlop = true
                    cancelLongPress()
                }

                // Check if vertical swipe is predominant and passes threshold
                if (gesturesOn && abs(dy) > swipeThreshold && abs(dy) > abs(dx) * 2f) {
                    isSwipeTriggered = true
                    cancelLongPress()

                    if (dy < 0) {
                        listener?.onSwipeUp()
                    } else {
                        listener?.onSwipeDown()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL ||
                        (event.flags and MotionEvent.FLAG_CANCELED) != 0
                if (cancelled) {
                    listener?.onTouchCancel()
                } else if (!isSwipeTriggered && !isLongPressTriggered && !movedBeyondSlop &&
                    hypot(event.rawX - startX, event.rawY - startY) <= touchSlop &&
                    (!gesturesOn || event.eventTime - event.downTime < longPressTimeoutMs)
                ) {
                    listener?.onSingleTap()
                }
                movedBeyondSlop = false
                isSwipeTriggered = false
                isLongPressTriggered = false
                return true
            }

            else -> return false
        }
    }

    private fun cancelLongPress() {
        if (isLongPressPending) {
            handler.removeCallbacks(longPressRunnable)
            isLongPressPending = false
        }
    }

    fun cleanup() {
        cancelLongPress()
    }
}
