package com.allmanview.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what a closing brace says, tested without an editor.
 *
 * These used to be unreachable from a test -- they lived inside BraceAccentStyle, which needs a
 * live `Editor` -- so every question about them ("why is there no span on this class?") could
 * only be answered by opening a file and looking. That is what these cover.
 */
class LabelPolicyTest {

    private fun closing(
        kind: BlockKind = BlockKind.TYPE,
        keyword: String = "class",
        spannedLines: Int = 0,
        isNested: Boolean = false,
        isLambda: Boolean = false,
        isAccessor: Boolean = false,
        siblingOrdinal: Int = 0,
    ): BraceAccent {
        return BraceAccent(
            offset = 0,
            kind = kind,
            nameOffset = 0,
            nameLength = 3,
            keyword = keyword,
            isOpening = false,
            spannedLines = spannedLines,
            isNested = isNested,
            isLambda = isLambda,
            isAccessor = isAccessor,
            siblingOrdinal = siblingOrdinal,
        )
    }

    /** The plugin's own defaults, so a test says what a user with untouched settings sees. */
    private val typeSpan = BlockSpanConfig(minLines = 100, onlyWithSeveral = true)
    private val functionSpan = BlockSpanConfig(minLines = 60, onlyWithSeveral = false)
    private val propertySpan = BlockSpanConfig(minLines = 40, onlyWithSeveral = false)
    private val namespaceSpan = BlockSpanConfig(minLines = 150, onlyWithSeveral = true)

    // ------------------------------------------------------------------ the reported case

    @Test
    fun `a 112-line nested class in a file of fifteen types reports its span`() {
        // UniTask.WhenAny.Generated.cs: WhenAnyPromise is declared on line 17 and closes on
        // line 129, and the file holds fifteen types. Every number here comes from running the
        // scanner over that file.
        val accent = closing(spannedLines = 112, isNested = true, siblingOrdinal = 1)
        val counts = BlockCounts(types = 15, namespaces = 1)

        assertTrue(LabelPolicy.showsBlockSpan(accent, typeSpan, counts))
        assertEquals("↑: 17  Δ: 112", LabelPolicy.blockSpanText(closingLine = 129, spannedLines = 112))
    }

    @Test
    fun `the span and the delta add up to the line the reader is looking at`() {
        assertEquals(129, LabelPolicy.declarationLine(closingLine = 129, spannedLines = 112) + 112)
    }

    // --------------------------------------------------------------------------- the span

    @Test
    fun `a block shorter than its group's length reports no span`() {
        val counts = BlockCounts(types = 15, namespaces = 1)
        assertFalse(LabelPolicy.showsBlockSpan(closing(spannedLines = 99), typeSpan, counts))
        assertTrue(LabelPolicy.showsBlockSpan(closing(spannedLines = 100), typeSpan, counts))
    }

    @Test
    fun `a switched-off group reports no span however long the block`() {
        val counts = BlockCounts(types = 15, namespaces = 1)
        assertFalse(LabelPolicy.showsBlockSpan(closing(spannedLines = 5000), null, counts))
    }

    @Test
    fun `the only type in its file reports no span, three types do`() {
        val accent = closing(spannedLines = 400)
        assertFalse(LabelPolicy.showsBlockSpan(accent, typeSpan, BlockCounts(types = 1, namespaces = 1)))
        assertTrue(LabelPolicy.showsBlockSpan(accent, typeSpan, BlockCounts(types = 3, namespaces = 1)))
    }

    @Test
    fun `the restriction can be switched off, and then one type is enough`() {
        val accent = closing(spannedLines = 400)
        val unrestricted = BlockSpanConfig(minLines = 100, onlyWithSeveral = false)
        assertTrue(LabelPolicy.showsBlockSpan(accent, unrestricted, BlockCounts(types = 1, namespaces = 1)))
    }

    @Test
    fun `a lone namespace reports no span, two namespaces do`() {
        val accent = closing(kind = BlockKind.NAMESPACE, keyword = "ns", spannedLines = 900)
        assertFalse(LabelPolicy.showsBlockSpan(accent, namespaceSpan, BlockCounts(types = 9, namespaces = 1)))
        assertTrue(LabelPolicy.showsBlockSpan(accent, namespaceSpan, BlockCounts(types = 9, namespaces = 2)))
    }

    @Test
    fun `a function never asks whether it has competition`() {
        val accent = closing(kind = BlockKind.FUNCTION, keyword = "fun", spannedLines = 80)
        assertTrue(LabelPolicy.showsBlockSpan(accent, functionSpan, BlockCounts(types = 0, namespaces = 0)))
    }

