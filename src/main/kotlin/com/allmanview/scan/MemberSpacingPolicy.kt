package com.allmanview.scan

/**
 * Where two adjacent declarations in the same body -- two types, two members of a class, two
 * top-level namespaces -- should have a blank line between them and do not.
 *
 * Only [BlockKind.TYPE], [BlockKind.NAMESPACE] and a non-lambda, non-accessor
 * [BlockKind.FUNCTION] are members for this purpose. A lambda is an expression, not a
 * declaration, so it never counts on either side. A property's own accessors are parts of that
 * property, not its own neighbours -- `get` and `set` sitting back to back is not this. The
 * property's own block (kind [BlockKind.FUNCTION], keyword [PROPERTY_KEYWORD]) is tracked
 * separately from an ordinary function, so the two can be turned on or off independently.
 *
 * Two adjacent members are flagged only when at least one of them spans more than one line: two
 * packed one-line auto-properties, or two packed one-line methods, read fine with nothing
 * between them; a single-line property sitting against a multi-line one does not.
 *
 * Built entirely from [BraceAccent.offset] and [BraceAccent.isOpening], the same way
 * `SampleFilesTest` pairs braces to check they balance: no parent or depth field exists on
 * [BraceAccent] itself, so this walks the accent list once with a stack of open frames, each
 * frame remembering the last child of ITS OWN that has closed so far. A depth number alone
 * would not do: two sibling containers at the same nesting depth (say, two top-level classes,
 * each holding one method) would otherwise leak each other's last-closed child as if the two
 * methods from different classes were themselves neighbours. A block this plugin does not
 * report as an accent at all -- an `if`, a loop, an object initializer -- is simply absent from
 * the list, which is exactly right here: two members separated only by code like that are still
 * each other's neighbours for this purpose, and the stack never even notices what came between
 * them.
 */
object MemberSpacingPolicy {

    /** Which kinds of member this rule looks at at all; independent of one another. */
    data class Options(
        val types: Boolean,
        val namespaces: Boolean,
        val functions: Boolean,
        val properties: Boolean,
    )

    /** One place that needs a blank line and does not have one. */
    data class Violation(
        /** Offset of the earlier member's own closing brace -- the line to mark. */
        val closingBraceOffset: Int,
        /** Where the next member's own declaration starts. */
        val nextDeclarationOffset: Int,
    )

    fun violations(text: CharSequence, accents: List<BraceAccent>, options: Options): List<Violation> {
        val stack = ArrayDeque<OpenFrame>()
        var lastRootChildClose: BraceAccent? = null
        val violations = ArrayList<Violation>()

        for (accent in accents) {
            if (accent.isOpening) {
                val previousSibling = if (stack.isEmpty()) lastRootChildClose else stack.last().lastChildClose
                stack.addLast(OpenFrame(accent, previousSibling))
                continue
            }

            val frame = stack.removeLast()
            val violation = violationFor(text, frame, accent, options)
            if (violation != null) {
                violations.add(violation)
            }

            if (stack.isEmpty()) {
                lastRootChildClose = accent
            } else {
                stack.last().lastChildClose = accent
            }
        }
        return violations
    }

    private fun violationFor(
        text: CharSequence,
        frame: OpenFrame,
        closing: BraceAccent,
        options: Options,
    ): Violation? {
        val previousClosing = frame.previousSiblingClose ?: return null
        if (!isMember(previousClosing, options) || !isMember(frame.opening, options)) {
            return null
        }
        if (previousClosing.spannedLines <= 0 && closing.spannedLines <= 0) {
            return null
        }

        val gapStart = previousClosing.offset + 1
        val gapEnd = startOffset(frame.opening)
        if (gapStart >= gapEnd) {
            return null
        }
        if (hasBlankLine(text, gapStart, gapEnd)) {
            return null
        }
        return Violation(closingBraceOffset = previousClosing.offset, nextDeclarationOffset = gapEnd)
    }

    /** Whether [accent] is a member this rule tracks at all, [options] aside. */
    private fun isMember(accent: BraceAccent, options: Options): Boolean {
        if (accent.isLambda || accent.isAccessor) {
            return false
        }
        return when (accent.kind) {
            BlockKind.TYPE -> options.types
            BlockKind.NAMESPACE -> options.namespaces
            BlockKind.FUNCTION -> isMemberFunction(accent, options)
            BlockKind.OTHER -> false
        }
    }

    private fun isMemberFunction(accent: BraceAccent, options: Options): Boolean {
        if (accent.keyword == PROPERTY_KEYWORD) {
            return options.properties
        }
        return options.functions
    }

    /** The declaration's own start, past any attributes or comments a header does not include. */
    private fun startOffset(accent: BraceAccent): Int {
        if (accent.headerOffset >= 0) {
            return accent.headerOffset
        }
        return accent.offset
    }

    /**
     * Whether [text] between [start] and [end] holds a line with nothing but whitespace on it
     * -- not merely a line the previous member's own closing brace ends, and not a comment or
     * an attribute line in between: those are not blank, whatever they attach to.
     */
    private fun hasBlankLine(text: CharSequence, start: Int, end: Int): Boolean {
        var lastLineBreak = NO_LINE_BREAK_YET
        var index = start
        while (index < end) {
            if (text[index] == '\n') {
                if (lastLineBreak != NO_LINE_BREAK_YET && isBlank(text, lastLineBreak + 1, index)) {
                    return true
                }
                lastLineBreak = index
            }
            index++
        }
        return false
    }

    private fun isBlank(text: CharSequence, start: Int, end: Int): Boolean {
        for (index in start until end) {
            val character = text[index]
            if (character != ' ' && character != '\t' && character != '\r') {
                return false
            }
        }
        return true
    }

    /**
     * One open block waiting for its matching close: [opening] is its own opening brace,
     * [previousSiblingClose] is whichever sibling in the SAME parent closed right before it
     * (null if it is its parent's first child), and [lastChildClose] tracks this block's own
     * children as they close one by one, for whichever child comes next.
     */
    private class OpenFrame(val opening: BraceAccent, val previousSiblingClose: BraceAccent?) {
        var lastChildClose: BraceAccent? = null
    }

    private const val NO_LINE_BREAK_YET = -1
}
