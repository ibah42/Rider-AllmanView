package com.allmanview.geometry

import kotlin.math.max
import kotlin.math.min

/**
 * Where the view should end up after a jump from a block's end-of-block label to the block's
 * own declaration.
 *
 * Nothing here knows about an `Editor`. That is the point: "why did clicking that label scroll
 * *there*" is a question worth answering with a unit test rather than by clicking the label and
 * watching the screen, which is the only way it could be answered while the arithmetic lived
 * inside the controller.
 *
 * Everything is measured in the content component's pixels -- the ones `Editor.offsetToXY`,
 * `ScrollingModel.getVisibleArea` and `Editor.getLineHeight` all speak. The two settings are in
 * lines, but a line here is only ever a distance from the top of the *viewport*, multiplied out
 * by the line height; it is never a count of document lines. In this plugin a phantom line is a
 * block inlay, so two document lines twenty lines apart are not twenty line heights apart on
 * screen.
 */
object LabelNavigationGeometry {

    /** The largest value either setting accepts. More than this is a jump to mid-screen. */
    const val MAX_TOP_LINES = 20

    /**
     * The scroll offset to move to, or null to leave the scroll exactly where it is.
     *
     * Three cases, in the order they are tested:
     *
     * 1. The declaration is not fully inside the viewport -- in practice it is above it, since a
     *    declaration always precedes its own closing brace and the label on that brace was just
     *    clicked. It lands [landingLines] lines below the top edge.
     * 2. It is inside, but fewer than [minimumTopLines] lines below the top edge. The view moves
     *    just far enough to put it on that line and no further.
     * 3. It is inside with at least that much room above it. Nothing moves; only the caret does.
     *
     * Both results are clamped to the scroll range, so near the start of a file the declaration
     * simply ends up closer to the top than asked -- there is nothing above it to scroll to.
     *
     * @param targetY top of the line the declaration sits on
     * @param viewportTop y of the first visible pixel, i.e. the current scroll offset
     * @param viewportHeight height of the visible area
     * @param lineHeight height of one line
     * @param maximumScroll the largest offset this editor can be scrolled to
     * @param landingLines lines between the top edge and a declaration that had to be fetched
     *   from outside the viewport
     * @param minimumTopLines the least room above a declaration that is already visible
     */
    fun scrollTargetY(
        targetY: Int,
        viewportTop: Int,
        viewportHeight: Int,
        lineHeight: Int,
        maximumScroll: Int,
        landingLines: Int,
        minimumTopLines: Int,
    ): Int? {
        // A viewport with no height at all is a window being dragged open or an editor that
        // has not been laid out yet. There is no "where to scroll" to answer.
        if (lineHeight <= 0 || viewportHeight <= 0) {
            return null
        }

        val landing = effectiveTopLines(landingLines, viewportHeight, lineHeight)
        // Clamped to the landing line as well as to the viewport: the settings page enforces
        // it, but a hand-edited allman-view.xml does not, and a minimum above the landing line
        // would make a declaration fetched from off screen land inside the "too close" zone.
        val minimum = min(effectiveTopLines(minimumTopLines, viewportHeight, lineHeight), landing)

        val fromTop = targetY - viewportTop
        val targetFromTop: Int
        if (!isInsideViewport(fromTop, viewportHeight, lineHeight)) {
            targetFromTop = landing * lineHeight
        } else if (fromTop < minimum * lineHeight) {
            targetFromTop = minimum * lineHeight
        } else {
            return null
        }

        return (targetY - targetFromTop).coerceIn(0, max(0, maximumScroll))
    }

    /**
     * Keeps a setting within [0, MAX_TOP_LINES] and within the viewport.
     *
     * The viewport cap is what keeps a short window usable: asked for ten lines of room in a
     * window five lines tall, the declaration would be put below the bottom edge -- off screen,
     * after a jump whose whole point was to show it.
     */
    private fun effectiveTopLines(requested: Int, viewportHeight: Int, lineHeight: Int): Int {
        val lowestLine = max(0, viewportHeight / lineHeight - 1)
        return min(requested.coerceIn(0, MAX_TOP_LINES), lowestLine)
    }

    private fun isInsideViewport(fromTop: Int, viewportHeight: Int, lineHeight: Int): Boolean {
        if (fromTop < 0) {
            return false
        }
        return fromTop + lineHeight <= viewportHeight
    }
}
