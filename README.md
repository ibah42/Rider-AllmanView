# Allman View

A plugin for Rider (and any other IntelliJ IDE) that shows your code in Allman style
**visually**, without changing a single byte in the file.

```
// what is in the file            // what you see in the editor
if (x) {                         if (x)
    Foo();                       {
} else {                             Foo();
    Bar();                       }
}                                else
                                 {
                                     Bar();
                                 }
```

Single statements are moved down as well:

```
// what is in the file                    // what you see in the editor
if (body == null) return;                 if (body == null) return;
                                              return;

foreach (var x in items) Sum += x;        foreach (var x in items) Sum += x;
                                              Sum += x;

if (verbose) { Log(id); }                 if (verbose) { Log(id); }
                                          {
                                              Log(id);
                                          }
```

The grey text is the real one; the phantom is drawn with the real highlighting, the same one the
original has. `if`, `else`, `for`, `foreach`, `while`, `using`, `lock` and `fixed` are all split.
Each of those can be turned off separately in the settings.

Constructs where a single line is the right shape are deliberately left alone:
`using System.Text;` (a directive, not a block), `do { } while (x);`,
`public int X { get; set; }`, `void M() { }`, `while (reader.Read());`, and initializers such as
`new Point { X = 1 }`.

## Accented braces of types and functions

Braces that belong to a type declaration (`class`, `struct`, `interface`, `enum`, `record`) or to
a function declaration are painted more prominently than the rest: the base colour is taken
**from the name itself** in the document, pushed away from the background, and set in bold.

The colour is sampled from the name rather than read from scheme keys, so the accent matches
whatever ReSharper and the current colour scheme actually do. The `CLASS_NAME` and
`FUNCTION_DECLARATION` keys remain as a fallback for when the backend has not answered yet.

The shift is computed **relative to the background, not always towards black**: on a light scheme
the colour moves towards black, on a dark one towards white. The settings hold two separate
percentages. Darkening on Darcula would be pointless — the brace would sink into the background.

Prominence comes from a shadow: a copy of the glyph with an offset, drawn **before** the character
itself. `CustomHighlighterRenderer` paints over the background but before the text, so the copy
lands underneath the glyph and adds depth without dirtying the edges. The shadow colour runs from
the background towards grey — grey is darker than a light background and lighter than a dark one,
so one setting works in both themes. The X and Y offsets are separate: `X=1, Y=0` gives faux bold
instead of depth.

The shadow is drawn under the phantom brace too — otherwise it would be invisible in K&R code,
where what you see on screen is the phantom, not the real brace.

When a block is longer than the threshold, a label appears after the closing brace —
`class CrateShelf`, `fun CollectShards`. It is an inline inlay right after the `}`, in
italics, in the brace colour pushed towards grey. The area to the right of a closing brace is
usually empty, so nothing shifts.

Lambdas count as functions. They have no name of their own, so the colour and the label come from
the nearest meaningful identifier: first the unclosed call (`items.Select(y => {` → `Select`), and
only when there is no call, the assignment target (`Action handler = () => {` → `handler`).

"Function" covers more than a method, and each sub-kind says what it is in its own word, with its
own checkbox under **Which function blocks**:

| Label | What it is |
| --- | --- |
| `fun Update` | an ordinary method or a local function |
| `op +`, `op ==`, `op int` | an operator overload or a conversion — the word, then the operator exactly as the source writes it. Shares the methods checkbox: an operator is a method, and `fun +` would have read as a method called `+` |
| `ctor`, `static ctor`, `dtor` | a constructor, a static constructor, a destructor |
| `prop Name` | a property's own braces, and `this` for an indexer |
| `get`, `set`, `init` | an accessor body inside a property |
| `λ Select` | a lambda or an anonymous delegate |
| `ns` | a `namespace` |

Ownership is worked out without a parser: the scanner keeps a stack of open `{`, and a closing
brace recognizes its block by popping the stack. The kind of block is read from the header —
either a type keyword, or "ends with `)` and the first word is not a control keyword". The header
is looked for on the current line, and when the `{` sits on its own line (code that is already
Allman), on the previous one. A declaration wrapped over several lines is read whole, including a
`where` clause or a base-type list broken onto lines of its own.

What deliberately passes by: initializers `new Foo() { ... }`, and every control construct — `if`,
`for`, `switch`, `using`, `lock`, `try` and the rest. Constraints are cut off before
classification, otherwise `void Bind<T>(T v) where T : class {` would pass for a type.

### What a closing brace can say

Up to four things, left to right, each with its own switch and its own length threshold:

- `[3]` — which of its container's type/namespace siblings this is. Always drawn in front of the
  declaration; repeated after the `}` only once the block is long enough that the opening line has
  scrolled away.
- `nest` — the block sits inside another of its own kind. A nested declaration is the hardest to
  find by scrolling, so a nested block may be named whatever its length.
- `class Foo` — the construct word and the name. The word takes the editor's own keyword colour
  and the name its own accent colour, so a label never paints `class` in the colour of a class
  name.
- `↑: 920  Δ: 143` — the block is declared on line 920 and its `}` is 143 lines below it. The
  two numbers are derived from one another, so they always add up to the line you are looking at
  and can be checked at a glance. The **declaration's** line, not the `{`'s: a wrapped signature
  puts several lines between the two, and every length in the plugin is measured from the
  declaration. This one is end-only — standing on line 920 you can already see the block starts
  there — and it reads on its own, so it is drawn even on a block that is not named. Its lengths
  are set per kind (types, functions, properties, namespaces), and for types and namespaces it can
  be restricted to files that hold more than one of them.

### The label is a button

Ctrl-click an end-of-block label — Cmd-click on macOS — and the editor goes to the declaration
that brace closes, not to the `{`: a wrapped signature puts several lines between the two, and
the declaration is the line you were looking for. The caret lands on the declaration's first
real character, past the indent, rather than at the start of the line — the line start is inside
the whitespace, several tab stops from the word you were sent to read. The pointer turns into a hand over a label
that leads somewhere, and only while the modifier is held, the same way Ctrl-hover behaves
everywhere else in the IDE. The jump is recorded in the Back history, so `Ctrl+Alt+Left` returns
to the brace you clicked. A plain click does nothing, so dragging a selection across a label
still works.

Which modifiers the click must carry — Ctrl/Cmd, Alt, Shift, or none at all for a plain click —
is set under **Clicking the end-of-block label**, and they are matched exactly rather than as a
minimum: with Ctrl alone ticked, a Ctrl+Alt-click is somebody else's gesture and is left to them.

Where the view lands is a mirror about the middle of the screen. The declaration appears as far
from the top as the label you clicked stood from the bottom, so clicking a label low on screen
brings the declaration high on it and the block's body fills the view instead of flying past;
click in the very middle and nothing moves, because the middle mirrors onto itself. A declaration
already on screen with two lines to spare at both edges does not scroll at all — the mirror would
still have shoved a short block across the screen, and moving the text under a reader who can
already see both ends of the block is the one thing this avoids.

It is measured in pixels rather than lines, because a phantom line is a block inlay: two document
lines twenty lines apart are not twenty line heights apart on screen. The arithmetic is in
`geometry/LabelNavigationGeometry.kt`, with no `Editor` in it and a test per rule.

## Edge whitespace

A third marker, independent of the braces entirely: `whitespaces (N)` in reddish grey, drawn at
the very start and the very end of the file.

At the start it flags blank lines before the first real character. At the end it flags
anything after the last one -- extra blank lines, trailing spaces and tabs, or even a single
trailing newline. A file is expected to end on its last real character, not on whitespace of
any kind, so a file most editors consider "clean" (one trailing newline) still gets a marker
here on purpose.

`N` is a count of lines when the whitespace spans a full line break, and a count of characters
when it is a run of spaces or tabs with no line break in it. Both edges, the minimum count
before the marker shows, and how far its colour moves from a plain warning red towards grey, are
settings of their own -- see "Edge whitespace" on the settings page. The rules live in
`scan/EdgeWhitespacePolicy.kt`, with no `Editor` in them and a test per rule.

## Member spacing

A fourth marker: a red wavy line under a member's own closing brace where the next member starts
right after it with no blank line in between. It runs the full width of the editor's viewport,
not just under the text on that line, so it reads as a divider rather than a typo squiggle under
one word. "Member" means a type, a namespace, a function or a property's own block -- not a
lambda, which is an expression rather than a declaration, and not a property's `get`/`set`
accessors, which belong to that property rather than to each other.

The line only appears when at least one of the two members runs more than one line: two packed
one-line properties, or two packed one-line methods, read fine with nothing between them and are
never flagged. The blank line can sit anywhere in the gap between the two -- including among
comments or attributes above the second declaration -- but a comment-only line on its own, with
no genuinely blank line next to it, still counts as no blank line at all.

