package io.github.chayanforyou.quickball.freeform

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The display a small window lives on, in pixels; insets are the system bars / cutout. */
data class ScreenSpec(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val insetLeft: Int = 0,
    val insetTop: Int = 0,
    val insetRight: Int = 0,
    val insetBottom: Int = 0,
)

/**
 * Size, position and density of a small window.
 *
 * The app inside always sees the same logical size: the phone's own width in dp (the display's
 * short side) and a 16:9 height. A smaller window gets a proportionally lower density, so the
 * app lays out exactly as on the full screen and simply renders fewer pixels: what HyperOS
 * achieves by scaling a full-size surface, without drawing the extra pixels first.
 *
 * Resizing keeps that aspect ratio and changes only the density, i.e. it zooms.
 */
class FreeformGeometry(
    val screen: ScreenSpec,
    /** Decoration drawn above the window (the caption bar). */
    private val captionPx: Int,
    /** Decoration drawn below the window (the resize handles). */
    private val bottomReservePx: Int,
    private val marginPx: Int,
) {
    enum class Corner { BOTTOM_LEFT, BOTTOM_RIGHT }

    companion object {
        /** Height / width of the window content. */
        const val ASPECT = 16f / 9f
        const val MIN_SCALE = 0.35f
        const val MAX_SCALE = 0.95f
        const val DEFAULT_SCALE = 0.6f
        const val DEFAULT_CENTER_X = 0.5f
        const val DEFAULT_TOP = 0.12f
        const val MIN_DPI = 72
    }

    /** The width an app sees as the whole phone screen. */
    val baseShort: Int = max(1, min(screen.width, screen.height))

    // Area the window rectangle itself must stay in (decorations are kept outside of it).
    val areaLeft: Int = screen.insetLeft + marginPx
    val areaRight: Int = max(areaLeft + 1, screen.width - screen.insetRight - marginPx)
    val areaTop: Int = screen.insetTop + captionPx + marginPx
    val areaBottom: Int = max(areaTop + 1, screen.height - screen.insetBottom - bottomReservePx - marginPx)

    private val areaWidth: Int get() = areaRight - areaLeft
    private val areaHeight: Int get() = areaBottom - areaTop

    fun maxWidth(): Int = max(1, min(areaWidth, (areaHeight / ASPECT).toInt()))

    fun minWidth(): Int = min((baseShort * MIN_SCALE).roundToInt(), maxWidth())

    fun heightFor(width: Int): Int = (width * ASPECT).roundToInt()

    fun widthForScale(scale: Float): Int =
        (baseShort * scale.coerceIn(MIN_SCALE, MAX_SCALE)).roundToInt().coerceIn(minWidth(), maxWidth())

    fun scaleOf(width: Int): Float = width.toFloat() / baseShort

    /** Density that makes [width] pixels show the phone's full width in dp. */
    fun dpiFor(width: Int): Int =
        (screen.densityDpi * width.toFloat() / baseShort).roundToInt()
            .coerceIn(min(MIN_DPI, screen.densityDpi), screen.densityDpi)

    fun initialBounds(
        scale: Float = DEFAULT_SCALE,
        centerXFraction: Float = DEFAULT_CENTER_X,
        topFraction: Float = DEFAULT_TOP,
    ): Box {
        val width = widthForScale(scale)
        val height = heightFor(width)
        val left = (centerXFraction.coerceIn(0f, 1f) * screen.width - width / 2f).roundToInt()
        val top = (topFraction.coerceIn(0f, 1f) * screen.height).roundToInt()
        return clamp(Box(left, top, left + width, top + height))
    }

    /**
     * Moves [box] fully into the allowed area. Its size is kept unless it no longer fits (e.g.
     * after a rotation), in which case it shrinks proportionally.
     */
    fun clamp(box: Box): Box {
        var width = max(1, box.width)
        var height = max(1, box.height)
        if (width > areaWidth || height > areaHeight) {
            val factor = min(areaWidth.toFloat() / width, areaHeight.toFloat() / height)
            width = max(1, (width * factor).toInt())
            height = max(1, (height * factor).toInt())
        }
        val left = box.left.coerceIn(areaLeft, max(areaLeft, areaRight - width))
        val top = box.top.coerceIn(areaTop, max(areaTop, areaBottom - height))
        return Box(left, top, left + width, top + height)
    }

    fun moved(start: Box, dx: Float, dy: Float): Box =
        clamp(start.moveTo((start.left + dx).roundToInt(), (start.top + dy).roundToInt()))

    /**
     * Aspect-locked resize from a bottom corner; the opposite top corner stays where it is.
     * The finger's movement is projected onto the window's diagonal.
     */
    fun resized(start: Box, corner: Corner, dx: Float, dy: Float): Box {
        val horizontal = if (corner == Corner.BOTTOM_RIGHT) dx else -dx
        val grow = (horizontal + dy / ASPECT) / 2f
        val roomX = if (corner == Corner.BOTTOM_RIGHT) areaRight - start.left else start.right - areaLeft
        val roomY = ((areaBottom - start.top) / ASPECT).toInt()
        val maxW = max(1, min(roomX, roomY))
        val minW = min(minWidth(), maxW)
        val width = (start.width + grow).roundToInt().coerceIn(minW, maxW)
        val height = heightFor(width)
        return if (corner == Corner.BOTTOM_RIGHT) {
            Box(start.left, start.top, start.left + width, start.top + height)
        } else {
            Box(start.right - width, start.top, start.right, start.top + height)
        }
    }
}
