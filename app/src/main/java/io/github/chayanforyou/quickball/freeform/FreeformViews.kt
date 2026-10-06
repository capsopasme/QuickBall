package io.github.chayanforyou.quickball.freeform

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import io.github.chayanforyou.quickball.R
import kotlin.math.hypot
import kotlin.math.min

/** Colors of the small-window decoration, following the system dark mode. */
internal class FreeformPalette(
    val bar: Int,
    val foreground: Int,
    val pressed: Int,
    val veil: Int,
) {
    companion object {
        fun from(context: Context): FreeformPalette {
            val night = (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return if (night) {
                FreeformPalette(
                    bar = 0xF2303134.toInt(),
                    foreground = 0xFFC4C7C5.toInt(),
                    pressed = 0x33FFFFFF,
                    veil = 0xFF202124.toInt(),
                )
            } else {
                FreeformPalette(
                    bar = 0xF2F1F3F4.toInt(),
                    foreground = 0xFF5F6368.toInt(),
                    pressed = 0x22000000,
                    veil = 0xFFE8EAED.toInt(),
                )
            }
        }
    }
}

/** Overlay window parameters shared by every decoration window. */
internal fun freeformOverlayParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
    var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    return WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }
    }
}

internal enum class CaptionButton { MAXIMIZE, MINIMIZE, CLOSE }

/**
 * Draws the caption bar: rounded top corners, a drag pill in the middle, a maximize button on
 * the left and minimize / close buttons on the right. Shared by the real bar and the drag proxy.
 */
internal class CaptionPainter(private val density: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.8f * density
    }
    private val path = Path()
    private val rect = RectF()
    private val radii = FloatArray(8)

    val buttonWidth: Float get() = 40f * density

    fun buttonAt(x: Float, width: Int): CaptionButton? = when {
        x < buttonWidth -> CaptionButton.MAXIMIZE
        x > width - buttonWidth -> CaptionButton.CLOSE
        x > width - 2 * buttonWidth -> CaptionButton.MINIMIZE
        else -> null
    }

    fun draw(canvas: Canvas, width: Int, height: Int, palette: FreeformPalette, pressed: CaptionButton?) {
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = 14f * density
        radii.fill(0f)
        for (i in 0..3) radii[i] = corner
        path.reset()
        rect.set(0f, 0f, w, h)
        path.addRoundRect(rect, radii, Path.Direction.CW)
        fill.color = palette.bar
        canvas.drawPath(path, fill)

        val cy = h / 2f
        // Drag pill
        fill.color = palette.foreground
        val pillW = min(32f * density, w / 4f)
        val pillH = 4f * density
        rect.set(w / 2f - pillW / 2f, cy - pillH / 2f, w / 2f + pillW / 2f, cy + pillH / 2f)
        canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, fill)

        if (w < 4 * buttonWidth) return
        stroke.color = palette.foreground
        val icon = 5.5f * density
        // Pressed highlight
        if (pressed != null) {
            val cx = centerOf(pressed, w)
            fill.color = palette.pressed
            canvas.drawCircle(cx, cy, 13f * density, fill)
        }
        // Maximize: a rounded square
        var cx = centerOf(CaptionButton.MAXIMIZE, w)
        rect.set(cx - icon, cy - icon, cx + icon, cy + icon)
        canvas.drawRoundRect(rect, 2.5f * density, 2.5f * density, stroke)
        // Minimize: a bar
        cx = centerOf(CaptionButton.MINIMIZE, w)
        canvas.drawLine(cx - icon, cy, cx + icon, cy, stroke)
        // Close: a cross
        cx = centerOf(CaptionButton.CLOSE, w)
        val c = icon * 0.85f
        canvas.drawLine(cx - c, cy - c, cx + c, cy + c, stroke)
        canvas.drawLine(cx - c, cy + c, cx + c, cy - c, stroke)
    }

    private fun centerOf(button: CaptionButton, width: Float): Float = when (button) {
        CaptionButton.MAXIMIZE -> buttonWidth / 2f
        CaptionButton.MINIMIZE -> width - buttonWidth * 1.5f
        CaptionButton.CLOSE -> width - buttonWidth / 2f
    }
}

