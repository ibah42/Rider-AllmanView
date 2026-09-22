package com.allmanview.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a click on an end-of-block label leaves the view.
 *
 * Every case is written in the same viewport so the numbers can be compared by eye: the editor
 * is scrolled to 1000, shows 200 pixels, and a line is 20 pixels tall -- ten lines on screen,
 * whose tops are 1000, 1020, ... 1180.
 */
class LabelNavigationGeometryTest {

    private val viewportTop = 1000
    private val viewportHeight = 200
    private val lineHeight = 20
    private val maximumScroll = 100_000
    private val marginLines = 2

    private fun scrollFor(clickedY: Int, targetY: Int, maximum: Int = maximumScroll): Int? {
        return LabelNavigationGeometry.scrollTargetY(
            clickedY = clickedY,
            targetY = targetY,
            viewportTop = viewportTop,
            viewportHeight = viewportHeight,
            lineHeight = lineHeight,
            maximumScroll = maximum,
            marginLines = marginLines,
        )
    }

    private fun visible(targetY: Int): Boolean {
        return LabelNavigationGeometry.isAlreadyVisible(
            targetY = targetY,
            viewportTop = viewportTop,
            viewportHeight = viewportHeight,
            lineHeight = lineHeight,
            marginLines = marginLines,
        )
    }

    @Test
    fun `a label on the bottom line puts the declaration on the top line`() {
        // Clicked at 1180, the last line that fits: 0 from the bottom, so 0 from the top.
        assertEquals(600, scrollFor(clickedY = 1180, targetY = 600))
    }

    @Test
    fun `a label on the top line puts the declaration on the bottom line`() {
        // 180 pixels from the bottom, so 180 from the top: 600 - 180.
        assertEquals(420, scrollFor(clickedY = 1000, targetY = 600))
    }

    @Test
    fun `the middle of the viewport mirrors onto itself`() {
        // The clicked label stood 90 pixels down, and so does the declaration it leads to.
        assertEquals(510, scrollFor(clickedY = 1090, targetY = 600))
    }

    @Test
    fun `a declaration already on screen with room to spare does not scroll`() {
        assertNull(scrollFor(clickedY = 1180, targetY = 1100))
    }

    @Test
    fun `a declaration clinging to the top edge is still brought inwards`() {
        // 10 pixels down is inside the viewport but inside the margin too.
        assertFalse(visible(1010))
        // Clicked on the bottom line, so the mirror puts the declaration on the top one.
        assertEquals(1010, scrollFor(clickedY = 1180, targetY = 1010))
    }

    @Test
    fun `a declaration clinging to the bottom edge is still brought inwards`() {
        assertFalse(visible(1175))
        assertEquals(1175, scrollFor(clickedY = 1180, targetY = 1175))
    }

    @Test
    fun `the margin is measured from both edges`() {
        // The first line whose top is at least two lines down, and still two lines clear of
        // the bottom: tops 1040 through 1140 inclusive.
        assertFalse(visible(1039))
        assertTrue(visible(1040))
        assertTrue(visible(1140))
        assertFalse(visible(1141))
    }

    @Test
    fun `the top of the file clamps the scroll to zero instead of going negative`() {
        // The mirror asks for 10 - 180, which is not a scroll offset that exists.
        assertEquals(0, scrollFor(clickedY = 1000, targetY = 10))
    }

    @Test
    fun `the end of the file clamps the scroll to the maximum`() {
        assertEquals(700, scrollFor(clickedY = 1180, targetY = 5000, maximum = 700))
    }

    @Test
    fun `a label hanging off the bottom of the viewport is clamped, not mirrored past it`() {
        // 1300 is below the visible area entirely -- a label on a line being scrolled out of
        // view. It counts as the bottom line, not as a negative distance from it.
        assertEquals(scrollFor(clickedY = 1180, targetY = 600), scrollFor(clickedY = 1300, targetY = 600))
    }

    @Test
    fun `a label hanging off the top of the viewport is clamped too`() {
        assertEquals(scrollFor(clickedY = 1000, targetY = 600), scrollFor(clickedY = 700, targetY = 600))
    }

    @Test
    fun `a viewport too short for the margin shrinks the margin instead of always scrolling`() {
        // One line tall: nothing could ever be two lines clear of both edges, and without the
        // shrink every click in such a window would scroll.
        val onlyLineVisible = LabelNavigationGeometry.isAlreadyVisible(
            targetY = 1000,
            viewportTop = 1000,
            viewportHeight = 20,
            lineHeight = 20,
            marginLines = 2,
        )
        assertTrue(onlyLineVisible)
    }

    @Test
    fun `a viewport with no height yet answers nothing rather than a number`() {
        val duringLayout = LabelNavigationGeometry.scrollTargetY(
            clickedY = 1180,
            targetY = 600,
            viewportTop = 1000,
            viewportHeight = 0,
            lineHeight = 20,
            maximumScroll = maximumScroll,
            marginLines = marginLines,
        )
        assertNull(duringLayout)
    }

    @Test
    fun `no line height yet answers nothing rather than dividing the screen by zero`() {
        val beforeFontsLoaded = LabelNavigationGeometry.scrollTargetY(
            clickedY = 1180,
            targetY = 600,
            viewportTop = 1000,
            viewportHeight = 200,
            lineHeight = 0,
            maximumScroll = maximumScroll,
            marginLines = marginLines,
        )
        assertNull(beforeFontsLoaded)
    }

    @Test
    fun `with no margin asked for, any fully visible line counts as visible`() {
        val atTheVeryTop = LabelNavigationGeometry.isAlreadyVisible(
            targetY = 1000,
            viewportTop = viewportTop,
            viewportHeight = viewportHeight,
            lineHeight = lineHeight,
            marginLines = 0,
        )
        assertTrue(atTheVeryTop)
    }
}