Which kinds count (types, namespaces, functions, properties) and how far the line's colour moves
from a plain warning red towards grey are settings of their own -- see "Member spacing" on the
settings page. The gap rule lives in `scan/MemberSpacingPolicy.kt`, with no `Editor` in it and a
test per rule, built entirely from the existing brace list already produced for the accent
mechanic -- no scanner change was needed. The line itself is drawn by
`MemberSpacingUnderlineRenderer`: reaching past the line's own text to the edge of the viewport
is outside what a `RangeHighlighter`'s own effect can do, so it paints the wave by hand, the same
way `BraceShadowRenderer` paints a brace's shadow.

## Four independent switches

The settings page has one master checkbox and four mechanics under it, each with its own switch:

- **Move braces down** — the phantom lines plus the dimming of the text they stand in for.
- **Accent braces** — the colour, the shadow and the end-of-block label.
- **Edge whitespace** — the `whitespaces (N)` marker at the start and end of the file.
- **Member spacing** — the red wavy line where two adjacent members have no blank line between them.

Any of the four can be turned off on its own. With the moves off, the file keeps its K&R shape
on screen and the braces of types and functions are still accented in place; with the accent off,
the moves work with the ordinary editor colours; edge whitespace and member spacing each answer
to neither. The master checkbox at the top turns all four off, and then the editor shows the
file exactly as it is on disk.

## How it works

The original text **stays where it is** — it is simply dimmed, and the phantom is drawn next to
it. Two platform primitives, both purely visual:

1. **RangeHighlighter** on `editor.markupModel`, layer `HighlighterLayer.LAST + 100`,
   `TextAttributes` carrying only a `foregroundColor`. It paints the real `{` (and
   `else`/`catch`/`finally` in full Allman) in the muted colour of parameter hints.
2. **Block inlay** — `InlayModel.addBlockElement(lineEnd, relatesToPrecedingText = true, showAbove = false, ...)`.
   It draws the phantom line under its owner line, at that line's indent. The caret does not walk
   into the inlay and skips to the real text, just like with parameter hints.

The phantom text is always a contiguous slice of the document, and `PhantomLine` keeps its
`sourceOffset`. That means the highlighting can be asked of the editor itself: phantom character
`i` lives at `sourceOffset + i` in the document. Colours are collected from two sources — the
lexer (`EditorEx.getHighlighter()`) and the document markup (`DocumentMarkupModel`) — because in
Rider the C# highlighting arrives from the ReSharper backend as markup, and the lexer alone is
not enough.

The phantom indent is expressed in **levels**, not spaces: only the editor knows how wide a level
is, and `Graphics.drawString` does not expand a tab inside a string — the indent would simply
disappear.

Folding is deliberately not used: it clashed with ReSharper's fold regions on method bodies
(`createFoldRegion` returned `null` and the move silently did not happen) and it pushed the caret
out of the collapsed region while typing.

The document is never touched. Copying, search, the compiler, git and ReSharper all see the real
K&R text, so diffs stay clean.

For a multi-line construct the indent is taken not from the line holding the brace, but from the
line the construct started on (the scanner tracks the depth of `(` and `[`):

```csharp
private static void CollectShards(
    int crateId,
    bool isShelfEmpty) {   ← the brace physically sits here
{                               ← the phantom lands under `private`, not under `bool`
```

## Languages

A dialect settles two things: how string literals are parsed, and -- for the languages that
need it -- how a declaration is recognised.

| Dialect | Extensions | Literals |
|---|---|---|
| `CSHARP` | `cs csx` | `@"verbatim"`, `"""raw"""`, `$"{interp}"` with nested quotes |
| `C_FAMILY` | `c cpp h hpp m mm metal hlsl glsl shader compute cginc usf` | `R"delim(raw)delim"`, `1'000'000` |
| `JVM` | `java kt kts scala groovy gradle dart` | `"""` text blocks |
| `SWIFT` | `swift` | `"""` multi-line, `#"raw"#`, and no character literal at all |
| `RUST` | `rs` | `r#"raw"#`, `b"bytes"`, and lifetimes (`&'a str`) |
| `WEB` | `js jsx ts tsx go php` | `` `templates ${...}` `` |
| `GENERIC` | everything else (`json css scss sql proto zig`…) | `"..."`, `'...'`, `/* */`, `//` |

None of this is decoration. Java, Kotlin and Swift all have `"""` blocks, and without parsing
them the scanner runs off inside a multi-line string -- the same Java file yields 1 hit under
`JVM` and 2 under `GENERIC`. Rust's lifetime is worse than a wrong colour: read as a character
literal, `&'a str` swallows the rest of the declaration, and `impl` and `fn` blocks disappear
from the file entirely.

### How declarations are recognised

