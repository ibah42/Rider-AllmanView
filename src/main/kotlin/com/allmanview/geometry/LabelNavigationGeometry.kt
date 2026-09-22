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
 * `Inlay.getBounds` and `ScrollingModel.getVisibleArea` all speak -- and never in lines. In
 * this plugin a phantom line is a block inlay, so two document lines twenty lines apart are
 * not twenty line heights apart on screen. Counting lines would land the declaration in the
 * wrong place in exactly the files the plugin exists for.
 */
object LabelNavigationGeometry {

    /**
     * The scroll offset to move to, or null to leave the scroll exactly where it is.
     *
     * The rule is a mirror about the middle of the viewport: the declaration lands as far from
     * the top as the clicked label stood from the bottom. Click a label sitting low on screen
     * and the declaration appears high on it, so the block's body fills the view instead of
     * flying past it; click in the very middle and nothing moves at all, because the middle
     * mirrors onto itself.
     *
     * Null comes back when the declaration is already on screen with [marginLines] to spare.
     * The mirror would still have moved the view in that case -- a three-line block a third of
     * the way down the screen would be shoved to two thirds of the way down -- and jerking the
     * text under a reader who can already see both ends of the block is the one thing this is
     * meant to avoid.
     *
     * @param clickedY top of the line the clicked label sits on
     * @param targetY top of the line the declaration sits on
     * @param viewportTop y of the first visible pixel, i.e. the current scroll offset
     * @param viewportHeight height of the visible area
     * @param lineHeight height of one line
     * @param maximumScroll the largest offset this editor can be scrolled to
     * @param marginLines how much of the viewport's top and bottom does not count as
     *   "already visible", so that a declaration clinging to an edge is still brought inwards
     */
    fun scrollTargetY(
        clickedY: Int,
        targetY: Int,
        viewportTop: Int,
        viewportHeight: Int,
        lineHeight: Int,
        maximumScroll: Int,
        marginLines: Int,
    ): Int? {
        // A viewport with no height at all is a window being dragged open or an editor that
        // has not been laid out yet. There is no "where to scroll" to answer.
        if (lineHeight <= 0 || viewportHeight <= 0) {
            return null
        }
        if (isAlreadyVisible(targetY, viewportTop, viewportHeight, lineHeight, marginLines)) {
            return null
        }

        val lowestLineTop = max(0, viewportHeight - lineHeight)
        // Clamped because a label can be clicked while half of it hangs off the edge of the
        // viewport, and a negative distance would mirror into a target below the bottom.
        val clickedFromTop = (clickedY - viewportTop).coerceIn(0, lowestLineTop)
        val targetFromTop = lowestLineTop - clickedFromTop

        return (targetY - targetFromTop).coerceIn(0, max(0, maximumScroll))
    }

    /**
     * Whether the declaration already stands inside the viewport, far enough from both edges
     * that the reader can see it without hunting for it.
     */
    fun isAlreadyVisible(
        targetY: Int,
        viewportTop: Int,
        viewportHeight: Int,
        lineHeight: Int,
        marginLines: Int,
    ): Boolean {
        val margin = effectiveMargin(viewportHeight, lineHeight, marginLines)
        val fromTop = targetY - viewportTop

        if (fromTop < margin) {
            return false
        }
        return fromTop + lineHeight <= viewportHeight - margin
    }

    /**
     * The margin actually applied, which is not always the one asked for.
     *
     * A viewport too short to hold the requested margin twice over plus a line of text would
     * call nothing visible and scroll on every single click -- the smaller the window, the more
     * it would jump. Shrinking the margin to fit keeps the rule meaningful at any window size.
     */
    private fun effectiveMargin(viewportHeight: Int, lineHeight: Int, marginLines: Int): Int {
        val requested = marginLines * lineHeight
        val largestThatFits = max(0, (viewportHeight - lineHeight) / 2)
        return min(requested, largestThatFits)
    }
}
