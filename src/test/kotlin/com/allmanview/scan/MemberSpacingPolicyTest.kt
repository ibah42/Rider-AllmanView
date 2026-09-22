package com.allmanview.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "missing blank line between members" rule, tested without an editor -- the same reason
 * [LabelPolicyTest] exists for [LabelPolicy].
 *
 * Every text here is real enough to read, and every offset is found in it with [nth] rather
 * than hand-counted, so a typo in the source string cannot silently shift an assertion onto the
 * wrong character the way a hand-counted index could.
 */
class MemberSpacingPolicyTest {

    private val ALL_ON = MemberSpacingPolicy.Options(types = true, namespaces = true, functions = true, properties = true)

    // ------------------------------------------------------------------------- basic gap rule

    @Test
    fun `a member with no previous sibling is never flagged`() {
        val text = """
            class A
            {
            }
        """.trimIndent()
        val accents = listOf(
            opening(nth(text, '{', 1), kind = BlockKind.TYPE, keyword = "class"),
            closing(nth(text, '}', 1), kind = BlockKind.TYPE, keyword = "class", spannedLines = 5),
        )

        assertTrue(MemberSpacingPolicy.violations(text, accents, ALL_ON).isEmpty())
    }

    @Test
    fun `two multi-line functions packed with no blank line are flagged`() {
        val text = """
            void Foo()
            {
            }
            void Bar()
            {
            }
        """.trimIndent()
        val closeFooOffset = nth(text, '}', 1)
        val openBarOffset = nth(text, '{', 2)
        val accents = listOf(
            opening(nth(text, '{', 1)),
            closing(closeFooOffset, spannedLines = 2),
            opening(openBarOffset),
            closing(nth(text, '}', 2), spannedLines = 2),
        )

        val violations = MemberSpacingPolicy.violations(text, accents, ALL_ON)

        assertEquals(1, violations.size)
        assertEquals(closeFooOffset, violations[0].closingBraceOffset)
        assertEquals(openBarOffset, violations[0].nextDeclarationOffset)
    }

    @Test
    fun `a blank line between two multi-line functions clears the flag`() {
        val text = """
            void Foo()
            {
            }

            void Bar()
            {
            }
        """.trimIndent()
        val accents = listOf(
            opening(nth(text, '{', 1)),
            closing(nth(text, '}', 1), spannedLines = 2),
            opening(nth(text, '{', 2)),
            closing(nth(text, '}', 2), spannedLines = 2),
        )

        assertTrue(MemberSpacingPolicy.violations(text, accents, ALL_ON).isEmpty())
    }

    // -------------------------------------------------------------------- single-line members

    @Test
    fun `two single-line properties packed need no blank line`() {
        val text = "int A => 1;\nint B => 2;"
        val accents = listOf(
            opening(0, keyword = PROPERTY_KEYWORD),
            closing(text.indexOf(';'), keyword = PROPERTY_KEYWORD, spannedLines = 0),
            opening(text.indexOf("int B"), keyword = PROPERTY_KEYWORD),
            closing(text.length - 1, keyword = PROPERTY_KEYWORD, spannedLines = 0),
        )

        assertTrue(MemberSpacingPolicy.violations(text, accents, ALL_ON).isEmpty())
    }

    @Test
    fun `a single-line member directly before a multi-line one is still flagged`() {
        val text = """
            int A => 1;
            void Bar()
            {
            }
        """.trimIndent()
        val accents = listOf(
            opening(0, keyword = PROPERTY_KEYWORD),
            closing(text.indexOf(';'), keyword = PROPERTY_KEYWORD, spannedLines = 0),
            opening(nth(text, '{', 1)),
            closing(nth(text, '}', 1), spannedLines = 2),
        )

        assertEquals(1, MemberSpacingPolicy.violations(text, accents, ALL_ON).size)
    }

    // ------------------------------------------------------------------------------ exclusions