/** Tracks a drag in screen coordinates; the window under the finger may move meanwhile. */
private class DragTracker(context: Context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocity: VelocityTracker? = null
    var downX = 0f
        private set
    var downY = 0f
        private set
    var dragging = false
        private set

    fun down(event: MotionEvent) {
        downX = event.rawX
        downY = event.rawY
        dragging = false
        velocity?.recycle()
        velocity = VelocityTracker.obtain()
        add(event)
    }

    /** Returns true once the finger moved past the touch slop (and on every move after). */
    fun move(event: MotionEvent): Boolean {
        add(event)
        if (!dragging && hypot(event.rawX - downX, event.rawY - downY) > slop) dragging = true
        return dragging
    }

    fun dx(event: MotionEvent) = event.rawX - downX
    fun dy(event: MotionEvent) = event.rawY - downY

    fun velocityY(): Float {
        val tracker = velocity ?: return 0f
        tracker.computeCurrentVelocity(1000)
        return tracker.yVelocity
    }

    fun movedLittle(event: MotionEvent) = hypot(event.rawX - downX, event.rawY - downY) <= slop

    fun reset() {
        velocity?.recycle()
        velocity = null
        dragging = false
    }

    private fun add(event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        velocity?.addMovement(copy)
        copy.recycle()
    }
}

/** The bar above a small window. */
@SuppressLint("ViewConstructor")
internal class CaptionBarView(
    context: Context,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        /** Finger down away from the buttons: a drag may follow. */
        fun onCaptionPressed()
        fun onDragStart()
        fun onDragMove(dx: Float, dy: Float)
        fun onDragEnd(velocityY: Float, cancelled: Boolean)
        fun onCaptionButton(button: CaptionButton)
    }

    var palette: FreeformPalette = FreeformPalette.from(context)
        set(value) {
            field = value
            invalidate()
        }

    private val painter = CaptionPainter(resources.displayMetrics.density)
    private val tracker = DragTracker(context)
    private var pressedButton: CaptionButton? = null
    private var wasDragging = false

    init {
        contentDescription = context.getString(R.string.freeform_controls_description)
    }

    override fun onDraw(canvas: Canvas) {
        painter.draw(canvas, width, height, palette, pressedButton)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker.down(event)
                pressedButton = painter.buttonAt(event.x, width).takeIf { width >= 4 * painter.buttonWidth }
                if (pressedButton == null) listener.onCaptionPressed() else invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val button = pressedButton
                if (button != null) {
                    if (!tracker.movedLittle(event)) {
                        pressedButton = null
                        invalidate()
                    }
                } else if (tracker.move(event)) {
                    if (!wasDragging) {
                        wasDragging = true
                        listener.onDragStart()
                    }
                    listener.onDragMove(tracker.dx(event), tracker.dy(event))
                }
            }
            MotionEvent.ACTION_UP -> {
                val button = pressedButton
                pressedButton = null
                invalidate()
                if (wasDragging) {
                    wasDragging = false
                    listener.onDragEnd(tracker.velocityY(), cancelled = false)
                } else if (button != null) {
                    performClick()
                    listener.onCaptionButton(button)
                }
                tracker.reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedButton = null
                invalidate()
                if (wasDragging) {
                    wasDragging = false
                    listener.onDragEnd(0f, cancelled = true)
                }
                tracker.reset()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/** An L-shaped grip just outside a bottom corner of the window. */
@SuppressLint("ViewConstructor")
internal class ResizeHandleView(
    context: Context,
    val corner: FreeformGeometry.Corner,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onResizePressed(corner: FreeformGeometry.Corner)
        fun onResizeStart(corner: FreeformGeometry.Corner)
        fun onResizeMove(corner: FreeformGeometry.Corner, dx: Float, dy: Float)
        fun onResizeEnd(corner: FreeformGeometry.Corner, cancelled: Boolean)
    }

    companion object {
        /** Side of the handle window. */
        const val SIZE_DP = 32f
        /** How far the handle reaches into the window from its corner. */
        const val INSET_DP = 12f
    }

    var palette: FreeformPalette = FreeformPalette.from(context)
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val tracker = DragTracker(context)
    private var resizing = false
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.5f * density
        pathEffect = CornerPathEffect(4f * density)
    }

    override fun onDraw(canvas: Canvas) {
        val inset = INSET_DP * density
        val gap = 3f * density
        val arm = 11f * density
        // The window's corner, in this view's coordinates.
        val cx = if (corner == FreeformGeometry.Corner.BOTTOM_RIGHT) inset else width - inset
        val cy = inset
        val outX = if (corner == FreeformGeometry.Corner.BOTTOM_RIGHT) cx + gap else cx - gap
        val armX = if (corner == FreeformGeometry.Corner.BOTTOM_RIGHT) cx - arm else cx + arm
        path.reset()
        path.moveTo(outX, cy - arm)
        path.lineTo(outX, cy + gap)
        path.lineTo(armX, cy + gap)
        paint.color = palette.foreground
        canvas.drawPath(path, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker.down(event)
                listener.onResizePressed(corner)
            }
            MotionEvent.ACTION_MOVE -> if (tracker.move(event)) {
                if (!resizing) {
                    resizing = true
                    listener.onResizeStart(corner)
                }
                listener.onResizeMove(corner, tracker.dx(event), tracker.dy(event))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (resizing) {
                    resizing = false
                    listener.onResizeEnd(corner, event.actionMasked == MotionEvent.ACTION_CANCEL)
                }
                tracker.reset()
            }
        }
        return true
    }
}

