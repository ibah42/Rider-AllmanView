package com.allmanview.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The scanner run over whole real source files, not over hand-written snippets.
 *
 * Two kinds of check, and the difference matters:
 *
 *  - **Invariants** ([everySampleHoldsItsInvariants] and friends) run over every file in
 *    `src/test/resources/samples` with no expected output of any kind. Drop a new file in that
 *    folder and it is checked from the next run onwards, without a line of new code. These are
 *    the properties that must hold for any input at all -- balanced braces, in-bounds names,
 *    ascending offsets, a deterministic result -- so they cannot be "fixed" by regenerating
 *    anything.
 *  - **The golden file** ([everySampleMatchesItsGoldenFile]) is one line per block, compared
 *    against a checked-in `.expected.txt`. It catches what nobody thought to assert: the bug
 *    that started this file was a class quietly disappearing from a generated UniTask source,
 *    which no snippet test was ever going to notice.
 *
 * The golden is one line per block rather than per brace, and it carries names: a failure is
 * meant to be read and judged, not regenerated on sight. **Regenerating it is a change to the
 * plugin's behaviour** -- if a diff appears that the changelog entry does not explain, the diff
 * is the bug.
 */
class SampleFilesTest {

    // ------------------------------------------------------------------ invariants

    @Test
    fun `there is at least one sample to run over`() {
        // Guards the guard: a sample folder that resolves to nothing would make every test
        // below pass by having nothing to check.
        assertTrue("no sample files found in $SAMPLES_RESOURCE", sampleFiles().isNotEmpty())
    }

    @Test
    fun `every sample pairs each opening brace with a closing one of the same block`() {
        forEachSample { name, _, result ->
            val open = ArrayDeque<BraceAccent>()
            var closed = 0
            for (accent in result.accents) {
                if (accent.isOpening) {
                    open.addLast(accent)
                    continue
                }
                assertTrue("$name: closing brace with nothing open", open.isNotEmpty())
                val opening = open.removeLast()
                assertEquals("$name: kind", opening.kind, accent.kind)
                assertEquals("$name: keyword", opening.keyword, accent.keyword)
                assertEquals("$name: name offset", opening.nameOffset, accent.nameOffset)
                assertEquals("$name: name length", opening.nameLength, accent.nameLength)
                assertEquals("$name: ordinal", opening.siblingOrdinal, accent.siblingOrdinal)
                assertEquals("$name: nested", opening.isNested, accent.isNested)
                closed++
            }
            assertTrue("$name: ${open.size} blocks never closed", open.isEmpty())
            assertEquals("$name: every block reported twice", result.accents.size, closed * 2)
        }
    }

    @Test
    fun `every sample reports accents in ascending document order`() {
        forEachSample { name, _, result ->
            var previous = -1
            for (accent in result.accents) {
                assertTrue("$name: offsets out of order at ${accent.offset}", accent.offset > previous)
                previous = accent.offset
            }
        }
    }

    @Test
    fun `every sample keeps its offsets and names inside the document`() {
        forEachSample { name, source, result ->
            for (accent in result.accents) {
                assertTrue("$name: brace offset ${accent.offset}", accent.offset in source.indices)
                assertTrue(
                    "$name: brace at ${accent.offset} is '${source[accent.offset]}'",
                    source[accent.offset] == '{' || source[accent.offset] == '}',
                )
                if (accent.nameLength > 0) {
                    val end = accent.nameOffset + accent.nameLength
                    assertTrue("$name: name offset ${accent.nameOffset}", accent.nameOffset >= 0)
                    assertTrue("$name: name end $end past ${source.length}", end <= source.length)
                    assertTrue(
                        "$name: blank name at ${accent.nameOffset}",
                        source.substring(accent.nameOffset, end).isNotBlank(),
                    )
                }
                if (accent.headerOffset >= 0) {
                    assertTrue(
                        "$name: header offset ${accent.headerOffset} past the brace",
                        accent.headerOffset <= accent.offset,
                    )
                }
            }
        }
    }

    @Test
    fun `every sample measures a span of zero on the way in and a real one on the way out`() {
        forEachSample { name, _, result ->
            for (accent in result.accents) {
                if (accent.isOpening) {
                    assertEquals("$name: opening span", 0, accent.spannedLines)
                } else {
                    assertTrue("$name: negative span ${accent.spannedLines}", accent.spannedLines >= 0)
                }
            }
        }
    }

