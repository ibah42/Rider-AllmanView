package com.allmanview.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The counting rules behind the "whitespaces (N)" marker, tested without an editor -- the same
 * reason [LabelPolicyTest] exists for [LabelPolicy].
 */
class EdgeWhitespacePolicyTest {

    // ------------------------------------------------------------------ leadingMarker

    @Test
    fun `leadingMarker is null when the file starts with real content`() {
        assertNull(EdgeWhitespacePolicy.leadingMarker("class Foo {}"))
    }

    @Test
    fun `leadingMarker counts blank lines before the first real character`() {
        val marker = EdgeWhitespacePolicy.leadingMarker("\n\n\nclass Foo {}")

        assertEquals(0, marker?.offset)
        assertEquals(3, marker?.count)
        assertEquals(EdgeWhitespacePolicy.CountUnit.LINES, marker?.unit)
        assertEquals("whitespaces (3)", marker?.text)
    }

    @Test
    fun `leadingMarker counts characters when there is no line break at all`() {
        val marker = EdgeWhitespacePolicy.leadingMarker("   class Foo {}")

        assertEquals(3, marker?.count)
        assertEquals(EdgeWhitespacePolicy.CountUnit.CHARACTERS, marker?.unit)
    }

    @Test
    fun `leadingMarker reports the whole file when it is nothing but whitespace`() {
        val marker = EdgeWhitespacePolicy.leadingMarker("\n\n  \n")

        assertEquals(3, marker?.count)
        assertEquals(EdgeWhitespacePolicy.CountUnit.LINES, marker?.unit)
    }

    @Test
    fun `leadingMarker is null for an empty file`() {
        assertNull(EdgeWhitespacePolicy.leadingMarker(""))
    }

    // ------------------------------------------------------------------ trailingMarker

    @Test
    fun `trailingMarker flags even a single trailing newline`() {
        val marker = EdgeWhitespacePolicy.trailingMarker("class Foo {}\n")

        assertEquals(13, marker?.offset)
        assertEquals(1, marker?.count)
        assertEquals(EdgeWhitespacePolicy.CountUnit.LINES, marker?.unit)
        assertEquals("whitespaces (1)", marker?.text)
    }

    @Test
    fun `trailingMarker flags a single trailing CRLF the same as a single trailing newline`() {
        // Stands in for a Windows-saved file: IntelliJ's Document always normalises to "\n"
        // internally, so this is what the plugin actually sees, never a literal "\r\n" -- but
        // the pure function is tested against a raw CRLF too, in case it is ever fed one.
        val marker = EdgeWhitespacePolicy.trailingMarker("class Foo {}\r\n")

        assertEquals(1, marker?.count)
        assertEquals(EdgeWhitespacePolicy.CountUnit.LINES, marker?.unit)
    }

    @Test
    fun `trailingMarker is null when the file ends with no line break at all`() {
        assertNull(EdgeWhitespacePolicy.trailingMarker("class Foo {}"))
    }

    @Test
    fun `trailingMarker flags extra blank lines past the file's own newline`() {
        val marker = EdgeWhitespacePolicy.trailingMarker("class Foo {}\n\n\n")

        assertEquals(15, marker?.offset)
        assertEquals(3, marker?.count)
        assertEquals(EdgeWhitespacePolicy.CountUnit.LINES, marker?.unit)
        assertEquals("whitespaces (3)", marker?.text)
    }

    @Test
    fun `trailingMarker counts characters when the file ends with no line break at all`() {
        val marker = EdgeWhitespacePolicy.trailingMarker("class Foo {}   ")

        assertEquals(3, marker?.count)
        assertEquals(EdgeWhitespacePolicy.CountUnit.CHARACTERS, marker?.unit)
    }

    @Test
    fun `trailingMarker folds trailing spaces and the file's own newline into one line`() {
        // The newline is not stripped out first any more, so a run that ends in one is a
        // line count, not a character count -- consistent with markerFor's own rule.
        val marker = EdgeWhitespacePolicy.trailingMarker("class Foo {}   \n")

        assertEquals(1, marker?.count)
        assertEquals(EdgeWhitespacePolicy.CountUnit.LINES, marker?.unit)
    }

    @Test
    fun `trailingMarker is null for an empty file`() {
        assertNull(EdgeWhitespacePolicy.trailingMarker(""))
    }
}