    @Test
    fun `a lambda directly before a real member is not treated as its sibling`() {
        val text = """
            var action = () =>
            {
            };
            void Bar()
            {
            }
        """.trimIndent()
        val accents = listOf(
            opening(nth(text, '{', 1), isLambda = true),
            closing(nth(text, '}', 1), spannedLines = 3, isLambda = true),
            opening(nth(text, '{', 2)),
            closing(nth(text, '}', 2), spannedLines = 3),
        )

        assertTrue(MemberSpacingPolicy.violations(text, accents, ALL_ON).isEmpty())
    }

    @Test
    fun `a property's own accessors sitting back to back are not flagged`() {
        val text = """
            int Value
            {
                get
                {
                }
                set
                {
                }
            }
        """.trimIndent()
        val accents = listOf(
            opening(nth(text, '{', 1), keyword = PROPERTY_KEYWORD),
            opening(nth(text, '{', 2), keyword = "get", isAccessor = true),
            closing(nth(text, '}', 1), keyword = "get", spannedLines = 2, isAccessor = true),
            opening(nth(text, '{', 3), keyword = "set", isAccessor = true),
            closing(nth(text, '}', 2), keyword = "set", spannedLines = 2, isAccessor = true),
            closing(nth(text, '}', 3), keyword = PROPERTY_KEYWORD, spannedLines = 6),
        )

        assertTrue(MemberSpacingPolicy.violations(text, accents, ALL_ON).isEmpty())
    }

    @Test
    fun `each kind can be switched off independently`() {
        val text = """
            void Foo()
            {
            }
            void Bar()
            {
            }
        """.trimIndent()
        val accents = listOf(
            opening(nth(text, '{', 1)),
            closing(nth(text, '}', 1), spannedLines = 2),
            opening(nth(text, '{', 2)),
            closing(nth(text, '}', 2), spannedLines = 2),
        )
        val functionsOff = ALL_ON.copy(functions = false)

        assertTrue(MemberSpacingPolicy.violations(text, accents, functionsOff).isEmpty())
        assertEquals(1, MemberSpacingPolicy.violations(text, accents, ALL_ON).size)
    }

    // --------------------------------------------------------------------- blank-line-anywhere

    @Test
    fun `a comment-only line in the gap still counts as no blank line`() {
        val text = """
            void Foo()
            {
            }
            // explains Bar
            void Bar()
            {
            }
        """.trimIndent()
        val accents = listOf(
            opening(nth(text, '{', 1)),
            closing(nth(text, '}', 1), spannedLines = 2),
            opening(nth(text, '{', 2)),
            closing(nth(text, '}', 2), spannedLines = 2),
        )

        assertEquals(1, MemberSpacingPolicy.violations(text, accents, ALL_ON).size)
    }

    @Test
    fun `a blank line anywhere in the gap clears the flag, even among comments`() {
        val text = """
            void Foo()
            {
            }
            // explains Bar

            // still explaining
            void Bar()
            {
            }
        """.trimIndent()
        val accents = listOf(
            opening(nth(text, '{', 1)),
            closing(nth(text, '}', 1), spannedLines = 2),
            opening(nth(text, '{', 2)),
            closing(nth(text, '}', 2), spannedLines = 2),
        )

        assertTrue(MemberSpacingPolicy.violations(text, accents, ALL_ON).isEmpty())
    }

    // ----------------------------------------------------------------------------- the header

    @Test
    fun `a header offset, not the brace, is where the gap ends`() {
        val text = """
            void Foo()
            {
            }
            [Obsolete]
            void Bar()
            {
            }
        """.trimIndent()
        val headerOffset = nth(text, '[', 1)
        val accents = listOf(
            opening(nth(text, '{', 1)),
            closing(nth(text, '}', 1), spannedLines = 2),
            opening(nth(text, '{', 2), headerOffset = headerOffset),
            closing(nth(text, '}', 2), spannedLines = 2),
        )

        val violations = MemberSpacingPolicy.violations(text, accents, ALL_ON)

        assertEquals(1, violations.size)
        assertEquals(headerOffset, violations[0].nextDeclarationOffset)
    }

