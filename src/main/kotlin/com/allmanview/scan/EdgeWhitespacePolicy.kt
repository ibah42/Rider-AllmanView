package com.allmanview.scan

/**
 * Whitespace that should not be at the very start or the very end of a file: anything at all
 * before the first real character, or after the last one -- a trailing newline included. A
 * file is expected to end on its last real character, not on whitespace of any kind.
 *
 * Kept free of IntelliJ, the same reason [LabelPolicy] is: "why did this file get no marker"
 * is answerable by a unit test instead of by opening the IDE.
 */
object EdgeWhitespacePolicy {

    /** What the marker at [offset] should say, and whether it counts lines or characters. */
    data class Marker(val offset: Int, val count: Int, val unit: CountUnit) {
        val text: String
            get() = "whitespaces ($count)"
    }

    enum class CountUnit {
        LINES,
        CHARACTERS,
    }

    /**
     * Whitespace before the file's first real character, or null when the file starts with one
     * straight away. A file holding nothing but whitespace is reported here, as one leading
     * run -- [trailingMarker] never fires for it, so the same whitespace is not counted twice.
     */
    fun leadingMarker(text: CharSequence): Marker? {
        val firstRealCharacter = firstNonWhitespace(text)
        if (firstRealCharacter <= 0) {
            return null
        }
        return markerFor(offset = 0, region = text.subSequence(0, firstRealCharacter))
    }

    /**
     * Whitespace after the file's own last real character, or null when there is none of it --
     * including a lone trailing newline: IntelliJ's `Document` always normalises line
     * separators to "\n" internally regardless of what is on disk (CRLF on Windows included),
     * so there is no platform-specific case to special-case here.
     */
    fun trailingMarker(text: CharSequence): Marker? {
        val lastRealCharacter = lastNonWhitespace(text)
        if (lastRealCharacter < 0 || lastRealCharacter + 1 >= text.length) {
            return null
        }
        return markerFor(offset = text.length, region = text.subSequence(lastRealCharacter + 1, text.length))
    }

    /**
     * Lines when [region] spans at least one full line break -- a run of blank lines reads
     * better as a line count than a character count -- characters otherwise, for a run of
     * trailing spaces or tabs with no line break in it at all.
     */
    private fun markerFor(offset: Int, region: CharSequence): Marker {
        val lineBreakCount = region.count { character -> character == '\n' }
        if (lineBreakCount > 0) {
            return Marker(offset, lineBreakCount, CountUnit.LINES)
        }
        return Marker(offset, region.length, CountUnit.CHARACTERS)
    }

    private fun firstNonWhitespace(text: CharSequence): Int {
        for (index in text.indices) {
            if (!text[index].isWhitespace()) {
                return index
            }
        }
        return text.length
    }

    private fun lastNonWhitespace(text: CharSequence): Int {
        for (index in text.indices.reversed()) {
            if (!text[index].isWhitespace()) {
                return index
            }
        }
        return NO_REAL_CHARACTER
    }

    /** [lastNonWhitespace] returns this when the text holds no real character at all. */
    private const val NO_REAL_CHARACTER = -1
}
