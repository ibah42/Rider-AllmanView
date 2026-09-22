package com.allmanview

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D

/**
 * A red wavy line under a member's closing-brace line, running from the left edge of the line
 * all the way to the right edge of the editor's current viewport -- not just under the line's
 * own text.
 *
 * An ordinary [com.intellij.openapi.editor.markup.TextAttributes] effect, `WAVE_UNDERSCORE`
 * included, can only ever cover real document offsets, so it stops the moment the text does.
 * Reaching past the last character needs its own paint, the same reason [BraceShadowRenderer]
 * exists. The highlighter this is attached to still spans the line's real text -- that is what
 * keeps it anchored and invalidated correctly as the line is edited -- but the wave itself is
 * drawn independently of where that range ends, out to [Editor.getScrollingModel]'s visible
 * area, so it reaches the window's edge and re-fits itself as the editor is resized or scrolled.
 */
class MemberSpacingUnderlineRenderer(private val color: Color) : CustomHighlighterRenderer {

    override fun paint(editor: Editor, highlighter: RangeHighlighter, graphics: Graphics) {
        if (!highlighter.isValid) {
            return
        }

        val lineStartPoint = editor.offsetToXY(highlighter.startOffset)
        val visibleArea = editor.scrollingModel.visibleArea
        val rightEdge = visibleArea.x + visibleArea.width
        if (rightEdge <= lineStartPoint.x) {
            return
        }

        if (graphics is Graphics2D) {
            UISettings.setupAntialiasing(graphics)
        }
        graphics.color = color

        val waveY = lineStartPoint.y + editor.lineHeight - BASELINE_MARGIN
        drawWave(graphics, lineStartPoint.x, rightEdge, waveY)
    }

    /** A zigzag of alternating up/down segments, [WAVE_HALF_WIDTH] wide and [WAVE_AMPLITUDE] tall. */
    private fun drawWave(graphics: Graphics, startX: Int, endX: Int, centerY: Int) {
        var x = startX
        var risingEdge = true
        val topY = centerY - WAVE_AMPLITUDE
        val bottomY = centerY + WAVE_AMPLITUDE

        while (x < endX) {
            val nextX = (x + WAVE_HALF_WIDTH).coerceAtMost(endX)
            val fromY = if (risingEdge) bottomY else topY
            val toY = if (risingEdge) topY else bottomY
            graphics.drawLine(x, fromY, nextX, toY)
            x = nextX
            risingEdge = !risingEdge
        }
    }

    companion object {
        /** Horizontal length of one up or down leg of the zigzag. */
        private const val WAVE_HALF_WIDTH = 4

        /** How far the zigzag swings above and below its centre line. */
        private const val WAVE_AMPLITUDE = 2

        /** How far above the bottom of the line cell the wave's centre sits. */
        private const val BASELINE_MARGIN = 3
    }
}