    @Test
    fun `every sample's counts agree with the blocks it reported`() {
        forEachSample { name, _, result ->
            var types = 0
            var namespaces = 0
            for (accent in result.accents) {
                if (!accent.isOpening) {
                    continue
                }
                if (accent.kind == BlockKind.TYPE) {
                    types++
                }
                if (accent.kind == BlockKind.NAMESPACE) {
                    namespaces++
                }
            }
            assertEquals("$name: type count", types, result.counts.types)
            assertEquals("$name: namespace count", namespaces, result.counts.namespaces)
        }
    }

    @Test
    fun `every sample keeps its phantom sites inside the document`() {
        forEachSample { name, source, result ->
            for (site in result.sites) {
                assertTrue("$name: empty dim range", site.dimStart < site.dimEnd)
                assertTrue("$name: dim end ${site.dimEnd}", site.dimEnd <= source.length)
                assertTrue("$name: anchor ${site.anchorOffset}", site.anchorOffset <= source.length)
                assertTrue("$name: site with no phantom lines", site.phantomLines.isNotEmpty())
                for (line in site.phantomLines) {
                    val end = line.sourceOffset + line.text.length
                    assertTrue("$name: phantom source ${line.sourceOffset}", line.sourceOffset >= 0)
                    assertTrue("$name: phantom end $end past ${source.length}", end <= source.length)
                    assertEquals(
                        "$name: phantom text does not match the document at ${line.sourceOffset}",
                        source.substring(line.sourceOffset, end),
                        line.text,
                    )
                }
            }
        }
    }

    @Test
    fun `scanning a sample twice gives the same answer`() {
        // A scanner that carried state between runs, or hashed something iteration-ordered,
        // would show up here rather than as an intermittent failure somewhere else.
        forEachSample { name, source, result ->
            val again = BraceScanner(source, flavorOf(name), ScanOptions()).scan()
            assertEquals("$name: accents", result.accents, again.accents)
            assertEquals("$name: counts", result.counts, again.counts)
            assertEquals("$name: site count", result.sites.size, again.sites.size)
        }
    }

    // ------------------------------------------------------------------ the golden file

    @Test
    fun `every sample matches its golden file`() {
        for (sample in sampleFiles()) {
            val name = sample.name
            val source = sample.readText()
            val result = BraceScanner(source, flavorOf(name), ScanOptions()).scan()

            val goldenFile = File(sample.parentFile, name + GOLDEN_SUFFIX)
            assertTrue("$name: no golden file at ${goldenFile.name}", goldenFile.isFile)

            val expected = goldenFile.readText().trim().lines()
            val actual = renderBlocks(source, result).trim().lines()

            for (index in 0 until maxOf(expected.size, actual.size)) {
                // One line at a time, so a failure names the block that changed instead of
                // printing several hundred lines of context around it.
                assertEquals(
                    "$name golden line ${index + 1}: " + (expected.getOrNull(index) ?: "<end of file>"),
                    "$name golden line ${index + 1}: " + (actual.getOrNull(index) ?: "<end of file>"),
                )
            }
        }
    }

    // ------------------------------------------------------------------ the machinery

    // The plugin's own defaults (AllmanSettings.Config), copied here the same way
    // LabelPolicyTest copies them, so the golden reports what an untouched install actually
    // draws at both ends of a real block -- not only the scan facts ("nested", "ordinal=")
    // that were all it reported before. Kept in sync by hand: a settings default that changes
    // without a matching change here is exactly the drift the golden exists to catch, so a
    // mismatch found later is a reason to update this snapshot and say so in the changelog,
    // not a reason to "fix" the golden until it goes quiet.
    private val typeSpan = BlockSpanConfig(minLines = 100, onlyWithSeveral = true)
    private val functionSpan = BlockSpanConfig(minLines = 60, onlyWithSeveral = false)
    private val propertySpan = BlockSpanConfig(minLines = 40, onlyWithSeveral = false)
    private val namespaceSpan = BlockSpanConfig(minLines = 150, onlyWithSeveral = true)

