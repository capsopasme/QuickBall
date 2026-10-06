package io.github.chayanforyou.quickball.ui.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Magnifier
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.domain.AppPreference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen crop UI for the partial screenshot: shows the frozen screenshot, lets the user
 * draw a selection, then move it or drag its corners/edges, and offers Cancel / Copy / Share /
 * Save next to it. Everything is drawn on one canvas; springs drive the capture flash, the dim,
 * the toolbar and the exit fade.
 *
 * [screen] is the display area (screen coordinates) that [shot] covers, so the selection maps
 * back to screenshot pixels even if this window is not exactly at the display origin.
 */
@SuppressLint("ViewConstructor")
class CropOverlayView(
    context: Context,
    private val shot: Bitmap,
    private val screen: Rect,
    private val listener: Listener,
) : View(context) {

    enum class Action { SAVE, COPY, SHARE }

    interface Listener {
        fun onCancel()
        fun onConfirm(bitmapRect: Rect, action: Action)
    }

    private class Button(val label: String, val action: Action?) {
        val bounds = RectF()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ---------------------------------------------------------------- state
    private val sel = RectF()
    private val startSel = RectF()
    private val previousSel = RectF()
    private var hasSelection = false
    private var finishing = false
    private var onExitEnd: (() -> Unit)? = null
    private val initialOrientation = resources.configuration.orientation
    private var hadFocus = false
    private var backCallback: Any? = null

    private val location = IntArray(2)
    private val shotDst = RectF()

    private val flash = Spring(1f, 120f, 1f)
    private val dim = Spring(0f, 220f, 1f)
    private val toolbar = Spring(0f, 520f, 0.72f)
    private val sizeLabel = Spring(0f, 600f, 1f)
    private val exit = Spring(1f, 650f, 1f)
    private var lastFrameMs = 0L

    // ---------------------------------------------------------------- touch
    private var mode = MODE_NONE
    private var edges = 0
    private var downX = 0f
    private var downY = 0f
    private var pressedButton = -1
    private val minSize = dp(24f)
    private val cornerSlop = dp(28f)
    private val edgeSlop = dp(20f)
    private var magnifier: Magnifier? = null

    // ---------------------------------------------------------------- paints
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0xE6FFFFFF.toInt()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        textAlign = Paint.Align.LEFT
        fontFeatureSettings = "tnum"
        color = 0xFFFFFFFF.toInt()
    }
    private val tmp = RectF()

    private val accent = AppPreference.getInstance(context).toastFgColor or 0xFF000000.toInt()
    private val hint = context.getString(R.string.shot_hint)
    private val buttons = listOf(
        Button(context.getString(R.string.shot_cancel), null),
        Button(context.getString(R.string.shot_copy), Action.COPY),
        Button(context.getString(R.string.shot_share), Action.SHARE),
        Button(context.getString(R.string.shot_save), Action.SAVE),
    )
    private val toolbarBounds = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        dim.animateTo(1f)
        flash.animateTo(0f)
    }

    // ================================================================ lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { cancel() }
            findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback
            )
            backCallback = callback
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (backCallback as? OnBackInvokedCallback)?.let {
                findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(it)
            }
        }
        backCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) magnifier?.dismiss()
        magnifier = null
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        getLocationOnScreen(location)
        shotDst.set(
            (screen.left - location[0]).toFloat(),
            (screen.top - location[1]).toFloat(),
            (screen.right - location[0]).toFloat(),
            (screen.bottom - location[1]).toFloat(),
        )
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // Going home, opening the shade or another window taking over ends the crop.
        if (hasWindowFocus) hadFocus = true else if (hadFocus) cancel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != initialOrientation) cancel()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) cancel()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Fades the overlay out, then runs [onEnd] (the controller removes the window there). */
    fun dismiss(onEnd: () -> Unit) {
        finishing = true
        onExitEnd = onEnd
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) magnifier?.dismiss()
        exit.animateTo(0f)
        invalidate()
    }

    private fun cancel() {
        if (finishing) return
        finishing = true
        listener.onCancel()
    }

    private fun confirm(action: Action) {
        if (finishing || !hasSelection) return
        finishing = true
        listener.onConfirm(bitmapRect(), action)
    }

    // ================================================================ drawing

    override fun onDraw(canvas: Canvas) {
        val now = AnimationUtils.currentAnimationTimeMillis()
        val dt = if (lastFrameMs == 0L) 1f / 60f else (now - lastFrameMs).coerceAtLeast(0L) / 1000f
        lastFrameMs = now

        val showToolbar = hasSelection && (mode == MODE_NONE || mode == MODE_BUTTON) && !finishing
        toolbar.animateTo(if (showToolbar) 1f else 0f)
        sizeLabel.animateTo(if (mode == MODE_CREATE || mode == MODE_RESIZE) 1f else 0f)

        var moving = flash.step(dt)
        moving = dim.step(dt) or moving
        moving = toolbar.step(dt) or moving
        moving = sizeLabel.step(dt) or moving
        moving = exit.step(dt) or moving

        val alpha = exit.value.coerceIn(0f, 1f)
        val save = canvas.save()
        if (alpha < 0.999f) {
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).roundToInt())
        }
        drawContent(canvas)
        canvas.restoreToCount(save)

        if (moving) postInvalidateOnAnimation() else lastFrameMs = 0L

        if (finishing && alpha < 0.02f) {
            onExitEnd?.let { end ->
                onExitEnd = null
                post { end() }
            }
        }
    }

    private fun drawContent(canvas: Canvas) {
        canvas.drawBitmap(shot, null, shotDst, bitmapPaint)

        val d = dim.value.coerceIn(0f, 1f)
        val selecting = hasSelection || mode == MODE_CREATE
        fillPaint.color = (((if (selecting) 0.55f else 0.35f) * d * 255).roundToInt() shl 24)
        if (selecting) {
            val s = canvas.save()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canvas.clipOutRect(sel)
            } else {
                @Suppress("DEPRECATION")
                canvas.clipRect(sel, Region.Op.DIFFERENCE)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
            canvas.restoreToCount(s)
            drawSelection(canvas)
        } else {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
            drawHint(canvas, d)
        }

        val f = flash.value.coerceIn(0f, 1f)
        if (f > 0.01f) {
            fillPaint.color = ((0.45f * f * 255).roundToInt() shl 24) or 0xFFFFFF
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        }
    }

    private fun drawHint(canvas: Canvas, alpha: Float) {
        textPaint.color = 0xFFFFFFFF.toInt()
        val tw = textPaint.measureText(hint)
        val h = dp(44f)
        tmp.set(width / 2f - tw / 2f - dp(22f), height / 2f - h / 2f, width / 2f + tw / 2f + dp(22f), height / 2f + h / 2f)
        fillPaint.color = ((0.75f * alpha * 255).roundToInt() shl 24) or 0x1F1F1F
        canvas.drawRoundRect(tmp, h / 2f, h / 2f, fillPaint)
        textPaint.alpha = (alpha * 255).roundToInt()
        canvas.drawText(hint, width / 2f, tmp.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private fun drawSelection(canvas: Canvas) {
        canvas.drawRect(sel, borderPaint)

        // Corner brackets
        val len = min(dp(18f), min(sel.width(), sel.height()) / 2f)
        val l = sel.left
        val t = sel.top
        val r = sel.right
        val b = sel.bottom
        canvas.drawLine(l, t, l + len, t, handlePaint)
        canvas.drawLine(l, t, l, t + len, handlePaint)
        canvas.drawLine(r, t, r - len, t, handlePaint)
        canvas.drawLine(r, t, r, t + len, handlePaint)
        canvas.drawLine(l, b, l + len, b, handlePaint)
        canvas.drawLine(l, b, l, b - len, handlePaint)
        canvas.drawLine(r, b, r - len, b, handlePaint)
        canvas.drawLine(r, b, r, b - len, handlePaint)

        // Edge grips when there is room for them between the brackets
        val grip = dp(8f)
        if (sel.width() > dp(84f)) {
            canvas.drawLine(sel.centerX() - grip, t, sel.centerX() + grip, t, handlePaint)
            canvas.drawLine(sel.centerX() - grip, b, sel.centerX() + grip, b, handlePaint)
        }
        if (sel.height() > dp(84f)) {
            canvas.drawLine(l, sel.centerY() - grip, l, sel.centerY() + grip, handlePaint)
            canvas.drawLine(r, sel.centerY() - grip, r, sel.centerY() + grip, handlePaint)
        }

        val labelAlpha = sizeLabel.value.coerceIn(0f, 1f)
        if (labelAlpha > 0.01f) drawSizeLabel(canvas, labelAlpha)

        val tb = toolbar.value
        if (tb > 0.01f) drawToolbar(canvas, tb)
    }

    private fun drawSizeLabel(canvas: Canvas, alpha: Float) {
        val px = bitmapRect()
        val text = "${px.width()} × ${px.height()}"
        val tw = smallTextPaint.measureText(text)
        val h = dp(24f)
        var x = sel.left
        var y = sel.top - h - dp(8f)
        if (y < dp(8f)) y = sel.top + dp(8f)
        x = x.coerceIn(dp(8f), max(dp(8f), width - tw - dp(24f)))
        tmp.set(x, y, x + tw + dp(16f), y + h)
        fillPaint.color = ((0.8f * alpha * 255).roundToInt() shl 24) or 0x1F1F1F
        canvas.drawRoundRect(tmp, h / 2f, h / 2f, fillPaint)
        smallTextPaint.alpha = (alpha * 255).roundToInt()
        canvas.drawText(text, x + dp(8f), tmp.centerY() - (smallTextPaint.ascent() + smallTextPaint.descent()) / 2f, smallTextPaint)
    }

    private fun layoutToolbar() {
        val pad = dp(4f)
        val h = dp(44f)
        var total = pad
        for (button in buttons) total += textPaint.measureText(button.label) + dp(32f)
        total += pad

        val margin = dp(12f)
        val left = (sel.centerX() - total / 2f).coerceIn(margin, max(margin, width - total - margin))
        val below = sel.bottom + dp(14f)
        val above = sel.top - dp(14f) - h
        val top = when {
            below + h <= height - dp(56f) -> below
            above >= dp(48f) -> above
            else -> sel.bottom - dp(14f) - h
        }
        toolbarBounds.set(left, top, left + total, top + h)

        var x = left + pad
        for (button in buttons) {
            val w = textPaint.measureText(button.label) + dp(32f)
            button.bounds.set(x, top + pad, x + w, top + h - pad)
            x += w
        }
    }

    private fun drawToolbar(canvas: Canvas, t: Float) {
        layoutToolbar()
        val alpha = t.coerceIn(0f, 1f)
        val s = canvas.save()
        canvas.translate(0f, (1f - t) * dp(10f))
        val scale = lerp(0.9f, 1f, t)
        canvas.scale(scale, scale, toolbarBounds.centerX(), toolbarBounds.centerY())

        val radius = toolbarBounds.height() / 2f
        fillPaint.color = ((0.92f * alpha * 255).roundToInt() shl 24) or 0x1F1F1F
        canvas.drawRoundRect(toolbarBounds, radius, radius, fillPaint)

        buttons.forEachIndexed { i, button ->
            val primary = button.action == Action.SAVE
            val pressed = i == pressedButton
            val br = button.bounds.height() / 2f
            if (primary) {
                fillPaint.color = accent
                fillPaint.alpha = ((if (pressed) 0.8f else 1f) * alpha * 255).roundToInt()
                canvas.drawRoundRect(button.bounds, br, br, fillPaint)
            } else if (pressed) {
                fillPaint.color = ((0.18f * alpha * 255).roundToInt() shl 24) or 0xFFFFFF
                canvas.drawRoundRect(button.bounds, br, br, fillPaint)
            }
            textPaint.color = if (primary) contrastOn(accent) else 0xFFFFFFFF.toInt()
            textPaint.alpha = (alpha * 255).roundToInt()
            val baseline = button.bounds.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(button.label, button.bounds.centerX(), baseline, textPaint)
        }
        canvas.restoreToCount(s)
    }

    private fun contrastOn(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        return if (luminance > 150f) 0xFF1F1F1F.toInt() else 0xFFFFFFFF.toInt()
    }

    // ================================================================ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (finishing) return true
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                startSel.set(sel)
                pressedButton = -1
                if (hasSelection && toolbar.value > 0.5f) {
                    val i = buttons.indexOfFirst { it.bounds.contains(x, y) }
                    if (i >= 0) {
                        pressedButton = i
                        mode = MODE_BUTTON
                        invalidate()
                        return true
                    }
                }
                if (hasSelection) {
                    edges = hitEdges(x, y)
                    mode = when {
                        edges != 0 -> MODE_RESIZE
                        sel.contains(x, y) -> MODE_MOVE
                        else -> MODE_CREATE
                    }
                } else {
                    mode = MODE_CREATE
                }
                if (mode == MODE_CREATE) {
                    previousSel.set(sel)
                    sel.set(x, y, x, y)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    MODE_CREATE -> {
                        sel.set(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
                        clampInside(sel)
                        showMagnifier(x, y)
                    }

                    MODE_MOVE -> {
                        val dx = (x - downX).coerceIn(-startSel.left, width - startSel.right)
                        val dy = (y - downY).coerceIn(-startSel.top, height - startSel.bottom)
                        sel.set(startSel)
                        sel.offset(dx, dy)
                    }

                    MODE_RESIZE -> {
                        resize(x - downX, y - downY)
                        showMagnifier(x, y)
                    }

                    MODE_BUTTON -> {
                        if (pressedButton >= 0 && !buttons[pressedButton].bounds.contains(x, y)) {
                            pressedButton = -1
                        }
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) magnifier?.dismiss()
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                when (mode) {
                    MODE_BUTTON -> {
                        val button = buttons.getOrNull(pressedButton)
                        pressedButton = -1
                        mode = MODE_NONE
                        if (button != null && !cancelled) {
                            val action = button.action
                            if (action == null) cancel() else confirm(action)
                        }
                    }

                    MODE_CREATE -> {
                        if (sel.width() >= minSize && sel.height() >= minSize && !cancelled) {
                            hasSelection = true
                        } else if (hasSelection) {
                            sel.set(previousSel) // a tap outside keeps the old selection
                        } else {
                            sel.setEmpty()
                        }
                        mode = MODE_NONE
                    }

                    else -> mode = MODE_NONE
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitEdges(x: Float, y: Float): Int {
        val nearL = abs(x - sel.left) <= cornerSlop
        val nearR = abs(x - sel.right) <= cornerSlop
        val nearT = abs(y - sel.top) <= cornerSlop
        val nearB = abs(y - sel.bottom) <= cornerSlop
        // Corners first (bigger target), the closer one wins for tiny selections.
        if ((nearL || nearR) && (nearT || nearB)) {
            val horizontal = if (abs(x - sel.left) < abs(x - sel.right)) EDGE_LEFT else EDGE_RIGHT
            val vertical = if (abs(y - sel.top) < abs(y - sel.bottom)) EDGE_TOP else EDGE_BOTTOM
            return horizontal or vertical
        }
        val withinX = x > sel.left - edgeSlop && x < sel.right + edgeSlop
        val withinY = y > sel.top - edgeSlop && y < sel.bottom + edgeSlop
        return when {
            withinY && abs(x - sel.left) <= edgeSlop -> EDGE_LEFT
            withinY && abs(x - sel.right) <= edgeSlop -> EDGE_RIGHT
            withinX && abs(y - sel.top) <= edgeSlop -> EDGE_TOP
            withinX && abs(y - sel.bottom) <= edgeSlop -> EDGE_BOTTOM
            else -> 0
        }
    }

    private fun resize(dx: Float, dy: Float) {
        sel.set(startSel)
        if (edges and EDGE_LEFT != 0) {
            sel.left = (startSel.left + dx).coerceIn(0f, startSel.right - minSize)
        }
        if (edges and EDGE_RIGHT != 0) {
            sel.right = (startSel.right + dx).coerceIn(startSel.left + minSize, width.toFloat())
        }
        if (edges and EDGE_TOP != 0) {
            sel.top = (startSel.top + dy).coerceIn(0f, startSel.bottom - minSize)
        }
        if (edges and EDGE_BOTTOM != 0) {
            sel.bottom = (startSel.bottom + dy).coerceIn(startSel.top + minSize, height.toFloat())
        }
    }

    private fun clampInside(rect: RectF) {
        rect.left = rect.left.coerceIn(0f, width.toFloat())
        rect.right = rect.right.coerceIn(0f, width.toFloat())
        rect.top = rect.top.coerceIn(0f, height.toFloat())
        rect.bottom = rect.bottom.coerceIn(0f, height.toFloat())
    }

    private fun showMagnifier(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val m = magnifier ?: runCatching {
            Magnifier.Builder(this)
                .setInitialZoom(2f)
                .setSize(dp(112f).roundToInt(), dp(112f).roundToInt())
                .setCornerRadius(dp(56f))
                .setDefaultSourceToMagnifierOffset(0, -dp(96f).roundToInt())
                .build()
        }.getOrNull()?.also { magnifier = it } ?: return
        runCatching { m.show(x, y) }
    }

    /** The selection in screenshot pixels. */
    private fun bitmapRect(): Rect {
        val sx = shot.width / screen.width().toFloat()
        val sy = shot.height / screen.height().toFloat()
        val ox = location[0] - screen.left
        val oy = location[1] - screen.top
        val l = ((sel.left + ox) * sx).roundToInt().coerceIn(0, shot.width - 1)
        val t = ((sel.top + oy) * sy).roundToInt().coerceIn(0, shot.height - 1)
        val r = ((sel.right + ox) * sx).roundToInt().coerceIn(l + 1, shot.width)
        val b = ((sel.bottom + oy) * sy).roundToInt().coerceIn(t + 1, shot.height)
        return Rect(l, t, r, b)
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_CREATE = 1
        const val MODE_MOVE = 2
        const val MODE_RESIZE = 3
        const val MODE_BUTTON = 4

        const val EDGE_LEFT = 1
        const val EDGE_TOP = 2
        const val EDGE_RIGHT = 4
        const val EDGE_BOTTOM = 8
    }
}
