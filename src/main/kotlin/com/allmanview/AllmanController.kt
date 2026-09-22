package com.allmanview

import com.allmanview.geometry.LabelNavigationGeometry
import com.allmanview.scan.BraceAccent
import com.allmanview.scan.BraceScanner
import com.allmanview.scan.Dialects
import com.allmanview.scan.EdgeWhitespacePolicy
import com.allmanview.scan.Flavor
import com.allmanview.scan.MemberSpacingPolicy
import com.allmanview.scan.PhantomSite
import com.allmanview.scan.ScanOptions
import com.allmanview.scan.ScanResult
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.Alarm
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseEvent
import kotlin.math.max

/**
 * One instance per editor.
 *
 * The document is never touched and nothing is hidden by folding: the original text stays
 * where it is and is dimmed by highlighting, while phantom lines are block inlays. That is
 * why there are no clashes with ReSharper's folding and no caret being pushed around while
 * typing.
 *
 * The two mechanics redraw on two separate timers, not one: see [scheduleMove], [scheduleAccent]
 * and the class doc on [MOVE_REFRESH_DELAY_MS] for why, and [moveHighlighters]/[accentHighlighters]
 * for how each keeps its own decorations so redrawing one never touches the other's.
 */
class AllmanController(private val editor: Editor) : Disposable {

    /** The move mechanic's own highlighters (the dimming of text that visually moved down). */
    private val moveHighlighters = ArrayList<RangeHighlighter>()

    /** The move mechanic's own inlays (the phantom lines themselves). */
    private val moveInlays = ArrayList<Inlay<*>>()

    /** The colour mechanic's own highlighters (brace colour and shadow). */
    private val accentHighlighters = ArrayList<RangeHighlighter>()

    /** The colour mechanic's own inlays (declaration-line markers and end-of-block labels). */
    private val accentInlays = ArrayList<Inlay<*>>()

    /**
     * See [scanResult]. Kept until the text changes or the editor is disposed, which trades a
     * scan's worth of memory per open editor for not scanning the same text twice.
     */
    private var cachedScan: CachedScan? = null

    /**
     * Whether the hand cursor over a navigable label is currently this controller's doing.
     *
     * Tracked rather than simply set on every mouse move: the custom cursor is a shared slot
     * other components ask for too, and writing to it on every pixel of pointer movement would
     * be both wasteful and a good way to stamp on somebody else's cursor.
     */
    private var handCursorShown = false