Most of these languages write the return type first and leave the kind of the thing implied by
the shape: `void Foo(int x)`. Swift and Rust lead with the kind instead -- `func`, `struct`,
`impl`, `mod` -- which is a different grammar rather than a dialect of the same one, so they are
classified by their own rules.

| Language | Types | Functions | Other |
|---|---|---|---|
| Swift | `class struct enum protocol extension actor` | `func`, `init` → `ctor`, `deinit` → `dtor`, `subscript` → `prop` | `var`/`let` bodies → `prop`; `get set willSet didSet`; trailing closures |
| Rust | `struct enum union trait impl` | `fn` | `mod` → a namespace; `impl Display for Point` is named after `Point` |

The label is the concept rather than the source word, so Swift's `init` prints `ctor` exactly as
a C# constructor does: a reader who knows the plugin reads the same label in every language.

`C_FAMILY` covers C, C++, Objective-C, Objective-C++ and the shading languages in one dialect,
and that is deliberate rather than a shortcut: a `.h` is C, C++ or Objective-C with nothing in
its name to say which, and a `.mm` is genuinely both at once. Their literals are identical --
Objective-C's `@"..."` is an `@` in front of an ordinary C string -- so only the declaration
shapes differ, and those are told apart by their own syntax instead of by the file's name.

Objective-C therefore adds the two shapes C++ has none of: the `- (void)doThing:(int)x` method
header, and the `^{ }` block literal, which borrows the name of the argument it is passed as --
`animations:^{` reads as `animations`. Neither is valid C or C++, so the C and C++ files sharing
the dialect are unaffected.

The extension list is edited in Settings → Editor → Allman View. The same page has an "All text
files" checkbox, which ignores the list and runs the plugin everywhere.

## Layout

| File | What it does |
|---|---|
| `scan/BraceScanner.kt` | Lexer plus the search for places to move. **Zero IntelliJ dependencies**, covered by tests. |
| `AllmanController.kt` | One per editor: rebuilds the highlighting and the inlays on a timer. |
| `AllmanService.kt` | Subscribes to editor creation, `refreshAll()`. |
| `PhantomLineRenderer.kt` | Draws the phantom lines. |
| `EditorColorSampler.kt` | Pulls the editor's real highlighting for a slice of the document. |
| `BraceAccentStyle.kt` | Works out the brace, shadow and label colours from the name colour and the scheme background. |
| `BraceShadowRenderer.kt` | Draws the shadow under a real brace, before the glyph itself. |
| `BlockLabelRenderer.kt` | The end-of-block label after a `}` of a long block. |
| `AllmanSettings.kt` / `AllmanConfigurable.kt` | Settings plus the panel in Settings → Editor → Allman View. |

## Building

The wrapper is in the repository, so Gradle does not need to be installed separately.

```
gradlew.bat test        # scanner tests
gradlew.bat runIde      # launches a sandbox IDE with the plugin
gradlew.bat buildPlugin # build/distributions/allman-view-1.1.0.zip
```

The versions are pinned the way they are because:

- **Gradle 9.7.0** — IntelliJ Platform Gradle Plugin 2.x needs at least 9.0, and on JDK 25 Gradle
  can only start from 9.1. On 8.x the build fails with the cryptic
  `What went wrong: 25.0.1`.
- **Kotlin 2.4.20** — fully supports the Gradle 7.6.3–9.7.0 range.
- **jvmToolchain(21)** — the 2026.x platform runs on JBR 21, so the bytecode has to be 21
  regardless of which JDK runs Gradle itself. If JDK 21 is not installed,
  `foojay-resolver-convention` in `settings.gradle.kts` downloads it automatically.

From IDEA: File → Open → the project folder. The Gradle JVM can be any 17+.

The target IDE is set in `gradle.properties`:

```properties
platformType=RD          # RD = Rider, IC = IntelliJ IDEA Community (a much smaller download)
platformVersion=2026.2.2
```

Every API in use is a platform API, so you can build and check against `IC` and install the
finished zip into Rider.

## Known limitations

- The real `{` stays visible, just grey. This is deliberate: it shows where the text physically
  is, and it breaks neither the caret nor anyone else's folding. Turn it off with the "Dim the
  original text" checkbox (only the phantom then remains, on top of the ordinary brace).
- A phantom line gets no number in the gutter and takes no part in indent guides — the vertical
  indent lines will break.
- The caret cannot be placed in a phantom line: it is not text.
- The scanner recomputes the whole document on a timer (200 ms after an edit). On files of tens of
  thousands of lines this is worth measuring and, if needed, making incremental (caching the lexer
  state at the start of each line).
- The `} while (x);` of a `do` loop is deliberately not split.
