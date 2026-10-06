package io.github.chayanforyou.quickball.ui.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Build
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AnimationUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.domain.AppPreference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * iOS / Dynamic Island style volume HUD, drawn on one canvas so every part moves on its own
 * spring.
 *
 * Collapsed it is a black capsule just below the status bar: it grows out of a smaller pill,
 * the speaker's sound waves come and go with the level, the fill springs to the new value, the
 * percentage rolls, and the whole capsule stretches like rubber when a step hits 0 % or 100 %.
 * Dragging sideways sets the volume (with a light tick per step), tapping or pulling it down
 * morphs the capsule into a panel: the media bar glides into the first row while ringtone,
 * notifications, alarm and call fade in one after another, plus a ringer-mode button and a
 * shortcut to the system volume panel.
 *
 * Window management and AudioManager calls live in the controller ([Host]); this view only
 * renders state and reports what the user chose. Springs are stepped from onDraw and a new
 * frame is requested only while something still moves, so an idle HUD costs nothing.
 */
@SuppressLint("ViewConstructor")
class VolumePanelView(context: Context, private val host: Host) : View(context) {

    interface Host {
        /** The user dragged or tapped a bar to a new volume index. */
        fun onVolumeChosen(stream: Int, index: Int)

        /** A finger went down on the HUD (true) or was lifted (false); drives auto-hide. */
        fun onTouchActive(active: Boolean)

        /** The panel is about to expand; the window must grow to the expanded size first. */
        fun onExpandStarted()

        /** The collapse animation has settled; the window may shrink back. */
        fun onCollapseSettled()

        /** The hide animation finished; the window can be removed. */
        fun onHidden()

        fun onRingerModeClicked()
        fun onSystemPanelClicked()
    }

    class Row internal constructor(
        val stream: Int,
        @param:StringRes val labelRes: Int,
        @param:DrawableRes val iconRes: Int,
    ) {
        var min = 0
            internal set
        var max = 1
            internal set
        var index = 0
            internal set

        internal val level = Spring(0f, LEVEL_STIFFNESS, LEVEL_DAMPING)
        internal val stretch = Spring(0f, STRETCH_STIFFNESS, STRETCH_DAMPING)
        internal val press = Spring(0f, 900f, 1f)
        internal val mute = Spring(0f, 520f, 0.9f)
        internal var label = ""
        internal var icon: Drawable? = null
        internal var lastAudibleIndex = 0
        internal val bar = RectF()

        internal val minFraction: Float get() = fractionOf(min)
        internal fun fractionOf(i: Int): Float = if (max > 0) i.toFloat() / max else 0f
    }

    val rows: List<Row> = listOf(
        Row(AudioManager.STREAM_MUSIC, R.string.volume_stream_media, 0),
        Row(AudioManager.STREAM_RING, R.string.volume_stream_ring, R.drawable.ic_stream_ring),
        Row(AudioManager.STREAM_NOTIFICATION, R.string.volume_stream_notification, R.drawable.ic_notification),
        Row(AudioManager.STREAM_ALARM, R.string.volume_stream_alarm, R.drawable.ic_stream_alarm),
        Row(AudioManager.STREAM_VOICE_CALL, R.string.volume_stream_call, R.drawable.ic_stream_call),
    )
    val mediaRow: Row get() = rows[0]

    var isExpanded = false
        private set
    var isHiding = false
        private set

    // ---------------------------------------------------------------- geometry
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    val marginPx = dp(MARGIN_DP).roundToInt()
    private val collapsedW = dp(COLLAPSED_W_DP)
    private val collapsedH = dp(COLLAPSED_H_DP)
    private val stretchW = dp(STRETCH_W_DP)
    private val expandedW = dp(EXPANDED_W_DP)
    private val headerH = dp(HEADER_H_DP)
    private val rowH = dp(ROW_H_DP)
    private val expandedH = headerH + rowH * rows.size + dp(BOTTOM_PAD_DP)