    /**
     * One line per block: where it is declared, where it closes, how long it is, and what
     * LabelPolicy actually says to draw at both ends of it under the defaults above -- `start=`
     * for the declaration line, `end=` for the closing brace. Written to be read -- a diff has
     * to be judgeable by eye, which is the whole difference between a golden file and a rubber
     * stamp.
     */
    private fun renderBlocks(source: String, result: ScanResult): String {
        val lineStarts = lineStartsOf(source)
        val builder = StringBuilder()
        builder.append("counts: types=").append(result.counts.types)
            .append(" namespaces=").append(result.counts.namespaces).append('\n')
        builder.append("blocks: ").append(result.accents.size / 2).append('\n')
        builder.append('\n')

        val open = ArrayDeque<BraceAccent>()
        for (accent in result.accents) {
            if (accent.isOpening) {
                open.addLast(accent)
                continue
            }
            val opening = open.removeLast()
            val closeLine = lineOf(lineStarts, accent.offset)
            val declarationLine = closeLine - accent.spannedLines
            val openLine = lineOf(lineStarts, opening.offset)
            val (startText, endText) = markerTexts(source, result.counts, opening, accent, closeLine)

            builder.append("decl=").append(declarationLine)
                .append(" open=").append(openLine)
                .append(" close=").append(closeLine)
                .append(" span=").append(accent.spannedLines)
                .append(' ').append(accent.kind)
                .append(" '").append(accent.keyword).append('\'')
                .append(" name='").append(nameOf(source, accent)).append('\'')
            if (accent.isNested) {
                builder.append(" nested")
            }
            if (accent.isLambda) {
                builder.append(" lambda")
            }
            if (accent.isAccessor) {
                builder.append(" accessor")
            }
            if (accent.siblingOrdinal > 0) {
                builder.append(" ordinal=").append(accent.siblingOrdinal)
            }
            builder.append(" start='").append(startText).append('\'')
            builder.append(" end='").append(endText).append('\'')
            builder.append('\n')
        }
        return builder.toString()
    }

    /**
     * What LabelPolicy says to draw at the declaration line and at the closing brace, under
     * the defaults above -- the same functions and the same order AllmanController uses to
     * build the real inlays (the ordinal, then `nest`, then, end-of-block only, the keyword,
     * the name and the span). `""` means nothing is drawn on that side at all.
     */
    private fun markerTexts(
        source: String,
        counts: BlockCounts,
        opening: BraceAccent,
        closing: BraceAccent,
        closingLine: Int,
    ): Pair<String, String> {
        val start = StringBuilder()
        appendPairedMarkers(start, opening)

        val end = StringBuilder()
        appendPairedMarkers(end, closing)
        if (needsLabelByDefault(closing)) {
            val keywordText = keywordLabelText(closing)
            if (keywordText.isNotEmpty()) {
                appendPart(end, keywordText)
            }
            val nameText = nameTextFor(source, closing)
            if (nameText.isNotEmpty()) {
                appendPart(end, nameText)
            }
        }
        val group = LabelPolicy.blockSpanGroupOf(closing)
        if (LabelPolicy.showsBlockSpan(closing, blockSpanConfigFor(group), counts)) {
            appendPart(end, LabelPolicy.blockSpanText(closingLine, closing.spannedLines))
        }

        return Pair(start.toString(), end.toString())
    }

    /** The sibling ordinal, then `nest` -- the paired markers, in the order they are drawn. */
    private fun appendPairedMarkers(builder: StringBuilder, accent: BraceAccent) {
        val ordinalText = LabelPolicy.siblingOrdinalText(accent, NUMBERING_ENABLED, NUMBERING_END_OF_BLOCK_MIN_LINES)
        if (ordinalText.isNotEmpty()) {
            appendPart(builder, ordinalText)
        }
        if (LabelPolicy.marksAsNested(accent, NESTED_MARKER_ENABLED, NESTED_MARKER_END_OF_BLOCK_MIN_LINES)) {
            appendPart(builder, "nest")
        }
    }

    private fun needsLabelByDefault(accent: BraceAccent): Boolean {
        return LabelPolicy.needsLabel(
            accent,
            showLabel = showsLabelByDefault(accent.kind),
            labelMinLines = labelMinLinesFor(accent.kind),
            nestedLabelAlways = NESTED_LABEL_ALWAYS,
            nestedLabelMinLines = NESTED_MARKER_END_OF_BLOCK_MIN_LINES,
            numberingEnabled = NUMBERING_ENABLED,
            numberingMinLines = NUMBERING_END_OF_BLOCK_MIN_LINES,
        )
    }

    /** Mirrors AllmanSettings.accentFor: only these three kinds carry a label at all. */
    private fun showsLabelByDefault(kind: BlockKind): Boolean {
        return kind == BlockKind.TYPE || kind == BlockKind.FUNCTION || kind == BlockKind.NAMESPACE
    }

    private fun labelMinLinesFor(kind: BlockKind): Int {
        if (kind == BlockKind.TYPE) {
            return TYPE_LABEL_MIN_LINES
        }
        if (kind == BlockKind.NAMESPACE) {
            return NAMESPACE_LABEL_MIN_LINES
        }
        return FUNCTION_LABEL_MIN_LINES
    }