/**
 * Stand-in for the window while it is dragged or resized: the window's last frame (or, until
 * that arrives, a plain veil with the app icon), optionally under a caption bar.
 */
@SuppressLint("ViewConstructor")
internal class SnapshotView(
    context: Context,
    private val icon: Drawable?,
    private val withCaption: Boolean,
    private val captionPx: Int,
) : View(context) {

    var palette: FreeformPalette = FreeformPalette.from(context)
    private val painter = CaptionPainter(resources.displayMetrics.density)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = Rect()
    private var bitmap: Bitmap? = null
    private val iconPx = (44f * resources.displayMetrics.density).toInt()

    fun setBitmap(value: Bitmap?) {
        bitmap = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val top = if (withCaption) captionPx else 0
        if (withCaption) painter.draw(canvas, width, captionPx, palette, null)
        dst.set(0, top, width, height)
        val frame = bitmap
        if (frame != null && !frame.isRecycled) {
            canvas.drawBitmap(frame, null, dst, paint)
            return
        }
        paint.color = palette.veil
        canvas.drawRect(dst, paint)
        val drawable = icon ?: return
        val size = min(iconPx, min(dst.width(), dst.height()) / 2)
        val left = dst.centerX() - size / 2
        val iconTop = dst.centerY() - size / 2
        drawable.setBounds(left, iconTop, left + size, iconTop + size)
        drawable.draw(canvas)
    }
}

/** A minimized small window: the app icon in a circle at the screen edge. */
@SuppressLint("ViewConstructor")
internal class BubbleView(
    context: Context,
    private val icon: Drawable?,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onBubbleTap(view: BubbleView)
        fun onBubbleLongPress(view: BubbleView)
        fun onBubbleDrag(view: BubbleView, dx: Float, dy: Float)
        fun onBubbleDragEnd(view: BubbleView)
    }

    companion object {
        const val SIZE_DP = 50f
        private const val LONG_PRESS_MS = 550L
    }

    var palette: FreeformPalette = FreeformPalette.from(context)
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val tracker = DragTracker(context)
    private val handler = Handler(Looper.getMainLooper())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private var longPressed = false
    private val longPress = Runnable {
        if (!tracker.dragging) {
            longPressed = true
            listener.onBubbleLongPress(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f
        fill.color = palette.bar
        canvas.drawCircle(width / 2f, height / 2f, r - density, fill)
        ring.color = palette.pressed
        canvas.drawCircle(width / 2f, height / 2f, r - density, ring)
        val drawable = icon ?: return
        val size = (r * 1.3f).toInt()
        val left = (width - size) / 2
        val top = (height - size) / 2
        drawable.setBounds(left, top, left + size, top + size)
        drawable.draw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker.down(event)
                longPressed = false
                handler.postDelayed(longPress, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> if (!longPressed && tracker.move(event)) {
                handler.removeCallbacks(longPress)
                listener.onBubbleDrag(this, tracker.dx(event), tracker.dy(event))
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                when {
                    tracker.dragging -> listener.onBubbleDragEnd(this)
                    !longPressed -> {
                        performClick()
                        listener.onBubbleTap(this)
                    }
                }
                tracker.reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (tracker.dragging) listener.onBubbleDragEnd(this)
                tracker.reset()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }
}