    private val moveAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val accentAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        editor.putUserData(KEY, this)
        editor.document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    // The move mechanic is what keeps the file reading as valid Allman style
                    // while typing, so it stays on the short timer. The colour mechanic draws
                    // strictly more decorations per accent on top of that -- colour, shadow,
                    // label, the nest and sibling-ordinal markers -- so it is the one worth
                    // delaying: a longer pause before it redraws means fewer full rebuilds of
                    // the more expensive half while someone is still actively typing.
                    scheduleMove(MOVE_REFRESH_DELAY_MS)
                    scheduleAccent(ACCENT_REFRESH_DELAY_MS)
                }
            },
            this,
        )
        editor.addEditorMouseListener(
            object : EditorMouseListener {
                override fun mousePressed(event: EditorMouseEvent) {
                    onMousePressed(event)
                }

                override fun mouseExited(event: EditorMouseEvent) {
                    showHandCursor(false)
                }
            },
            this,
        )
        editor.addEditorMouseMotionListener(
            object : EditorMouseMotionListener {
                override fun mouseMoved(event: EditorMouseEvent) {
                    showHandCursor(isOverNavigableLabel(event))
                }
            },
            this,
        )
        schedule(IMMEDIATE_DELAY_MS)
    }

    /**
     * Refreshes both mechanics at the same delay. For an external trigger -- a settings change,
     * a newly opened editor -- where they should show up together instead of staggered. Typing
     * itself never calls this; the document listener above schedules the two halves separately.
     */
    fun schedule(delayMs: Int = MOVE_REFRESH_DELAY_MS) {
        scheduleMove(delayMs)
        scheduleAccent(delayMs)
    }

    private fun scheduleMove(delayMs: Int) {
        if (editor.isDisposed) {
            return
        }
        moveAlarm.cancelAllRequests()
        moveAlarm.addRequest({ refreshMove() }, delayMs)
    }

    private fun scheduleAccent(delayMs: Int) {
        if (editor.isDisposed) {
            return
        }
        accentAlarm.cancelAllRequests()
        accentAlarm.addRequest({ refreshAccent() }, delayMs)
    }

    /** The phantom lines and the dimming of the text they stand in for. */
    private fun refreshMove() {
        if (editor.isDisposed) {
            return
        }
        clearMove()

        val settings = AllmanSettings.getInstance()
        if (!settings.state.enabled || !settings.state.moveBraces) {
            return
        }
        val flavor = flavorFor() ?: return
        if (editor.document.textLength > MAX_FILE_CHARS) {
            return
        }

        val result = scanResult(settings, flavor)
        val accentStyle = BraceAccentStyle(editor, settings, result.counts)
        paintMovedBraces(settings, result, phantomBraceStyles(accentStyle, result))
    }

    /** Brace colour and shadow, the end-of-block label, and the declaration-line markers. */
    private fun refreshAccent() {
        if (editor.isDisposed) {
            return
        }
        clearAccent()

        val settings = AllmanSettings.getInstance()
        if (!settings.state.enabled) {
            return
        }
        val flavor = flavorFor() ?: return
        if (editor.document.textLength > MAX_FILE_CHARS) {
            return
        }

        // Independent of brace scanning entirely, so it draws even on a file this half would
        // otherwise skip below for having nothing left to accent.
        paintEdgeWhitespaceMarkers(settings)

        val result = scanResult(settings, flavor)
        val accentStyle = BraceAccentStyle(editor, settings, result.counts)

        paintMemberSpacingMarkers(settings, result)
        paintDeclarationLineMarkers(accentStyle, result)
        paintRealBraces(
            accentStyle,
            result,
            buildBraceStyles(accentStyle, result),
            dimmedBraceOffsets(settings, result),
        )
    }

    /**
     * The scan both halves read, reused when they ask for the same text twice.
     *
     * The two timers fire at different moments but almost always over the very same document:
     * type once, and the move half scans at 200ms while the colour half scans again at 600ms.
     * Scanning is linear but not free -- about 90ms at the [MAX_FILE_CHARS] ceiling -- and
     * doing it twice put that on the EDT twice for one keystroke.
     *
     * Only the [ScanResult] is cached, never the styling built from it: a scan is a pure
     * function of the text and the options, while a colour is sampled from the editor and can
     * change with no edit at all -- the ReSharper backend answering late, or the colour scheme
     * switching. Caching those too would freeze stale colours until the next keystroke.
     */
    private fun scanResult(settings: AllmanSettings, flavor: Flavor): ScanResult {
        val options = scanOptions(settings)
        val modificationStamp = editor.document.modificationStamp

        val cached = cachedScan
        if (cached != null && cached.modificationStamp == modificationStamp && cached.options == options) {
            return cached.result
        }

        val result = BraceScanner(
            editor.document.immutableCharSequence,
            flavor,
            options,
        ).scan()
        cachedScan = CachedScan(modificationStamp, options, result)
        return result
    }

    private class CachedScan(
        val modificationStamp: Long,
        val options: ScanOptions,
        val result: ScanResult,
    )

    /**
     * Styles for the braces a phantom line actually redraws, and no others.
     *
     * [PhantomLineRenderer] looks a style up by document offset for every character it paints,
     * and nothing else in this half uses one -- so styling every accent, as the colour half
     * must, would sample a colour per accent and then throw nearly all of them away. In a file
     * already written in Allman style there are no phantom lines at all, and every one of those
     * samples would be wasted.
     */
    private fun phantomBraceStyles(
        style: BraceAccentStyle,
        result: ScanResult,
    ): Map<Int, BraceStyle> {
        if (result.sites.isEmpty() || result.accents.isEmpty()) {
            return emptyMap()
        }

        val painted = HashSet<Int>()
        for (site in result.sites) {
            for (line in site.phantomLines) {
                for (offset in line.sourceOffset until line.sourceOffset + line.text.length) {
                    painted.add(offset)
                }
            }
        }

        val styles = HashMap<Int, BraceStyle>()
        for (accent in result.accents) {
            // A phantom brace is the brace, visually -- so it follows the same "colour the
            // braces" switch the real one does, not merely the kind being recognised.
            if (accent.offset !in painted || !style.showsBraces(accent)) {
                continue
            }
            val braceStyle = style.styleFor(accent)
            if (braceStyle != null) {
                styles[accent.offset] = braceStyle
            }
        }
        return styles
    }

    private fun scanOptions(settings: AllmanSettings): ScanOptions {
        return ScanOptions(
            fullAllman = settings.state.fullAllman,
            splitStatements = settings.state.splitStatements,
            expandInlineBlocks = settings.state.expandInlineBlocks,
            accentTypes = settings.state.accentBraces && settings.state.accentTypes,
            accentFunctions = settings.state.accentBraces && settings.state.accentFunctions,
            accentMethods = settings.state.accentMethods,
            accentConstructors = settings.state.accentConstructors,
            accentProperties = settings.state.accentProperties,
            accentAccessors = settings.state.accentAccessors,
            accentLambdas = settings.state.accentLambdas,
            accentNamespaces = settings.state.accentBraces && settings.state.accentNamespaces,
        )
    }

    /** Brace offset to its styling. */
    private fun buildBraceStyles(
        style: BraceAccentStyle,
        result: ScanResult,
    ): Map<Int, BraceStyle> {
        if (result.accents.isEmpty()) {
            return emptyMap()
        }

        val styles = HashMap<Int, BraceStyle>(result.accents.size)
        for (accent in result.accents) {
            val braceStyle = style.styleFor(accent)
            if (braceStyle != null) {
                styles[accent.offset] = braceStyle
            }
        }
        return styles
    }

    private fun paintMovedBraces(
        settings: AllmanSettings,
        result: ScanResult,
        braceStyles: Map<Int, BraceStyle>,
    ) {
        val dimAttributes: TextAttributes?
        if (settings.state.dimOriginal) {
            dimAttributes = TextAttributes()
            dimAttributes.foregroundColor = dimColor(settings)
        } else {
            dimAttributes = null
        }

        val documentLength = editor.document.textLength
        for (site in result.sites) {
            if (site.dimEnd > documentLength || site.anchorOffset > documentLength) {
                continue
            }
            if (dimAttributes != null) {
                addHighlighter(site.dimStart, site.dimEnd, DIM_LAYER_OFFSET, dimAttributes, moveHighlighters)
            }
            addPhantomLines(site, braceStyles)
        }
    }

    /**
     * Real `{` and `}` of types and functions: colour, shadow and the end-of-block label.
     *
     * A brace already dimmed as "moved down" is left alone, because accenting would contradict
     * the dimming. Its role is played by the phantom, which the renderer colours. The label and
     * the shadow are still attached, since they belong to the real brace.
     */
    private fun paintRealBraces(
        style: BraceAccentStyle,
        result: ScanResult,
        braceStyles: Map<Int, BraceStyle>,
        dimmedBraces: Set<Int>,
    ) {
        val documentLength = editor.document.textLength

        for (accent in result.accents) {
            if (accent.offset >= documentLength) {
                continue
            }
            val braceStyle = braceStyles[accent.offset]
            if (braceStyle == null) {
                continue
            }

            if (style.showsBraces(accent) && accent.offset !in dimmedBraces) {
                addHighlighter(
                    accent.offset,
                    accent.offset + 1,
                    ACCENT_LAYER_OFFSET,
                    braceStyle.attributes,
                    accentHighlighters,
                )
                addShadow(accent.offset, braceStyle)
            }

            if (accent.isOpening) {
                continue
            }
            val segments = endOfBlockSegments(style, accent, braceStyle)
            if (segments.isNotEmpty()) {
                addLabel(accent, segments)
            }
        }
    }

    /**
     * The declaration-line markers in front of a block's own header: the sibling ordinal
     * `[N]`, the `nest` marker, or both together with `[N]` first, in the same colour as the
     * prefix on the block's end-of-block label. Placed right after the line's indent, so the
     * real declaration is pushed right rather than the marker landing in the margin.
     */
    private fun paintDeclarationLineMarkers(style: BraceAccentStyle, result: ScanResult) {
        val documentLength = editor.document.textLength

        for (accent in result.accents) {
            // Only the opening side has a declaration line to sit in front of; both accents of
            // a numbered block otherwise carry the same ordinal, which would draw it twice.
            if (!accent.isOpening) {
                continue
            }
            val headerOffset = headerOffsetOf(accent)
            if (headerOffset >= documentLength) {
                continue
            }

            val contentOffset = contentStartOffset(headerOffset)
            val segments = ArrayList<BlockLabelRenderer.Segment>(MARKER_SEGMENT_CAPACITY)
            appendMarkerSegments(segments, style, accent, contentOffset)
            if (segments.isEmpty()) {
                continue
            }
            addDeclarationLineMarker(contentOffset, segments)
        }
    }

    /**
     * The paired markers, in order: the sibling ordinal `[N]`, then the `nest` word. Each has
     * its own switch and its own colour, and each is drawn in both places a marker belongs --
     * before the declaration and again at the start of the end-of-block label -- which is why
     * this is one function serving both.
     *
     * @param sampleOffset where the marker's colour is read from, so it greys out with the code
     *   around it: the declaration itself on the opening side, the closing brace on the other
     */
    private fun appendMarkerSegments(
        segments: MutableList<BlockLabelRenderer.Segment>,
        style: BraceAccentStyle,
        accent: BraceAccent,
        sampleOffset: Int,
    ) {
        val ordinalText = style.siblingOrdinalText(accent)
        if (ordinalText.isNotEmpty()) {
            appendSegment(segments, ordinalText, style.siblingOrdinalColor(sampleOffset))
        }
        if (style.marksAsNested(accent)) {
            appendSegment(
                segments,
                BraceAccentStyle.NESTED_MARKER_TEXT,
                style.nestedMarkerColor(sampleOffset),
            )
        }
    }

    /** Adds a run, separated by a single space from whatever already stands to its left. */
    private fun appendSegment(
        segments: MutableList<BlockLabelRenderer.Segment>,
        text: String,
        color: Color,
    ) {
        val separator: String
        if (segments.isEmpty()) {
            separator = ""
        } else {
            separator = " "
        }
        segments.add(BlockLabelRenderer.Segment(separator + text, color))
    }

    /** The declaration line, not the brace line: a multi-line signature starts well above it. */
    private fun headerOffsetOf(opening: BraceAccent): Int {
        if (opening.headerOffset in 0 until editor.document.textLength) {
            return opening.headerOffset
        }
        return opening.offset
    }

    /** First non-blank character of the line holding this offset, past its indent. */
    private fun contentStartOffset(offset: Int): Int {
        val document = editor.document
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        val characters = document.immutableCharSequence

        var end = lineStart
        while (end < characters.length && (characters[end] == ' ' || characters[end] == '\t')) {
            end++
        }
        return end
    }

    private fun addDeclarationLineMarker(offset: Int, segments: List<BlockLabelRenderer.Segment>) {
        val inlay = editor.inlayModel.addInlineElement(
            offset,
            /* relatesToPrecedingText = */ false,
            BlockLabelRenderer(
                segments = segments,
                leadingSpaces = 0,
                trailingSpaces = 1,
            ),
        )
        if (inlay != null) {
            accentInlays.add(inlay)
        }
    }

    private fun addShadow(braceOffset: Int, braceStyle: BraceStyle) {
        val shadowColor = braceStyle.shadowColor
        if (shadowColor == null) {
            return
        }

        val highlighter = editor.markupModel.addRangeHighlighter(
            braceOffset,
            braceOffset + 1,
            HighlighterLayer.LAST + SHADOW_LAYER_OFFSET,
            null,
            HighlighterTargetArea.EXACT_RANGE,
        )
        highlighter.customRenderer = BraceShadowRenderer(
            shadowColor,
            braceStyle.shadowOffsetX,
            braceStyle.shadowOffsetY,
            braceStyle.attributes.fontType,
        )
        accentHighlighters.add(highlighter)
    }

    private fun addLabel(accent: BraceAccent, segments: List<BlockLabelRenderer.Segment>) {
        val inlay = editor.inlayModel.addInlineElement(
            accent.offset + 1,
            /* relatesToPrecedingText = */ true,
            BlockLabelRenderer(
                segments = segments,
                leadingSpaces = LABEL_LEADING_SPACES,
                trailingSpaces = 0,
            ),
        )
        if (inlay != null) {
            accentInlays.add(inlay)
            // The label doubles as a button -- see [onMousePressed]. The offset it leads to
            // hangs on the inlay rather than on its renderer, which is shared with the
            // declaration-line marker and has nowhere to lead, and rather than in a map beside
            // accentInlays, which would be a second lifetime to keep in step with this one.
            inlay.putUserData(NAVIGATION_TARGET, declarationTargetOf(accent))
        }
    }

    /**
     * Where this block's end-of-block label should lead: the offset its declaration starts at,
     * or null when there is nowhere worth going.
     *
     * Deliberately without [headerOffsetOf]'s fallback to the brace itself. On a closing brace
     * that fallback is the very line the label is drawn on, and a button that carries the
     * reader to where they already stand is worse than no button at all.
     */
    private fun declarationTargetOf(accent: BraceAccent): Int? {
        val headerOffset = accent.headerOffset
        if (headerOffset < 0 || headerOffset >= editor.document.textLength) {
            return null
        }
        // Past the indent, onto the first real character of the line. The line start is inside
        // the whitespace, which is neither what the reader is looking at nor where they would
        // start typing -- and it is where the caret lands at its least useful, several tab
        // stops away from the declaration it was sent to. This is the same anchor the
        // declaration-line marker uses, so the caret arrives exactly where that marker sits.
        return contentStartOffset(headerOffset)
    }

    /**
     * A click on an end-of-block label goes to the declaration it names, when the click
     * carries the modifiers the settings ask for -- Ctrl, or Cmd on macOS, out of the box.
     *
     * Handled on press rather than on click because press is where the editor decides where to
     * put the caret, and it checks the event for having been consumed before it does. A label
     * is not text in the document, so the caret would otherwise land on whichever character of
     * the real line the pointer happened to be over -- never where the reader asked to go.
     */
    private fun onMousePressed(event: EditorMouseEvent) {
        if (event.isConsumed) {
            return
        }
        if (event.mouseEvent.button != MouseEvent.BUTTON1) {
            return
        }

        // Typed here, and not left as whatever `getInlay()` hands back: that method is
        // declared with a raw `Inlay`, and Kotlin erases the members of a raw type -- read
        // straight off it, getUserData answers Any? rather than Int?.
        val label: Inlay<*> = event.inlay ?: return
        val target = navigationTargetOf(label, event.mouseEvent)
        if (target == null) {
            return
        }

        event.consume()
        navigateToDeclaration(label, target)
    }

    private fun isOverNavigableLabel(event: EditorMouseEvent): Boolean {
        val label: Inlay<*> = event.inlay ?: return false
        return navigationTargetOf(label, event.mouseEvent) != null
    }

    /** The offset this label leads to, or null when it is not a label that leads anywhere. */
    private fun navigationTargetOf(label: Inlay<*>, mouseEvent: MouseEvent): Int? {
        val target = label.getUserData(NAVIGATION_TARGET)
        if (target == null || target >= editor.document.textLength) {
            return null
        }

        // The settings are read last on purpose. This runs on every pointer move, and the
        // question above answers "no" for every pixel that is not over a label, which is very
        // nearly all of them.
        val config = AllmanSettings.getInstance().state
        if (!config.labelNavigationEnabled) {
            return null
        }
        if (!matchesNavigationGesture(mouseEvent, config)) {
            return null
        }
        return target
    }

    /**
     * Whether the modifiers being held are exactly the ones the settings ask for.
     *
     * Exactly, not "at least". With Ctrl alone configured, a Ctrl+Alt-click is a different
     * gesture and belongs to whatever else is listening for it; swallowing it here would make
     * the label a trap laid across somebody else's shortcut.
     */
    private fun matchesNavigationGesture(
        mouseEvent: MouseEvent,
        config: AllmanSettings.Config,
    ): Boolean {
        if (config.labelNavigationCtrl != isPrimaryModifierDown(mouseEvent)) {
            return false
        }
        if (config.labelNavigationAlt != mouseEvent.isAltDown) {
            return false
        }
        return config.labelNavigationShift == mouseEvent.isShiftDown
    }

    /**
     * Ctrl everywhere, Cmd on macOS -- the same key that means "go to" in every other editor
     * gesture on the platform. Control-click is a right-click on a Mac, so reading it there
     * would open the context menu and navigate at the same time.
     */
    private fun isPrimaryModifierDown(mouseEvent: MouseEvent): Boolean {
        if (SystemInfo.isMac) {
            return mouseEvent.isMetaDown
        }
        return mouseEvent.isControlDown
    }

    /**
     * Move the caret to the declaration and re-aim the view at it.
     *
     * Two things the platform will not do on its own. Moving the caret does not scroll -- see
     * OpenFileDescriptor, which has to ask for that separately -- which is exactly what lets
     * [LabelNavigationGeometry] decide where the view lands instead of the platform centring
     * on the caret. And the jump only shows up under Back if it happened inside a command that
     * declared itself a navigation: IdeDocumentHistory records the place being left when such
     * a command finishes, and the caret crossing a line marks the other half of that test
     * itself.
     */
    private fun navigateToDeclaration(label: Inlay<*>, targetOffset: Int) {
        // Read before the caret moves, so the geometry is the one the reader clicked on.
        val scrollTo = scrollAfterJump(label, targetOffset)

        val project = editor.project
        if (project == null || project.isDefault) {
            moveCaretTo(targetOffset)
        } else {
            CommandProcessor.getInstance().executeCommand(
                project,
                {
                    IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation()
                    moveCaretTo(targetOffset)
                },
                NAVIGATION_COMMAND_NAME,
                null,
            )
        }

        if (scrollTo != null) {
            editor.scrollingModel.scrollVertically(scrollTo)
        }
    }

    private fun moveCaretTo(offset: Int) {
        editor.caretModel.removeSecondaryCarets()
        editor.caretModel.moveToOffset(offset)
        editor.selectionModel.removeSelection()
    }

    /** Where to scroll after the jump, or null to leave the view alone. */
    private fun scrollAfterJump(label: Inlay<*>, targetOffset: Int): Int? {
        val labelBounds = label.bounds
        if (labelBounds == null) {
            return null
        }
        val visibleArea = editor.scrollingModel.visibleArea

        return LabelNavigationGeometry.scrollTargetY(
            clickedY = labelBounds.y,
            targetY = editor.offsetToXY(targetOffset).y,
            viewportTop = visibleArea.y,
            viewportHeight = visibleArea.height,
            lineHeight = editor.lineHeight,
            maximumScroll = max(0, editor.contentComponent.height - visibleArea.height),
            marginLines = NAVIGATION_MARGIN_LINES,
        )
    }

    /**
     * The hand cursor over a label that leads somewhere, and only while the configured
     * modifiers are held -- the same bargain Ctrl-hover strikes everywhere else in the IDE,
     * where the pointer changes exactly when a click would do something.
     */
    private fun showHandCursor(show: Boolean) {
        if (show == handCursorShown) {
            return
        }
        val editorEx = editor as? EditorEx
        if (editorEx == null) {
            return
        }

        if (show) {
            editorEx.setCustomCursor(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
        } else {
            editorEx.setCustomCursor(this, null)
        }
        handCursorShown = show
    }

    /**
     * Every run the closing brace's inlay draws, left to right: the paired markers first, each
     * behind its own switch, then the construct word (`class`, `fun`, `ns`, ...) in the
     * editor's own keyword colour and the symbol's own name in its own accent colour -- those
     * last two only when this kind of block is named at all, which is a separate switch again
     * -- and last of all, on a very long block, the span marker `↑: 920  Δ: 143`.
     *
     * An empty list means the closing brace gets no inlay: with every part switched off there
     * would be nothing to draw in it.
     */
    private fun endOfBlockSegments(
        style: BraceAccentStyle,
        accent: BraceAccent,
        braceStyle: BraceStyle,
    ): List<BlockLabelRenderer.Segment> {
        val segments = ArrayList<BlockLabelRenderer.Segment>(LABEL_SEGMENT_CAPACITY)
        appendMarkerSegments(segments, style, accent, accent.offset)

        if (style.needsLabel(accent)) {
            if (braceStyle.keywordText.isNotEmpty()) {
                appendSegment(segments, braceStyle.keywordText, braceStyle.keywordColor)
            }
            if (braceStyle.nameText.isNotEmpty()) {
                appendSegment(segments, braceStyle.nameText, braceStyle.nameColor)
            }
        }

        // Last, after the name, and outside the label question entirely: the span is about the
        // block as a whole, so it reads as a footnote to whatever stands in front of it -- and
        // it is the one marker that says something complete on its own, so a block that is not
        // named still gets it.
        if (style.showsBlockSpan(accent)) {
            appendSegment(segments, style.blockSpanText(accent), style.blockSpanColor())
        }
        return segments
    }

    /**
     * The braces the move mechanic dimmed, so accenting can leave them alone.
     *
     * Collected once per refresh rather than asked per brace: walking every site for every
     * accent is O(accents x sites), which on a large file is millions of comparisons on the
     * EDT for a single keystroke. Only brace offsets are kept, so the set stays small however
     * much text is dimmed.
     */
    private fun dimmedBraceOffsets(settings: AllmanSettings, result: ScanResult): Set<Int> {
        if (!settings.state.moveBraces) {
            return emptySet()
        }
        if (!settings.state.dimOriginal) {
            return emptySet()
        }

        val braceOffsets = HashSet<Int>(result.accents.size)
        for (accent in result.accents) {
            braceOffsets.add(accent.offset)
        }

        val dimmed = HashSet<Int>()
        for (site in result.sites) {
            for (offset in site.dimStart until site.dimEnd) {
                if (braceOffsets.contains(offset)) {
                    dimmed.add(offset)
                }
            }
        }
        return dimmed
    }

    private fun addHighlighter(
        start: Int,
        end: Int,
        layerOffset: Int,
        attributes: TextAttributes,
        highlighters: MutableList<RangeHighlighter>,
    ) {
        val highlighter = editor.markupModel.addRangeHighlighter(
            start,
            end,
            // above the syntax highlighting, otherwise the colour is overridden back
            HighlighterLayer.LAST + layerOffset,
            attributes,
            HighlighterTargetArea.EXACT_RANGE,
        )
        highlighters.add(highlighter)
    }

    private fun addPhantomLines(site: PhantomSite, braceStyles: Map<Int, BraceStyle>) {
        val inlay = editor.inlayModel.addBlockElement(
            site.anchorOffset,
            /* relatesToPrecedingText = */ true,
            /* showAbove = */ false,
            /* priority = */ 0,
            PhantomLineRenderer(site.indent, site.phantomLines, braceStyles),
        )
        if (inlay != null) {
            moveInlays.add(inlay)
        }
    }

    /** By default the same muted colour the platform uses for parameter hints. */
    private fun dimColor(settings: AllmanSettings): Color {
        val scheme = editor.colorsScheme

        if (settings.state.dimUseHintColor) {
            val hintAttributes = scheme.getAttributes(
                DefaultLanguageHighlighterColors.INLINE_PARAMETER_HINT,
            )
            val hintColor = hintAttributes?.foregroundColor
            if (hintColor != null) {
                return hintColor
            }
        }

        return ColorBalance.mix(
            scheme.defaultForeground,
            scheme.defaultBackground,
            settings.state.dimPercent,
        )
    }

    private fun clearMove() {
        removeHighlighters(moveHighlighters)
        disposeInlays(moveInlays)
    }

    private fun clearAccent() {
        removeHighlighters(accentHighlighters)
        disposeInlays(accentInlays)
    }

    private fun removeHighlighters(highlighters: MutableList<RangeHighlighter>) {
        val markupModel = editor.markupModel
        for (highlighter in highlighters) {
            if (highlighter.isValid) {
                markupModel.removeHighlighter(highlighter)
            }
        }
        highlighters.clear()
    }

    private fun disposeInlays(inlays: MutableList<Inlay<*>>) {
        for (inlay in inlays) {
            Disposer.dispose(inlay)
        }
        inlays.clear()
    }

    private fun flavorFor(): Flavor? {
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        if (file == null) {
            return null
        }
        if (file.fileType.isBinary) {
            return null
        }

        val extension = file.extension
        if (!AllmanSettings.getInstance().appliesTo(extension)) {
            return null
        }

        // The dialect only matters for string literal boundaries; an unknown extension is
        // parsed as GENERIC, which works for any C-like language.
        return Dialects.forExtension(extension ?: "")
    }

    /**
     * "whitespaces (N)" in reddish grey: whitespace that should not be at the very start or
     * the very end of the file. A third, independent mechanic -- it has nothing to do with
     * braces, so its only prerequisite besides its own switch is the plugin's master one. Kept
     * on the accent half rather than a list of its own: it is pure decoration, exactly like the
     * rest of that half, and CLAUDE.md is explicit that a third shared list is not "for
     * convenience".
     */
    private fun paintEdgeWhitespaceMarkers(settings: AllmanSettings) {
        if (!settings.state.edgeWhitespaceEnabled) {
            return
        }

        val text = editor.document.immutableCharSequence
        val minimumCount = settings.state.edgeWhitespaceMinCount
        val color = edgeWhitespaceColor(settings)

        if (settings.state.edgeWhitespaceLeading) {
            val marker = EdgeWhitespacePolicy.leadingMarker(text)
            if (marker != null && marker.count >= minimumCount) {
                addEdgeWhitespaceLabel(marker, color, leading = true)
            }
        }
        if (settings.state.edgeWhitespaceTrailing) {
            val marker = EdgeWhitespacePolicy.trailingMarker(text)
            if (marker != null && marker.count >= minimumCount) {
                addEdgeWhitespaceLabel(marker, color, leading = false)
            }
        }
    }

    /** Mixed from a plain warning red, the same way every other marker is mixed from its own base. */
    private fun edgeWhitespaceColor(settings: AllmanSettings): Color {
        return ColorBalance.towardsGrey(EDGE_WHITESPACE_BASE_RED, settings.state.edgeWhitespaceGreyPercent)
    }

    /**
     * @param leading true at the file's first character, false at its last -- decides which
     *   side of the label the gap goes on, so the text never sticks to the real content.
     */
    private fun addEdgeWhitespaceLabel(
        marker: EdgeWhitespacePolicy.Marker,
        color: Color,
        leading: Boolean,
    ) {
        val segments = listOf(BlockLabelRenderer.Segment(marker.text, color))
        val leadingSpaces: Int
        val trailingSpaces: Int
        if (leading) {
            leadingSpaces = 0
            trailingSpaces = 1
        } else {
            leadingSpaces = LABEL_LEADING_SPACES
            trailingSpaces = 0
        }

        val inlay = editor.inlayModel.addInlineElement(
            marker.offset,
            /* relatesToPrecedingText = */ !leading,
            BlockLabelRenderer(
                segments = segments,
                leadingSpaces = leadingSpaces,
                trailingSpaces = trailingSpaces,
            ),
        )
        if (inlay != null) {
            accentInlays.add(inlay)
        }
    }

    /**
     * A red wavy line under the closing brace's own line, running out to the right edge of the
     * editor's viewport, wherever two adjacent members -- two types, two namespaces, two
     * functions, two properties -- sit with no blank line between them and at least one of the
     * two runs more than one line. See [MemberSpacingPolicy] for exactly which pairs count. A
     * fourth, independent mechanic, for the same reason [paintEdgeWhitespaceMarkers] is: it
     * needs the scan's accents but nothing else this half builds from them, so it is kept out
     * of [buildBraceStyles] and the rest of the per-brace styling.
     */
    private fun paintMemberSpacingMarkers(settings: AllmanSettings, result: ScanResult) {
        val options = settings.memberSpacingOptions() ?: return
        if (result.accents.isEmpty()) {
            return
        }

        val text = editor.document.immutableCharSequence
        val color = memberSpacingColor(settings)

        for (violation in MemberSpacingPolicy.violations(text, result.accents, options)) {
            addMemberSpacingUnderline(violation.closingBraceOffset, color)
        }
    }

    /** Mixed from a plain warning red, the same way every other marker is mixed from its own base. */
    private fun memberSpacingColor(settings: AllmanSettings): Color {
        return ColorBalance.towardsGrey(MEMBER_SPACING_BASE_RED, settings.state.memberSpacingGreyPercent)
    }

    /**
     * The highlighter still spans the closing brace's own line -- that is what keeps it
     * anchored and invalidated as the line is edited -- but [MemberSpacingUnderlineRenderer]
     * paints well past [lineEnd], out to the edge of the viewport, so no [TextAttributes] effect
     * is set here: the renderer does all of the drawing itself.
     */
    private fun addMemberSpacingUnderline(closingBraceOffset: Int, color: Color) {
        val document = editor.document
        val lineNumber = document.getLineNumber(closingBraceOffset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)

        val highlighter = editor.markupModel.addRangeHighlighter(
            lineStart,
            lineEnd,
            HighlighterLayer.LAST + MEMBER_SPACING_LAYER_OFFSET,
            null,
            HighlighterTargetArea.EXACT_RANGE,
        )
        highlighter.customRenderer = MemberSpacingUnderlineRenderer(color)
        accentHighlighters.add(highlighter)
    }

    override fun dispose() {
        editor.putUserData(KEY, null)
        cachedScan = null
        if (!editor.isDisposed) {
            clearMove()
            clearAccent()
            showHandCursor(false)
        }
    }

    companion object {
        /**
         * How long a pause in typing has to be before the move mechanic redraws. Short, because
         * this is the mechanic that keeps the file reading as Allman style at all -- the file
         * would otherwise flash back to its real, non-Allman brace placement while someone is
         * still typing.
         */
        private const val MOVE_REFRESH_DELAY_MS = 200

        /**
         * How long a pause before the colour mechanic redraws -- noticeably longer than
         * [MOVE_REFRESH_DELAY_MS]. It is pure decoration on top of what the move mechanic
         * already drew, and it touches strictly more highlighters and inlays per accent
         * (colour, shadow, the end-of-block label, the nest and sibling-ordinal markers), so it
         * is the more expensive half to rebuild on every short pause. Delaying it further means
         * a fast typing burst rebuilds it only once it actually stops, instead of once per
         * pause in the middle of it.
         */
        private const val ACCENT_REFRESH_DELAY_MS = 600

        /** Also used by the service when a settings change has to show up at once, for both. */
        const val IMMEDIATE_DELAY_MS = 0

        /** How far above the syntax highlighting the dimming highlighter sits. */
        private const val DIM_LAYER_OFFSET = 100

        /** Brace accents also sit above syntax, but below the dimming. */
        private const val ACCENT_LAYER_OFFSET = 90

        /** The shadow paints before the text; the layer only keeps it out of others' way. */
        private const val SHADOW_LAYER_OFFSET = 80

        /** Gap after the brace so the end-of-block label does not stick to it. */
        private const val LABEL_LEADING_SPACES = 2

        /** The most runs an end-of-block label draws: two markers, the keyword, the name. */
        private const val LABEL_SEGMENT_CAPACITY = 5

        /** The most runs a declaration-line marker draws: the ordinal and the `nest` word. */
        private const val MARKER_SEGMENT_CAPACITY = 2

        /** The scanner is linear, but a full timed rescan of a huge file is pointless. */
        private const val MAX_FILE_CHARS = 2_000_000

        /** Plain warning red; the edge-whitespace marker mixes this towards grey itself. */
        private val EDGE_WHITESPACE_BASE_RED = Color(196, 80, 80)

        /** Plain warning red; the member-spacing underline mixes this towards grey itself. */
        private val MEMBER_SPACING_BASE_RED = Color(196, 80, 80)

        /** Above the dimming highlighter too, so the underline is never painted over. */
        private const val MEMBER_SPACING_LAYER_OFFSET = 110

        /**
         * How close to an edge of the viewport a declaration may sit and still count as one
         * the reader can already see. Inside this many lines of the top or the bottom,
         * clicking the label re-aims the view instead of leaving it alone -- a declaration
         * clinging to an edge is visible in the letter of the word only.
         */
        private const val NAVIGATION_MARGIN_LINES = 2

        /**
         * What the caret move is called. Nothing is edited, so this never appears as an undo
         * entry; the command exists only so the jump can be recorded as a navigation.
         */
        private const val NAVIGATION_COMMAND_NAME = "Go to Block Declaration"

        /** Where an end-of-block label leads; absent on an inlay that leads nowhere. */
        private val NAVIGATION_TARGET: Key<Int> = Key.create("allman.view.navigation.target")

        val KEY: Key<AllmanController> = Key.create("allman.view.controller")
    }
}