    @Test
    fun `a blank line before an attribute line still clears the flag`() {
        val text = """
            void Foo()
            {
            }

            [Obsolete]
            void Bar()
            {
            }
        """.trimIndent()
        val headerOffset = nth(text, '[', 1)
        val accents = listOf(
            opening(nth(text, '{', 1)),
            closing(nth(text, '}', 1), spannedLines = 2),
            opening(nth(text, '{', 2), headerOffset = headerOffset),
            closing(nth(text, '}', 2), spannedLines = 2),
        )

        assertTrue(MemberSpacingPolicy.violations(text, accents, ALL_ON).isEmpty())
    }

    // ----------------------------------------------------------------------- nesting (regression)

    @Test
    fun `two sibling types each holding one function are not confused with each other's children`() {
        // Regression test: an earlier version of this policy tracked "the previous sibling that
        // closed" in a HashMap keyed by nesting depth alone. That conflated Bar (B's first and
        // only child, at depth 1) with Foo (A's last child, also at depth 1) just because A and
        // B sit at the same depth in the file -- reporting a spurious violation between two
        // methods that belong to different classes and are not really adjacent to each other at
        // all. Only the true type-level violation, between A and B themselves, should survive.
        val text = """
            class A
            {
                void Foo()
                {
                }
            }
            class B
            {
                void Bar()
                {
                }
            }
        """.trimIndent()
        val closeAOffset = nth(text, '}', 2)
        val openBOffset = nth(text, '{', 3)
        val accents = listOf(
            opening(nth(text, '{', 1), kind = BlockKind.TYPE, keyword = "class"),
            opening(nth(text, '{', 2)),
            closing(nth(text, '}', 1), spannedLines = 2),
            closing(closeAOffset, kind = BlockKind.TYPE, keyword = "class", spannedLines = 5),
            opening(openBOffset, kind = BlockKind.TYPE, keyword = "class"),
            opening(nth(text, '{', 4)),
            closing(nth(text, '}', 3), spannedLines = 2),
            closing(nth(text, '}', 4), kind = BlockKind.TYPE, keyword = "class", spannedLines = 5),
        )

        val violations = MemberSpacingPolicy.violations(text, accents, ALL_ON)

        assertEquals(1, violations.size)
        assertEquals(closeAOffset, violations[0].closingBraceOffset)
        assertEquals(openBOffset, violations[0].nextDeclarationOffset)
    }

    // ------------------------------------------------------------------------------- helpers

    /** The offset of the [occurrence]-th [symbol] in [text], counting from 1. */
    private fun nth(text: String, symbol: Char, occurrence: Int): Int {
        var from = 0
        var found = -1
        repeat(occurrence) {
            found = text.indexOf(symbol, from)
            check(found >= 0) { "occurrence $occurrence of '$symbol' not found in: $text" }
            from = found + 1
        }
        return found
    }

    private fun opening(
        offset: Int,
        kind: BlockKind = BlockKind.FUNCTION,
        keyword: String = "fun",
        isLambda: Boolean = false,
        isAccessor: Boolean = false,
        headerOffset: Int = -1,
    ): BraceAccent {
        return BraceAccent(
            offset = offset,
            kind = kind,
            nameOffset = -1,
            nameLength = 0,
            keyword = keyword,
            isOpening = true,
            spannedLines = 0,
            isLambda = isLambda,
            isAccessor = isAccessor,
            headerOffset = headerOffset,
        )
    }

    private fun closing(
        offset: Int,
        kind: BlockKind = BlockKind.FUNCTION,
        keyword: String = "fun",
        spannedLines: Int = 2,
        isLambda: Boolean = false,
        isAccessor: Boolean = false,
    ): BraceAccent {
        return BraceAccent(
            offset = offset,
            kind = kind,
            nameOffset = -1,
            nameLength = 0,
            keyword = keyword,
            isOpening = false,
            spannedLines = spannedLines,
            isLambda = isLambda,
            isAccessor = isAccessor,
        )
    }
}