    /** Mirrors AllmanSettings.blockSpanFor: every group has a config, none of them are off. */
    private fun blockSpanConfigFor(group: BlockSpanGroup): BlockSpanConfig {
        if (group == BlockSpanGroup.TYPE) {
            return typeSpan
        }
        if (group == BlockSpanGroup.NAMESPACE) {
            return namespaceSpan
        }
        if (group == BlockSpanGroup.PROPERTY) {
            return propertySpan
        }
        return functionSpan
    }

    /** Mirrors BraceAccentStyle.keywordLabelText, without the editor it reads the colour from. */
    private fun keywordLabelText(accent: BraceAccent): String {
        if (accent.keyword.isEmpty()) {
            return ""
        }
        if (accent.isLambda) {
            if (LAMBDA_SYMBOL_ENABLED) {
                return LAMBDA_SYMBOL
            }
            return ""
        }
        return accent.keyword
    }

    /** Mirrors BraceAccentStyle.nameText, reading the name from [source] instead of a document. */
    private fun nameTextFor(source: String, accent: BraceAccent): String {
        if (accent.isLambda && !LAMBDA_NAME_ENABLED) {
            return ""
        }
        return nameOf(source, accent)
    }

    private fun appendPart(builder: StringBuilder, text: String) {
        if (builder.isNotEmpty()) {
            builder.append(' ')
        }
        builder.append(text)
    }

    private fun nameOf(source: String, accent: BraceAccent): String {
        if (accent.nameOffset < 0 || accent.nameLength <= 0) {
            return ""
        }
        return source.substring(accent.nameOffset, accent.nameOffset + accent.nameLength)
    }

    /** Offsets every line starts at, so a line number is a binary search rather than a count. */
    private fun lineStartsOf(source: String): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        for (index in source.indices) {
            if (source[index] == '\n') {
                starts.add(index + 1)
            }
        }
        return starts.toIntArray()
    }

    /** 1-based, matching the gutter. */
    private fun lineOf(lineStarts: IntArray, offset: Int): Int {
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (lineStarts[middle] <= offset) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        return low + 1
    }

    private fun flavorOf(fileName: String): Flavor {
        return Dialects.forExtension(fileName.substringAfterLast('.', ""))
    }

    private fun forEachSample(check: (String, String, ScanResult) -> Unit) {
        for (sample in sampleFiles()) {
            val source = sample.readText()
            check(sample.name, source, BraceScanner(source, flavorOf(sample.name), ScanOptions()).scan())
        }
    }

    /**
     * Every sample in the resource folder, golden files excluded.
     *
     * Read through the classloader rather than by a relative path: the working directory of a
     * test run is not promised to be the project root, and it differs between Gradle and an
     * IDE run configuration.
     */
    private fun sampleFiles(): List<File> {
        val url = javaClass.getResource(SAMPLES_RESOURCE)
        if (url == null) {
            return emptyList()
        }
        val directory = File(url.toURI())
        if (!directory.isDirectory) {
            return emptyList()
        }
        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(GOLDEN_SUFFIX) && !it.name.endsWith(".md") }
            .sortedBy { it.name }
    }

    private companion object {
        const val SAMPLES_RESOURCE = "/samples"
        const val GOLDEN_SUFFIX = ".expected.txt"

        // The plugin's own defaults (AllmanSettings.Config), copied here the same way
        // LabelPolicyTest copies them, so the golden reports what an untouched install
        // actually draws at both ends of a real block -- not only the scan facts ("nested",
        // "ordinal=") that were all it reported before. Kept in sync by hand: a settings
        // default that changes without a matching change here is exactly the drift the golden
        // exists to catch, so a mismatch found later is a reason to update this snapshot and
        // say so in the changelog, not a reason to "fix" the golden until it goes quiet.
        const val TYPE_LABEL_MIN_LINES = 50
        const val FUNCTION_LABEL_MIN_LINES = 30
        const val NAMESPACE_LABEL_MIN_LINES = 0
        const val NESTED_LABEL_ALWAYS = true
        const val NESTED_MARKER_ENABLED = true
        const val NESTED_MARKER_END_OF_BLOCK_MIN_LINES = 15
        const val NUMBERING_ENABLED = true
        const val NUMBERING_END_OF_BLOCK_MIN_LINES = 15
        const val LAMBDA_SYMBOL_ENABLED = true
        const val LAMBDA_NAME_ENABLED = true
        const val LAMBDA_SYMBOL = "λ"
    }
}
