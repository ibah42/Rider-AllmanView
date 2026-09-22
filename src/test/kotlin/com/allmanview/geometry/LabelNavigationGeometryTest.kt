package com.allmanview.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a click on an end-of-block label leaves the view.
 *
 * Every case is written in the same viewport so the numbers can be compared by eye: the editor
 * is scrolled to 1000, shows 200 pixels, and a line is 20 pixels tall -- ten lines on screen,
 * whose tops are 1000, 1020, ... 1180. Line N of the viewport is N lines below the top edge,
 * so line 0 is the top line itself.
 */
class LabelNavigationGeometryTest {

    private val viewportTop = 1000
    private val viewportHeight = 200
    private val lineHeight = 20
    private val maximumScroll = 100_000

    private fun scrollFor(
        targetY: Int,
        landingLines: Int = 3,
        minimumTopLines: Int = 3,
        maximum: Int = maximumScroll,
        height: Int = viewportHeight,
    ): Int? {
        return LabelNavigationGeometry.scrollTargetY(
            targetY = targetY,
            viewportTop = viewportTop,
            viewportHeight = height,
            lineHeight = lineHeight,
            maximumScroll = maximum,
            landingLines = landingLines,
            minimumTopLines = minimumTopLines,
        )
    }

    @Test
    fun `a declaration above the viewport lands on the landing line`() {
        // Three lines of room above it: 600 - 3 * 20.
        assertEquals(540, scrollFor(targetY = 600))
    }

    @Test
    fun `the landing line follows its setting`() {
        assertEquals(520, scrollFor(targetY = 600, landingLines = 4, minimumTopLines = 3))
        assertEquals(600, scrollFor(targetY = 600, landingLines = 0, minimumTopLines = 0))
    }

    @Test
    fun `a declaration half hanging off the top counts as above the viewport`() {
        // Top at 990: its upper half is cut off, so it is fetched like any other.
        assertEquals(930, scrollFor(targetY = 990))
    }

    @Test
    fun `a visible declaration too close to the top is pulled down to the minimum`() {
        // On line 1 with a minimum of 3: the view moves up two lines, by 40 pixels.
        assertEquals(960, scrollFor(targetY = 1020))
        // On line 0: three lines.
        assertEquals(940, scrollFor(targetY = 1000))
    }

    @Test
    fun `a visible declaration with enough room above it does not scroll`() {
        assertNull(scrollFor(targetY = 1060))
        assertNull(scrollFor(targetY = 1180))
    }

    @Test
    fun `a minimum of zero never moves a visible declaration`() {
        assertNull(scrollFor(targetY = 1000, landingLines = 3, minimumTopLines = 0))
    }

    @Test
    fun `a minimum above the landing line is capped to it`() {
        // Not reachable from the settings page, only from a hand-edited file. Uncapped, line 4
        // would be pulled to line 6 -- further than a declaration fetched from off screen goes.
        assertNull(scrollFor(targetY = 1080, landingLines = 3, minimumTopLines = 6))
        assertEquals(980, scrollFor(targetY = 1040, landingLines = 3, minimumTopLines = 6))
    }

    @Test
    fun `settings outside the valid range are clamped`() {
        assertEquals(600, scrollFor(targetY = 600, landingLines = -5, minimumTopLines = -5))
        // 50 is clamped to 20, then to the viewport's lowest line, 9.
        assertEquals(420, scrollFor(targetY = 600, landingLines = 50, minimumTopLines = 50))
    }

    @Test
    fun `a viewport shorter than the landing line keeps the declaration on screen`() {
        // Three lines tall: the lowest line is 2, so a landing line of 10 becomes 2.
        assertEquals(560, scrollFor(targetY = 600, landingLines = 10, minimumTopLines = 3, height = 60))
    }

    @Test
    fun `near the start of the file the declaration ends up closer to the top`() {
        // Nothing above offset 0 to scroll to.
        assertEquals(0, scrollFor(targetY = 20))
    }

    @Test
    fun `near the end of the file the scroll stops at its maximum`() {
        assertEquals(500, scrollFor(targetY = 600, maximum = 500))
    }

    @Test
    fun `an editor that is not laid out yet does not scroll`() {
        assertNull(scrollFor(targetY = 600, height = 0))
    }
}