    @Test
    fun `the opening brace never reports a span`() {
        val opening = closing(spannedLines = 400).copy(isOpening = true)
        assertFalse(LabelPolicy.showsBlockSpan(opening, typeSpan, BlockCounts(types = 9, namespaces = 1)))
    }

    // -------------------------------------------------------------------------- the group

    @Test
    fun `a property and its accessors answer to the property group, a method to the function one`() {
        assertEquals(
            BlockSpanGroup.PROPERTY,
            LabelPolicy.blockSpanGroupOf(closing(kind = BlockKind.FUNCTION, keyword = PROPERTY_KEYWORD)),
        )
        assertEquals(
            BlockSpanGroup.PROPERTY,
            LabelPolicy.blockSpanGroupOf(closing(kind = BlockKind.FUNCTION, keyword = "get", isAccessor = true)),
        )
        assertEquals(
            BlockSpanGroup.FUNCTION,
            LabelPolicy.blockSpanGroupOf(closing(kind = BlockKind.FUNCTION, keyword = "fun")),
        )
        assertEquals(
            BlockSpanGroup.FUNCTION,
            LabelPolicy.blockSpanGroupOf(closing(kind = BlockKind.FUNCTION, keyword = "ctor")),
        )
        assertEquals(
            BlockSpanGroup.TYPE,
            LabelPolicy.blockSpanGroupOf(closing(kind = BlockKind.TYPE, keyword = "class")),
        )
        assertEquals(
            BlockSpanGroup.NAMESPACE,
            LabelPolicy.blockSpanGroupOf(closing(kind = BlockKind.NAMESPACE, keyword = "ns")),
        )
    }

    @Test
    fun `a forty-line property reports its span where a forty-line method does not`() {
        val counts = BlockCounts(types = 1, namespaces = 1)
        val property = closing(kind = BlockKind.FUNCTION, keyword = PROPERTY_KEYWORD, spannedLines = 40)
        val method = closing(kind = BlockKind.FUNCTION, keyword = "fun", spannedLines = 40)
        assertTrue(LabelPolicy.showsBlockSpan(property, propertySpan, counts))
        assertFalse(LabelPolicy.showsBlockSpan(method, functionSpan, counts))
    }

    // ------------------------------------------------------------------------- the label

    @Test
    fun `a long enough block is named`() {
        assertTrue(needsLabel(closing(spannedLines = 50)))
        assertFalse(needsLabel(closing(spannedLines = 49)))
    }

    @Test
    fun `a nested block is named once it reaches its own minimum`() {
        assertTrue(needsLabel(closing(spannedLines = 15, isNested = true)))
        assertFalse(needsLabel(closing(spannedLines = 14, isNested = true)))
        assertFalse(needsLabel(closing(spannedLines = 15, isNested = true), nestedLabelAlways = false))
    }

    @Test
    fun `a numbered block is named once the number is repeated`() {
        assertTrue(needsLabel(closing(spannedLines = 15, siblingOrdinal = 3)))
        assertFalse(needsLabel(closing(spannedLines = 14, siblingOrdinal = 3)))
    }

    @Test
    fun `the span alone is not a reason to name the block`() {
        // 1.9.0 made it one, which put the name on a block that had no other reason for it;
        // 1.10.0 took it back out. The span says something complete on its own.
        val accent = closing(spannedLines = 120)
        assertFalse(needsLabel(accent, labelMinLines = 500, nestedLabelAlways = false))
        assertTrue(LabelPolicy.showsBlockSpan(accent, typeSpan, BlockCounts(types = 4, namespaces = 1)))
    }

    @Test
    fun `the per-kind label switch sits above every reason`() {
        assertFalse(needsLabel(closing(spannedLines = 900, isNested = true, siblingOrdinal = 2), showLabel = false))
    }

    @Test
    fun `a block with no keyword is never named`() {
        assertFalse(needsLabel(closing(keyword = "", spannedLines = 900)))
    }

    @Test
    fun `the opening brace is never named`() {
        assertFalse(needsLabel(closing(spannedLines = 900).copy(isOpening = true)))
    }

    // ------------------------------------------------------------------------- nesting

    @Test
    fun `a lambda and an accessor are never marked as nested`() {
        assertFalse(LabelPolicy.isNestedBlock(closing(isNested = true, isLambda = true)))
        assertFalse(LabelPolicy.isNestedBlock(closing(isNested = true, isAccessor = true)))
        assertTrue(LabelPolicy.isNestedBlock(closing(isNested = true)))
    }