    val collapsedWindowWidth = (collapsedW + stretchW + 2 * marginPx).roundToInt()
    val collapsedWindowHeight = (collapsedH + 2 * marginPx).roundToInt()
    val expandedWindowWidth = (max(expandedW, collapsedW + stretchW) + 2 * marginPx).roundToInt()
    val expandedWindowHeight = (expandedH + 2 * marginPx).roundToInt()

    private val barH = dp(6f)
    private val barHPressed = dp(10f)
    private val barStretch = dp(12f)
    private val buttonRadius = dp(18f)

    // ---------------------------------------------------------------- springs
    private val appear = Spring(0f, 300f, 0.62f)
    private val expansion = Spring(0f, 340f, 0.84f)
    private val iconBump = Spring(0f, 700f, 0.32f)
    private val wave1 = Spring(0f, 420f, 0.8f)
    private val wave2 = Spring(0f, 420f, 0.8f)
    private val ringerPop = Spring(0f, 700f, 0.35f)
    private val ringerPress = Spring(0f, 900f, 1f)
    private val systemPress = Spring(0f, 900f, 1f)
    private var lastFrameMs = 0L
    private var collapseNotified = true
    private var hiddenNotified = false

    // ---------------------------------------------------------------- drawing state
    private val prefs = AppPreference.getInstance(context)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG_COLOR }
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        fontFeatureSettings = "tnum"
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(13f) }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = sp(17f)
    }

    private val card = RectF()
    private val tmp = RectF()
    private val ringerButton = RectF()
    private val systemButton = RectF()
    private val title = context.getString(R.string.volume_panel_title)

    private val speaker = PathData.parse(SPEAKER_PATH)
    private val wave1Path = PathData.parse(WAVE1_PATH)
    private val wave2Path = PathData.parse(WAVE2_PATH)
    private var ringerIcon: Drawable? = loadIcon(ringerIconRes(AudioManager.RINGER_MODE_NORMAL))
    private val systemIcon: Drawable? = loadIcon(R.drawable.ic_open_external)

    /** Current ringer mode, shown on the header button. */
    var ringerMode: Int = AudioManager.RINGER_MODE_NORMAL
        set(value) {
            if (field != value) {
                field = value
                ringerIcon = loadIcon(ringerIconRes(value))
                ringerPop.kick(5f)
                invalidate()
            }
        }

    // ---------------------------------------------------------------- touch state
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var touchMode = TOUCH_NONE
    private var touchRow: Row? = null
    private var dragRow: Row? = null
    private var dragStartFraction = 0f
    private var dragBarWidth = 1f
    private var limitTicked = false

    init {
        isHapticFeedbackEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        for (row in rows) {
            row.label = context.getString(row.labelRes)
            if (row.iconRes != 0) row.icon = loadIcon(row.iconRes)
        }
    }

    private fun loadIcon(@DrawableRes res: Int): Drawable? =
        ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(FG_COLOR) }

    private fun ringerIconRes(mode: Int) = when (mode) {
        AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_vibrate
        AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_silent
        else -> R.drawable.ic_notification
    }

    // ================================================================ public API

    fun setRange(row: Row, min: Int, max: Int) {
        row.max = max.coerceAtLeast(1)
        row.min = min.coerceIn(0, row.max)
    }

    /** Applies the system's volume for [row]; animated unless the HUD is just appearing. */
    fun setIndex(row: Row, index: Int, animate: Boolean) {
        val clamped = index.coerceIn(0, row.max)
        row.index = clamped
        if (clamped > 0) row.lastAudibleIndex = clamped
        if (row === dragRow) return // the finger owns the bar until it lifts
        val fraction = row.fractionOf(clamped)
        if (animate) {
            row.level.animateTo(fraction, LEVEL_STIFFNESS, LEVEL_DAMPING)
        } else {
            row.level.snapTo(fraction)
            row.mute.snapTo(if (clamped == 0) 1f else 0f)
            if (row === mediaRow) {
                wave1.snapTo(if (fraction > 0f) 1f else 0f)
                wave2.snapTo(if (fraction > 0.5f) 1f else 0f)
            }
        }
        invalidate()
    }

    /** Feedback for a volume-key style step; [limitReached] = the volume could not move. */
    fun onStepped(stream: Int, up: Boolean, limitReached: Boolean) {
        val row = rows.firstOrNull { it.stream == stream } ?: return
        if (limitReached) row.stretch.kick(if (up) LIMIT_KICK else -LIMIT_KICK)
        if (row === mediaRow) iconBump.kick(if (up) 4.5f else -3.5f)
        invalidate()
    }

    fun show() {
        isHiding = false
        hiddenNotified = false
        appear.animateTo(1f, 300f, 0.62f)
        invalidate()
    }

    fun hide() {
        if (isHiding) return
        isHiding = true
        releaseTouch()
        if (isExpanded) {
            // Fold back into the capsule while it shrinks away, like the island closing.
            isExpanded = false
            collapseNotified = false
            expansion.animateTo(0f, 380f, 0.92f)
        }
        appear.animateTo(0f, 420f, 1f)
        invalidate()
    }

    fun expand() {
        if (isExpanded) return
        isExpanded = true
        host.onExpandStarted()
        expansion.animateTo(1f, 340f, 0.84f)
        invalidate()
    }

    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        collapseNotified = false
        expansion.animateTo(0f, 380f, 0.92f)
        invalidate()
    }

    // ================================================================ frame loop

    override fun onDraw(canvas: Canvas) {
        val now = AnimationUtils.currentAnimationTimeMillis()
        val dt = if (lastFrameMs == 0L) 1f / 60f else (now - lastFrameMs).coerceAtLeast(0L) / 1000f
        lastFrameMs = now

        val moving = stepAll(dt)
        drawPanel(canvas)

        if (moving) postInvalidateOnAnimation() else lastFrameMs = 0L

        if (!isExpanded && !collapseNotified && expansion.isAtRest) {
            collapseNotified = true
            host.onCollapseSettled()
        }
        if (isHiding && !hiddenNotified && appear.value < 0.02f) {
            hiddenNotified = true
            // Removing the window from inside onDraw would detach mid-traversal.
            post { host.onHidden() }
        }
    }

    private fun stepAll(dt: Float): Boolean {
        val mediaLevel = mediaRow.level.value
        wave1.animateTo(if (mediaLevel > 0.002f) 1f else 0f)
        wave2.animateTo(if (mediaLevel > 0.5f) 1f else 0f)
        for (row in rows) {
            row.mute.animateTo(if (row.index == 0 && row.level.value < 0.002f) 1f else 0f)
        }

        var moving = appear.step(dt)
        moving = expansion.step(dt) or moving
        moving = iconBump.step(dt) or moving
        moving = wave1.step(dt) or moving
        moving = wave2.step(dt) or moving
        moving = ringerPop.step(dt) or moving
        moving = ringerPress.step(dt) or moving
        moving = systemPress.step(dt) or moving
        for (row in rows) {
            moving = row.level.step(dt) or moving
            moving = row.stretch.step(dt) or moving
            moving = row.press.step(dt) or moving
            moving = row.mute.step(dt) or moving
        }
        return moving
    }

    // ================================================================ drawing

    private fun drawPanel(canvas: Canvas) {
        val a = appear.value
        if (a <= 0.001f) return
        val e = expansion.value
        val ec = e.coerceIn(0f, 1f)
        val collapsedPart = 1f - ec

        // Grows out of a smaller pill (Dynamic Island), stretches sideways at a volume limit.
        val reveal = lerp(0.6f, 1f, a)
        val stretch = abs(mediaRow.stretch.value).coerceAtMost(1.2f) * collapsedPart
        val w = lerp(collapsedW, expandedW, e) * lerp(0.5f, 1f, a) + stretchW * stretch
        val h = lerp(collapsedH, expandedH, e) * reveal * (1f - 0.08f * stretch)
        val top = marginPx + (collapsedH - collapsedH * reveal) / 2f * collapsedPart
        val left = width / 2f - w / 2f
        card.set(left, top, left + w, top + h)

        val bgAlpha = (a / 0.35f).coerceIn(0f, 1f)
        bgPaint.alpha = (bgAlpha * (BG_COLOR ushr 24)).roundToInt()
        // Pill while collapsed, a rounded card while it grows (not one huge blob).
        val radius = lerp(collapsedH * reveal / 2f, dp(28f), ec).coerceAtMost(h / 2f)
        canvas.drawRoundRect(card, radius, radius, bgPaint)

        // Content appears once the capsule is mostly open and leaves early when it closes.
        val contentAlpha = ((a - 0.5f) / 0.5f).coerceIn(0f, 1f)
        if (contentAlpha <= 0.001f) return

        val save = canvas.save()
        canvas.clipRect(card)
        if (contentAlpha < 0.999f) {
            canvas.saveLayerAlpha(card.left, card.top, card.right, card.bottom, (contentAlpha * 255).roundToInt())
        }
        drawContent(canvas, e, ec)
        canvas.restoreToCount(save)
    }

    private fun drawContent(canvas: Canvas, e: Float, ec: Float) {
        val left = card.left
        val right = card.right
        val top = card.top

        // Grabber hinting that the capsule opens into a panel
        val grabberAlpha = 1f - smoothStep(0f, 0.3f, e)
        if (grabberAlpha > 0.01f) {
            shapePaint.color = withAlpha(FG_COLOR, 0.28f * grabberAlpha)
            val gw = dp(16f)
            tmp.set(card.centerX() - gw / 2f, card.bottom - dp(5f), card.centerX() + gw / 2f, card.bottom - dp(2.5f))
            canvas.drawRoundRect(tmp, dp(1.5f), dp(1.5f), shapePaint)
        }

        val headerAlpha = smoothStep(0.3f, 0.85f, e)
        if (headerAlpha > 0.01f) drawHeader(canvas, left, right, top, headerAlpha)

        // ---- media row, shared by both states: it glides from the capsule into row 0
        val cy0 = top + collapsedH / 2f
        val rowTop0 = top + headerH
        val iconSize = lerp(dp(18f), dp(22f), ec)
        val iconCx = lerp(left + dp(13f) + iconSize / 2f, left + dp(18f) + iconSize / 2f, ec)
        val iconCy = lerp(cy0, rowTop0 + rowH / 2f, ec)
        drawSpeaker(canvas, iconCx, iconCy, iconSize)

        val barLeft = lerp(left + dp(41f), left + dp(52f), ec)
        val barRight = lerp(right - dp(54f), right - dp(18f), ec)
        val barCy = lerp(cy0, rowTop0 + dp(38f), ec)
        drawBar(canvas, mediaRow, barLeft, barRight, barCy, 1f, ec)

        percentPaint.textSize = sp(13f)
        percentPaint.color = withAlpha(FG_COLOR, lerp(1f, 0.85f, ec))
        val percentBaseline = lerp(cy0 + percentPaint.textSize * 0.36f, rowTop0 + dp(23f), ec)
        canvas.drawText(percentText(mediaRow), lerp(right - dp(14f), right - dp(18f), ec), percentBaseline, percentPaint)

        val labelAlpha = smoothStep(0.35f, 0.9f, e)
        if (labelAlpha > 0.01f) drawLabel(canvas, mediaRow.label, left + dp(52f), rowTop0 + dp(23f), labelAlpha)

        // ---- the other streams, staggered in
        for (i in 1 until rows.size) {
            val row = rows[i]
            val rowAlpha = smoothStep(0.35f + 0.06f * i, 0.9f + 0.02f * i, e)
            if (rowAlpha <= 0.01f) {
                row.bar.setEmpty()
                continue
            }
            val rowTop = top + headerH + rowH * i + (1f - rowAlpha) * dp(12f)
            drawRow(canvas, row, left, right, rowTop, rowAlpha)
        }
    }

    private fun drawHeader(canvas: Canvas, left: Float, right: Float, top: Float, alpha: Float) {
        titlePaint.color = withAlpha(FG_COLOR, alpha)
        canvas.drawText(title, left + dp(20f), top + headerH / 2f + titlePaint.textSize * 0.36f, titlePaint)

        val cy = top + headerH / 2f + dp(1f)
        val sysCx = right - dp(16f) - buttonRadius
        val ringCx = sysCx - buttonRadius * 2f - dp(8f)
        systemButton.set(sysCx - buttonRadius, cy - buttonRadius, sysCx + buttonRadius, cy + buttonRadius)
        ringerButton.set(ringCx - buttonRadius, cy - buttonRadius, ringCx + buttonRadius, cy + buttonRadius)

        drawRoundButton(canvas, ringerButton, ringerIcon, alpha, ringerPress.value, ringerPop.value)
        drawRoundButton(canvas, systemButton, systemIcon, alpha, systemPress.value, 0f)
    }

    private fun drawRoundButton(
        canvas: Canvas,
        bounds: RectF,
        icon: Drawable?,
        alpha: Float,
        pressed: Float,
        pop: Float,
    ) {
        val r = bounds.width() / 2f * (1f - pressed * 0.06f)
        shapePaint.color = withAlpha(FG_COLOR, (0.14f + 0.14f * pressed) * alpha)
        canvas.drawCircle(bounds.centerX(), bounds.centerY(), r, shapePaint)
        if (icon != null) {
            val s = dp(19f) * (1f + pop)
            val l = (bounds.centerX() - s / 2f).roundToInt()
            val t = (bounds.centerY() - s / 2f).roundToInt()
            icon.setBounds(l, t, l + s.roundToInt(), t + s.roundToInt())
            icon.alpha = (alpha * 255).roundToInt()
            icon.draw(canvas)
        }
    }

    private fun drawRow(canvas: Canvas, row: Row, left: Float, right: Float, rowTop: Float, alpha: Float) {
        val icon = row.icon
        if (icon != null) {
            val size = dp(22f)
            val cx = left + dp(18f) + size / 2f
            val cy = rowTop + rowH / 2f
            val l = (cx - size / 2f).roundToInt()
            val t = (cy - size / 2f).roundToInt()
            val s = size.roundToInt()
            icon.setBounds(l, t, l + s, t + s)
            val mute = row.mute.value.coerceIn(0f, 1f)
            icon.alpha = (alpha * lerp(1f, 0.55f, mute) * 255).roundToInt()
            icon.draw(canvas)
            if (mute > 0.01f) {
                // A slash sweeps across the icon as the stream reaches zero.
                strokePaint.color = withAlpha(FG_COLOR, alpha)
                strokePaint.strokeWidth = dp(2f)
                val inset = size * 0.12f
                canvas.drawLine(
                    l + inset, t + inset,
                    lerp(l + inset, l + s - inset, mute), lerp(t + inset, t + s - inset, mute),
                    strokePaint,
                )
            }
        }
        drawLabel(canvas, row.label, left + dp(52f), rowTop + dp(23f), alpha)
        percentPaint.textSize = sp(13f)
        percentPaint.color = withAlpha(FG_COLOR, 0.85f * alpha)
        canvas.drawText(percentText(row), right - dp(18f), rowTop + dp(23f), percentPaint)
        drawBar(canvas, row, left + dp(52f), right - dp(18f), rowTop + dp(38f), alpha, 1f)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, baseline: Float, alpha: Float) {
        labelPaint.color = withAlpha(FG_COLOR, 0.7f * alpha)
        canvas.drawText(text, x, baseline, labelPaint)
    }

    /**
     * Track + fill. [ownStretch] says how much of the rubber band the bar shows itself (in the
     * panel); in the capsule the whole capsule stretches instead.
     */
    private fun drawBar(
        canvas: Canvas,
        row: Row,
        left: Float,
        right: Float,
        cy: Float,
        alpha: Float,
        ownStretch: Float,
    ) {
        row.bar.set(left, cy - dp(20f), right, cy + dp(20f))

        val st = row.stretch.value * ownStretch
        val thickness = lerp(barH, barHPressed, row.press.value) * (1f - abs(st).coerceAtMost(1f) * 0.2f)
        val half = thickness / 2f
        var l = left
        var r = right
        if (st > 0f) r += st * barStretch else l += st * barStretch

        shapePaint.color = withAlpha(FG_COLOR, 0.25f * alpha)
        tmp.set(l, cy - half, r, cy + half)
        canvas.drawRoundRect(tmp, half, half, shapePaint)

        val f = row.level.value.coerceIn(0f, 1f)
        if (f > 0.001f) {
            val end = max(l + (r - l) * f, l + thickness)
            shapePaint.color = withAlpha(FG_COLOR, alpha)
            tmp.set(l, cy - half, end, cy + half)
            canvas.drawRoundRect(tmp, half, half, shapePaint)
        }
    }

    /** Speaker whose sound waves grow with the level and turn into a cross when muted. */
    private fun drawSpeaker(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val save = canvas.save()
        val unit = size / 24f
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(unit, unit)
        val bump = 1f + iconBump.value
        canvas.scale(bump, bump, 9f, 12f)

        strokePaint.strokeWidth = 2f
        strokePaint.color = FG_COLOR
        canvas.drawPath(speaker, strokePaint)

        val mute = mediaRow.mute.value.coerceIn(0f, 1f)
        val w1 = wave1.value.coerceIn(0f, 1f) * (1f - mute)
        val w2 = wave2.value.coerceIn(0f, 1f) * (1f - mute)
        if (w1 > 0.01f) {
            strokePaint.color = withAlpha(FG_COLOR, w1)
            val s1 = canvas.save()
            canvas.translate((1f - w1) * -2f, 0f)
            canvas.scale(lerp(0.6f, 1f, w1), lerp(0.6f, 1f, w1), 17f, 12f)
            canvas.drawPath(wave1Path, strokePaint)
            canvas.restoreToCount(s1)
        }
        if (w2 > 0.01f) {
            strokePaint.color = withAlpha(FG_COLOR, w2)
            val s2 = canvas.save()
            canvas.translate((1f - w2) * -3f, 0f)
            canvas.scale(lerp(0.6f, 1f, w2), lerp(0.6f, 1f, w2), 20f, 12f)
            canvas.drawPath(wave2Path, strokePaint)
            canvas.restoreToCount(s2)
        }
        if (mute > 0.01f) {
            strokePaint.color = FG_COLOR
            val d = 4f * mute
            canvas.drawLine(18f, 10f, 18f + d, 10f + d, strokePaint)
            canvas.drawLine(18f, 14f, 18f + d, 14f - d, strokePaint)
        }
        canvas.restoreToCount(save)
    }

    private fun percentText(row: Row): String =
        "${(row.level.value.coerceIn(0f, 1f) * 100f).roundToInt()}%"

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = ((color ushr 24) * alpha.coerceIn(0f, 1f)).roundToInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    // ================================================================ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                if (isExpanded) collapse()
                return true
            }

            MotionEvent.ACTION_DOWN -> {
                // Only the visible card reacts; the transparent margin around it is ignored.
                if (isHiding || appear.value < 0.5f || !card.contains(event.x, event.y)) return false
                downX = event.x
                downY = event.y
                limitTicked = false
                touchMode = TOUCH_PENDING
                touchRow = null
                parent?.requestDisallowInterceptTouchEvent(true)
                host.onTouchActive(true)

                if (isExpanded) {
                    when {
                        ringerButton.contains(downX, downY) -> {
                            touchMode = TOUCH_RINGER
                            ringerPress.animateTo(1f)
                        }

                        systemButton.contains(downX, downY) -> {
                            touchMode = TOUCH_SYSTEM
                            systemPress.animateTo(1f)
                        }

                        downY < card.top + headerH -> touchMode = TOUCH_HEADER
                        else -> touchRow = rowAt(downY)
                    }
                } else {
                    touchRow = mediaRow
                }
                touchRow?.press?.animateTo(1f)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (touchMode == TOUCH_PENDING || touchMode == TOUCH_HEADER) {
                    val row = touchRow
                    if (touchMode == TOUCH_PENDING && row != null &&
                        abs(dx) > touchSlop && abs(dx) > abs(dy)
                    ) {
                        startDrag(row)
                    } else if (abs(dy) > touchSlop * 2f && abs(dy) > abs(dx)) {
                        touchMode = TOUCH_VSWIPE
                        touchRow?.press?.animateTo(0f)
                        invalidate()
                    }
                }
                if (touchMode == TOUCH_DRAG) {
                    dragRow?.let { dragTo(it, dragStartFraction + dx / dragBarWidth) }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val dy = event.y - downY
                when (touchMode) {
                    TOUCH_DRAG -> endDrag()
                    TOUCH_VSWIPE -> when {
                        isExpanded && dy < 0f -> collapse()
                        !isExpanded && dy > 0f -> expand()
                        !isExpanded && dy < 0f -> hide()
                    }

                    TOUCH_RINGER -> if (ringerButton.contains(event.x, event.y)) host.onRingerModeClicked()
                    TOUCH_SYSTEM -> if (systemButton.contains(event.x, event.y)) host.onSystemPanelClicked()
                    TOUCH_HEADER -> collapse()
                    TOUCH_PENDING -> onTap(event.x)
                }
                releaseTouch()
                host.onTouchActive(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                releaseTouch()
                host.onTouchActive(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun rowAt(y: Float): Row? {
        if (y < card.top + headerH) return null
        val i = ((y - card.top - headerH) / rowH).toInt()
        return rows.getOrNull(i)
    }

    private fun onTap(x: Float) {
        if (!isExpanded) {
            expand()
            return
        }
        val row = touchRow ?: return
        if (row.bar.isEmpty) return
        if (x < row.bar.left - dp(8f)) {
            toggleMute(row)
            return
        }
        // Tap on the bar: jump to that spot with a soft spring.
        val width = row.bar.width().coerceAtLeast(1f)
        val fraction = ((x - row.bar.left) / width).coerceIn(row.minFraction, 1f)
        choose(row, (fraction * row.max).roundToInt())
        row.level.animateTo(row.fractionOf(row.index), LEVEL_STIFFNESS, LEVEL_DAMPING)
    }

    private fun toggleMute(row: Row) {
        val target = if (row.index > row.min) {
            row.min
        } else {
            row.lastAudibleIndex.coerceAtLeast(max(row.min, (row.max * 0.3f).roundToInt()))
        }
        choose(row, target)
        row.level.animateTo(row.fractionOf(row.index), LEVEL_STIFFNESS, LEVEL_DAMPING)
        if (row === mediaRow) iconBump.kick(if (target > row.min) 4f else -4f)
    }

    private fun startDrag(row: Row) {
        touchMode = TOUCH_DRAG
        dragRow = row
        dragStartFraction = row.level.target
        dragBarWidth = row.bar.width().coerceAtLeast(1f)
    }

    private fun dragTo(row: Row, raw: Float) {
        val min = row.minFraction
        val clamped = raw.coerceIn(min, 1f)
        row.level.animateTo(clamped, DRAG_STIFFNESS, 1f)

        val over = when {
            raw > 1f -> raw - 1f
            raw < min -> raw - min
            else -> 0f
        }
        row.stretch.animateTo(rubber(over), 1200f, 1f)
        if (over != 0f && !limitTicked) {
            limitTicked = true
            tick(strong = true)
        } else if (over == 0f) {
            limitTicked = false
        }

        val index = (clamped * row.max).roundToInt().coerceIn(row.min, row.max)
        if (index != row.index) {
            val up = index > row.index
            choose(row, index)
            if (row === mediaRow) iconBump.kick(if (up) 1.2f else -1.2f)
        }
        invalidate()
    }

    private fun endDrag() {
        val row = dragRow ?: return
        dragRow = null
        row.level.animateTo(row.fractionOf(row.index), LEVEL_STIFFNESS, LEVEL_DAMPING)
        row.stretch.animateTo(0f, STRETCH_STIFFNESS, STRETCH_DAMPING)
        invalidate()
    }

    private fun choose(row: Row, index: Int) {
        val clamped = index.coerceIn(row.min, row.max)
        if (clamped == row.index) return
        row.index = clamped
        if (clamped > 0) row.lastAudibleIndex = clamped
        tick(strong = false)
        host.onVolumeChosen(row.stream, clamped)
    }

    private fun releaseTouch() {
        endDrag()
        touchMode = TOUCH_NONE
        touchRow = null
        for (row in rows) row.press.animateTo(0f)
        ringerPress.animateTo(0f)
        systemPress.animateTo(0f)
        invalidate()
    }

    private fun tick(strong: Boolean) {
        if (!prefs.isHapticFeedbackEnabled) return
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (strong) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        performHapticFeedback(constant)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lastFrameMs = 0L
    }

    private companion object {
        const val BG_COLOR = 0xEB000000.toInt()
        const val FG_COLOR = 0xFFFFFFFF.toInt()

        const val MARGIN_DP = 10f
        const val COLLAPSED_W_DP = 216f
        const val COLLAPSED_H_DP = 40f
        const val STRETCH_W_DP = 14f
        const val EXPANDED_W_DP = 304f
        const val HEADER_H_DP = 56f
        const val ROW_H_DP = 58f
        const val BOTTOM_PAD_DP = 10f

        const val LEVEL_STIFFNESS = 380f
        const val LEVEL_DAMPING = 0.72f
        const val DRAG_STIFFNESS = 1600f
        const val STRETCH_STIFFNESS = 520f
        const val STRETCH_DAMPING = 0.42f
        const val LIMIT_KICK = 22f

        const val TOUCH_NONE = 0
        const val TOUCH_PENDING = 1
        const val TOUCH_DRAG = 2
        const val TOUCH_VSWIPE = 3
        const val TOUCH_RINGER = 4
        const val TOUCH_SYSTEM = 5
        const val TOUCH_HEADER = 6

        /** Rubber-band response: quick at first, then asymptotically towards ±1. */
        fun rubber(over: Float): Float {
            if (over == 0f) return 0f
            val v = 1f - 1f / (1f + abs(over) * 3f)
            return if (over > 0f) v else -v
        }

        const val SPEAKER_PATH =
            "M14,14.814V9.186C14,6.041 14,4.469 13.075,4.077C12.149,3.686 11.06,4.798 8.882,7.022" +
                "C7.754,8.174 7.111,8.429 5.506,8.429C4.103,8.429 3.401,8.429 2.897,8.773" +
                "C1.85,9.487 2.009,10.882 2.009,12C2.009,13.118 1.85,14.513 2.897,15.227" +
                "C3.401,15.571 4.103,15.571 5.506,15.571C7.111,15.571 7.754,15.826 8.882,16.978" +
                "C11.06,19.202 12.149,20.314 13.075,19.923C14,19.531 14,17.959 14,14.814Z"
        const val WAVE1_PATH = "M17,9C17.625,9.82 18,10.863 18,12C18,13.137 17.625,14.18 17,15"
        const val WAVE2_PATH = "M20,7C21.251,8.366 22,10.106 22,12C22,13.894 21.251,15.634 20,17"
    }
}
