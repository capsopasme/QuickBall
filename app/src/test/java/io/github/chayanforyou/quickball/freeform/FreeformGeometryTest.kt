package io.github.chayanforyou.quickball.freeform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FreeformGeometryTest {

    // OnePlus Ace 5 class display: 1264 x 2780, 560 dpi, status bar 120 px, nav bar 60 px.
    private val portrait = ScreenSpec(
        width = 1264, height = 2780, densityDpi = 560,
        insetTop = 120, insetBottom = 60,
    )
    private val landscape = ScreenSpec(
        width = 2780, height = 1264, densityDpi = 560,
        insetLeft = 120, insetRight = 60,
    )
    private val caption = 112
    private val reserve = 70
    private val margin = 21

    private fun geometry(screen: ScreenSpec = portrait) = FreeformGeometry(screen, caption, reserve, margin)

    private fun assertInsideArea(g: FreeformGeometry, box: Box) {
        assertTrue("left $box", box.left >= g.areaLeft)
        assertTrue("right $box", box.right <= g.areaRight)
        assertTrue("top $box", box.top >= g.areaTop)
        assertTrue("bottom $box", box.bottom <= g.areaBottom)
    }

    private fun assertAspect(box: Box) {
        assertTrue("aspect $box", abs(box.height - box.width * FreeformGeometry.ASPECT) <= 1.5f)
    }

    @Test
    fun initialBoundsUseScaleAndStayOnScreen() {
        val g = geometry()
        val box = g.initialBounds()
        assertEquals((1264 * FreeformGeometry.DEFAULT_SCALE).toInt(), box.width, 1)
        assertAspect(box)
        assertInsideArea(g, box)
        // Centred horizontally by default.
        assertEquals(1264 / 2, box.centerX, 2)
    }

    @Test
    fun densityKeepsThePhoneWidthInDp() {
        val g = geometry()
        val box = g.initialBounds(scale = 0.6f)
        val dpi = g.dpiFor(box.width)
        val windowDp = box.width * 160f / dpi
        val phoneDp = 1264 * 160f / 560
        assertEquals(phoneDp, windowDp, 1.5f)
        assertTrue(dpi < 560)
    }

    @Test
    fun densityNeverExceedsDisplayOrDropsBelowMinimum() {
        val g = geometry()
        assertEquals(560, g.dpiFor(5000))
        assertEquals(FreeformGeometry.MIN_DPI, g.dpiFor(1))
    }

    @Test
    fun clampMovesWindowBackOnScreen() {
        val g = geometry()
        val start = g.initialBounds()
        val moved = g.moved(start, dx = -5000f, dy = -5000f)
        assertEquals(start.width, moved.width)
        assertEquals(g.areaLeft, moved.left)
        assertEquals(g.areaTop, moved.top)
        val down = g.moved(start, dx = 5000f, dy = 5000f)
        assertEquals(g.areaRight, down.right)
        assertEquals(g.areaBottom, down.bottom)
    }

    @Test
    fun clampShrinksAWindowThatNoLongerFits() {
        val g = geometry(landscape)
        // A tall portrait window carried over a rotation.
        val tall = Box(200, 200, 200 + 900, 200 + 1600)
        val fitted = g.clamp(tall)
        assertInsideArea(g, fitted)
        val ratioBefore = tall.height.toFloat() / tall.width
        val ratioAfter = fitted.height.toFloat() / fitted.width
        assertEquals(ratioBefore, ratioAfter, 0.02f)
    }

    @Test
    fun landscapeInitialBoundsFitTheHeight() {
        val g = geometry(landscape)
        val box = g.initialBounds(scale = 0.9f)
        assertInsideArea(g, box)
        assertAspect(box)
    }

    @Test
    fun resizeFromBottomRightKeepsTopLeftAndAspect() {
        val g = geometry()
        val start = g.initialBounds(scale = 0.5f, centerXFraction = 0.4f)
        val bigger = g.resized(start, FreeformGeometry.Corner.BOTTOM_RIGHT, dx = 100f, dy = 200f)
        assertEquals(start.left, bigger.left)
        assertEquals(start.top, bigger.top)
        assertTrue(bigger.width > start.width)
        assertAspect(bigger)
        assertInsideArea(g, bigger)
    }

    @Test
    fun resizeFromBottomLeftKeepsTopRight() {
        val g = geometry()
        val start = g.initialBounds(scale = 0.6f)
        val smaller = g.resized(start, FreeformGeometry.Corner.BOTTOM_LEFT, dx = 150f, dy = -150f)
        assertEquals(start.right, smaller.right)
        assertEquals(start.top, smaller.top)
        assertTrue(smaller.width < start.width)
        assertAspect(smaller)
    }

    @Test
    fun resizeIsLimitedByScreenAndMinimumSize() {
        val g = geometry()
        val start = g.initialBounds(scale = 0.6f)
        val huge = g.resized(start, FreeformGeometry.Corner.BOTTOM_RIGHT, dx = 9000f, dy = 9000f)
        assertInsideArea(g, huge)
        val tiny = g.resized(start, FreeformGeometry.Corner.BOTTOM_RIGHT, dx = -9000f, dy = -9000f)
        assertEquals(g.minWidth(), tiny.width)
    }

    @Test
    fun degenerateScreenDoesNotCrash() {
        val g = FreeformGeometry(ScreenSpec(10, 10, 160), caption, reserve, margin)
        val box = g.initialBounds()
        assertTrue(box.width >= 1)
        g.resized(box, FreeformGeometry.Corner.BOTTOM_LEFT, 5f, 5f)
        g.clamp(Box(-100, -100, 400, 400))
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("expected $expected±$tolerance but was $actual", abs(expected - actual) <= tolerance)
    }
}