    @Test
    fun `a lambda or accessor is excluded on the closing brace too, however long it runs`() {
        // The exclusion is in isNestedBlock, which both marksAsNested and namesNestedBlock call
        // before they ever look at spannedLines -- a long lambda or accessor must not slip past
        // the exclusion just because it also clears the end-of-block minimum.
        val lambda = closing(spannedLines = 900, isNested = true, isLambda = true)
        val accessor = closing(spannedLines = 900, isNested = true, isAccessor = true)

        assertFalse(LabelPolicy.marksAsNested(lambda, nestedMarkerEnabled = true, endOfBlockMinLines = 15))
        assertFalse(LabelPolicy.marksAsNested(accessor, nestedMarkerEnabled = true, endOfBlockMinLines = 15))
        assertFalse(LabelPolicy.namesNestedBlock(lambda, nestedLabelAlways = true, minLines = 15))
        assertFalse(LabelPolicy.namesNestedBlock(accessor, nestedLabelAlways = true, minLines = 15))

        // labelMinLines set past spannedLines, so the only way needsLabel could still return
        // true is through the nested path -- the ordinary per-kind length is deliberately not
        // a second way for the assertion to pass by accident.
        assertFalse(needsLabel(lambda, labelMinLines = 5000, nestedLabelMinLines = 15))
        assertFalse(needsLabel(accessor, labelMinLines = 5000, nestedLabelMinLines = 15))
    }

    // ------------------------------------------------------------------------ the ordinal

    @Test
    fun `the ordinal is always drawn on the declaration line and has to earn the closing brace`() {
        val opening = closing(spannedLines = 0, siblingOrdinal = 2).copy(isOpening = true)
        assertEquals("[2]", LabelPolicy.siblingOrdinalText(opening, true, 15))

        assertEquals("", LabelPolicy.siblingOrdinalText(closing(spannedLines = 14, siblingOrdinal = 2), true, 15))
        assertEquals("[2]", LabelPolicy.siblingOrdinalText(closing(spannedLines = 15, siblingOrdinal = 2), true, 15))
    }

    @Test
    fun `an unnumbered block and a switched-off numbering print nothing`() {
        assertEquals("", LabelPolicy.siblingOrdinalText(closing(spannedLines = 900), true, 15))
        assertEquals("", LabelPolicy.siblingOrdinalText(closing(spannedLines = 900, siblingOrdinal = 2), false, 15))
    }

    // ---------------------------------------------------------------- the nest marker

    @Test
    fun `the nest word is always drawn on the declaration line and has to earn the closing brace`() {
        val opening = closing(spannedLines = 0, isNested = true).copy(isOpening = true)
        assertTrue(LabelPolicy.marksAsNested(opening, nestedMarkerEnabled = true, endOfBlockMinLines = 15))

        assertFalse(LabelPolicy.marksAsNested(closing(spannedLines = 14, isNested = true), true, 15))
        assertTrue(LabelPolicy.marksAsNested(closing(spannedLines = 15, isNested = true), true, 15))
    }

    @Test
    fun `an unnested block and a switched-off marker never mark as nested`() {
        // Both switches are checked before isOpening, so they hold on the declaration line
        // exactly as they do on the closing brace -- not just for a long block down here, but
        // for a freshly opened one with nothing yet to gate on.
        val openNested = closing(isNested = true).copy(isOpening = true)

        assertFalse(LabelPolicy.marksAsNested(closing(spannedLines = 900), true, 15))
        assertFalse(LabelPolicy.marksAsNested(closing(spannedLines = 900, isNested = true), false, 15))
        assertFalse(LabelPolicy.marksAsNested(openNested.copy(isNested = false), true, 15))
        assertFalse(LabelPolicy.marksAsNested(openNested, false, 15))
    }

    private fun needsLabel(
        accent: BraceAccent,
        showLabel: Boolean = true,
        labelMinLines: Int = 50,
        nestedLabelAlways: Boolean = true,
        nestedLabelMinLines: Int = 15,
        numberingEnabled: Boolean = true,
        numberingMinLines: Int = 15,
    ): Boolean {
        return LabelPolicy.needsLabel(
            accent,
            showLabel,
            labelMinLines,
            nestedLabelAlways,
            nestedLabelMinLines,
            numberingEnabled,
            numberingMinLines,
        )
    }
}
