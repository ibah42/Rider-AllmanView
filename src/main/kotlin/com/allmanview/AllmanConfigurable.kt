package com.allmanview

import com.allmanview.geometry.LabelNavigationGeometry
import com.allmanview.scan.Dialects
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.selected

/**
 * The panel has four independent sections, each behind its own master checkbox.
 *
 * "Move braces down" owns the phantom lines and the dimming of the text they stand in for.
 * "Sibling numbering" owns the `[1]`, `[2]`, ... marker on a container's type/namespace
 * children; it has nothing to number while the child's own kind is not being recognised below,
 * under "Accent braces". "Block span" owns the `{: 920  Δ: 143` footnote a very long block's
 * label ends with, whatever kind of block it is. "Accent braces" owns the colour, the weight,
 * the shadow and the end-of-block label of the three kinds of block that get one -- types,
 * functions and namespaces -- plus the nested marker shared by all three. Turning one master
 * off leaves the others running; the topmost checkbox turns off the plugin as a whole.
 *
 * Every subsection below a master checkbox is a `rowsRange { ... }.enabledIf(...)`: a row
 * disabled by an ancestor stays disabled regardless of its own predicate, so the cascade —
 * plugin → mechanic → per-kind toggle → the one specific checkbox a spinner belongs to — greys
 * out correctly at every level.
 *
 * Two rules keep the panel from reading as one endless column, which is what it had become.
 * A **kind** of block — Types, Functions, Namespaces — is a nested `group(title)`, so its
 * border says where it starts and ends; a **subsection** inside one is a `separator()` and a
 * bold heading, which is a line rather than just a change of weight. And settings that are
 * read together share a row: a spinner does not deserve a line of its own merely because it is
 * a spinner. Nobody sets a light percentage without glancing at the dark one, and nobody thinks
 * about a shadow's strength apart from its offset — those go side by side, with the
 * `row("label:")` prefix naming the lot of them in the order they appear.
 */
class AllmanConfigurable : BoundConfigurable("Allman View") {

    override fun createPanel(): DialogPanel {
        val config = AllmanSettings.getInstance().state
        return panel {
            lateinit var pluginEnabled: Cell<JBCheckBox>
            row {
                pluginEnabled = checkBox("Allman View enabled")
                    .bindSelected(config::enabled)
                    .comment(
                        "The master switch for everything below. With it off the editor " +
                            "shows the file exactly as it is on disk.",
                    )
            }

            group("Sibling numbering") {
                lateinit var siblingNumbering: Cell<JBCheckBox>
                row {
                    siblingNumbering = checkBox("Number a container's type and namespace children")
                        .bindSelected(config::siblingNumberingEnabled)
                        .comment(
                            "[1], [2], ... before each class, struct, interface, enum, " +
                                "record or namespace directly inside the same file, " +
                                "namespace, type or function, once that container has two " +
                                "or more of them. A lone child, or a function, is never " +
                                "numbered. Needs the child's own kind turned on below, " +
                                "under \"Accent braces\".",
                        )
                }
                rowsRange {
                    row("Repeat \"[N]\" after the closing brace from this block length, lines:") {
                        spinner(BLOCK_LINES_RANGE, BLOCK_LINES_STEP)
                            .bindIntValue(config::siblingNumberingEndOfBlockMinLines)
                            .comment(
                                "The [N] in front of the declaration is always drawn: the " +
                                    "declaration is right next to it and explains it. The copy " +
                                    "after the closing brace only pays for itself once the " +
                                    "opening line has scrolled out of view, so it has a length " +
                                    "of its own -- normally shorter than the per-kind label " +
                                    "length under \"Accent braces\", because \"which one of " +
                                    "the siblings is this\" becomes worth answering sooner " +
                                    "than \"what was this block called\". Whenever the number " +
                                    "is repeated the block is named as well, so a closing brace " +
                                    "never reads \"} [3]\" with nothing after it to say what " +
                                    "the 3 counts.",
                            )
                    }
                    row("\"[N]\" towards grey, %:") {
                        spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::siblingGreyPercent)
                            .comment("Based on the editor's keyword colour, like the other markers.")
                    }
                }.enabledIf(siblingNumbering.selected)
            }.enabledIf(pluginEnabled.selected)

            group("Block span") {
                lateinit var blockSpanMarker: Cell<JBCheckBox>
                row {
                    blockSpanMarker = checkBox("Report how far back a very long block started")
                        .bindSelected(config::blockSpanMarkerEnabled)
                        .comment(
                            "}  class Foo  ↑: 920  Δ: 143  --  the block is declared " +
                                "on line 920 and its } is 143 lines below it, so the two " +
                                "numbers always add up to the line you are looking at. The " +
                                "declaration's line, not the {'s: a wrapped signature puts " +
                                "several lines between the two, and every length in this " +
                                "panel is measured from the declaration. This is the one " +
                                "marker with nothing on the declaration line -- standing on " +
                                "line 920 you can already see the block starts there; it is " +
                                "at the far end, after a long scroll, that the question is " +
                                "worth answering. Drawn last, after the block's name.",
                        )
                }
                rowsRange {
                    // Four lengths rather than one: "long" is not the same number for a
                    // property as for a class, and a single threshold would be wrong for
                    // three of the four whatever it was set to.
                    row {
                        label("Types").bold()
                    }
                    lateinit var spanTypes: Cell<JBCheckBox>
                    row {
                        spanTypes = checkBox("Report a type's span")
                            .bindSelected(config::blockSpanTypes)
                            .comment("class, struct, interface, enum, record.")
                    }
                    rowsRange {
                        row("From this block length, lines:") {
                            spinner(BLOCK_LINES_RANGE, BLOCK_LINES_STEP)
                                .bindIntValue(config::blockSpanTypeMinLines)
                        }
                        row {
                            checkBox("Only in a file that holds more than one type")
                                .bindSelected(config::blockSpanTypesOnlyWithSeveralTypes)
                                .comment(
                                    "Counted over the whole file, nesting included. The span " +
                                        "answers \"which of these, and how far back did it " +
                                        "begin\", which is a question only where something " +
                                        "could be confused with something else. One class in " +
                                        "a file has no competition; fourteen of them, hundreds " +
                                        "of lines each, is where the marker pays for itself.",
                                )
                        }
                    }.enabledIf(spanTypes.selected)

                    row {
                        label("Functions").bold()
                    }
                    lateinit var spanFunctions: Cell<JBCheckBox>
                    row {
                        spanFunctions = checkBox("Report a function's span")
                            .bindSelected(config::blockSpanFunctions)
                            .comment("Methods, constructors, destructors and lambdas.")
                    }
                    rowsRange {
                        row("From this block length, lines:") {
                            spinner(BLOCK_LINES_RANGE, BLOCK_LINES_STEP)
                                .bindIntValue(config::blockSpanFunctionMinLines)
                        }
                    }.enabledIf(spanFunctions.selected)

                    row {
                        label("Properties").bold()
                    }
                    lateinit var spanProperties: Cell<JBCheckBox>
                    row {
                        spanProperties = checkBox("Report a property's span")
                            .bindSelected(config::blockSpanProperties)
                            .comment(
                                "A property's own block and its get, set and init accessors. " +
                                    "Separate from functions because a forty-line property is " +
                                    "remarkable and a forty-line method is not.",
                            )
                    }
                    rowsRange {
                        row("From this block length, lines:") {
                            spinner(BLOCK_LINES_RANGE, BLOCK_LINES_STEP)
                                .bindIntValue(config::blockSpanPropertyMinLines)
                        }
                    }.enabledIf(spanProperties.selected)

                    row {
                        label("Namespaces").bold()
                    }
                    lateinit var spanNamespaces: Cell<JBCheckBox>
                    row {
                        spanNamespaces = checkBox("Report a namespace's span")
                            .bindSelected(config::blockSpanNamespaces)
                    }
                    rowsRange {
                        row("From this block length, lines:") {
                            spinner(BLOCK_LINES_RANGE, BLOCK_LINES_STEP)
                                .bindIntValue(config::blockSpanNamespaceMinLines)
                        }
                        row {
                            checkBox("Only in a file that holds more than one namespace")
                                .bindSelected(config::blockSpanNamespacesOnlyWithSeveralNamespaces)
                                .comment(
                                    "The same rule as under Types, counting namespaces. A " +
                                        "namespace alone in its file spans the file, so its " +
                                        "span would only restate the file's length.",
                                )
                        }
                    }.enabledIf(spanNamespaces.selected)

                    row("Span towards grey, %:") {
                        spinner(PERCENT_RANGE, PERCENT_STEP)
                            .bindIntValue(config::blockSpanMarkerGreyPercent)
                            .comment(
                                "Based on the editor's line-number colour, not the keyword " +
                                    "colour the other markers use: this one reports where you " +
                                    "are in the file, like the gutter it sits opposite, rather " +
                                    "than naming a language construct.",
                            )
                    }
                }.enabledIf(blockSpanMarker.selected)
            }.enabledIf(pluginEnabled.selected)

            // Every group below greys out with the master switch, so it is obvious what it controls.
            group("Move braces down") {
                lateinit var moveBraces: Cell<JBCheckBox>
                row {
                    moveBraces = checkBox("Draw the phantom lines")
                        .bindSelected(config::moveBraces)
                        .comment("if (x) {  →  the { is shown on a line of its own underneath.")
                }

                separator()
                row {
                    label("Which lines to split").bold()
                }
                rowsRange {
                    row {
                        checkBox("Full Allman: split } else { into three lines")
                            .bindSelected(config::fullAllman)
                    }
                    row {
                        checkBox("Split single-line if / for / foreach / while / using / lock")
                            .bindSelected(config::splitStatements)
                            .comment("if (x) return;  →  if (x) ⏎ return;")
                    }
                    row {
                        checkBox("Expand a single-line braced block")
                            .bindSelected(config::expandInlineBlocks)
                            .comment("if (x) { Foo(); }  →  if (x) ⏎ { ⏎ Foo(); ⏎ }")
                    }
                }.enabledIf(moveBraces.selected)

                separator()
                row {
                    label("Dimming").bold()
                }
                rowsRange {
                    lateinit var dimOriginal: Cell<JBCheckBox>
                    row {
                        dimOriginal = checkBox("Dim the original text")
                            .bindSelected(config::dimOriginal)
                            .comment("The real text stays where it is and is simply muted.")
                    }
                    rowsRange {
                        row {
                            checkBox("Use the IDE hint colour")
                                .bindSelected(config::dimUseHintColor)
                                .comment("Turn off to set the dimming strength by hand.")
                        }
                        row("Dim towards background, %:") {
                            spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::dimPercent)
                        }
                    }.enabledIf(dimOriginal.selected)
                }.enabledIf(moveBraces.selected)
            }.enabledIf(pluginEnabled.selected)

            group("Accent braces") {
                lateinit var accentBraces: Cell<JBCheckBox>
                row {
                    accentBraces = checkBox("Colour the braces of types and functions")
                        .bindSelected(config::accentBraces)
                        .comment(
                            "Colour, shadow and the end-of-block label. Works on its own, " +
                                "whether or not the braces are moved down.",
                        )
                }

                // Marking a block as nested is not a per-kind setting -- it applies to every
                // kind below -- so it sits above them all, under the same master checkbox.

                separator()
                row {
                    label("Nested blocks").bold()
                }
                rowsRange {
                    lateinit var nestedMarkerEnabled: Cell<JBCheckBox>
                    row {
                        nestedMarkerEnabled = checkBox("Mark a block nested inside one of its own kind")
                            .bindSelected(config::nestedMarkerEnabled)
                            .comment(
                                "\"nest\" before its declaration, always. At the start of its " +
                                    "end-of-block label only once the block reaches the " +
                                    "length below.",
                            )
                    }
                    row("Repeat \"nest\" after the closing brace from this block length, lines:") {
                        spinner(BLOCK_LINES_RANGE, BLOCK_LINES_STEP)
                            .bindIntValue(config::nestedMarkerEndOfBlockMinLines)
                            .comment(
                                "The word in front of the declaration is always drawn: the " +
                                    "declaration is right next to it and explains it. The copy " +
                                    "after the closing brace only pays for itself once the " +
                                    "opening line has scrolled out of view, so it has a length " +
                                    "of its own, the same way \"[N]\" does under \"Sibling " +
                                    "numbering\" below. Also the length a nested block is " +
                                    "named at -- see the checkbox below, which reads this " +
                                    "number even with \"nest\" switched off above.",
                            )
                    }
                    row {
                        checkBox("Name a nested block once it reaches the length above")
                            .bindSelected(config::nestedLabelAlways)
                            .comment(
                                "Ahead of the per-kind minimum below. Separate from the word " +
                                    "itself: a nested block's own declaration is the hardest " +
                                    "to find by scrolling, with or without \"nest\" in front.",
                            )
                    }
                    row("\"nest\" marker towards grey, %:") {
                        spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::nestedLabelGreyPercent)
                            .comment("Based on the editor's keyword colour, not the block's own accent.")
                    }.enabledIf(nestedMarkerEnabled.selected)
                }.enabledIf(accentBraces.selected)

                group("Clicking the end-of-block label") {
                    lateinit var labelNavigation: Cell<JBCheckBox>
                    row {
                        labelNavigation = checkBox("Go to the declaration the label names")
                            .bindSelected(config::labelNavigationEnabled)
                            .comment(
                                "The label after a closing brace becomes a button: click it " +
                                    "and the editor goes to the declaration that brace closes. " +
                                    "The jump is recorded in the Back history, so Ctrl+Alt+Left " +
                                    "returns to the brace.",
                            )
                    }
                    rowsRange {
                        row("Hold while clicking:") {
                            checkBox("Ctrl / Cmd").bindSelected(config::labelNavigationCtrl)
                            checkBox("Alt").bindSelected(config::labelNavigationAlt)
                            checkBox("Shift").bindSelected(config::labelNavigationShift)
                        }.rowComment(
                            "Matched exactly rather than \"at least\": with Ctrl alone ticked, " +
                                "a Ctrl+Alt-click is somebody else's gesture and is left to " +
                                "them. Tick none of the three and a plain click navigates, " +
                                "which makes the label behave like a link at the cost of the " +
                                "odd jump while selecting text. Cmd stands in for Ctrl on " +
                                "macOS, where a Control-click is a right-click.",
                        )
                        row("Declaration off screen lands on line:") {
                            spinner(NAVIGATION_LINES_RANGE, NAVIGATION_LINES_STEP)
                                .bindIntValue(config::labelNavigationLandingLines)
                        }.rowComment(
                            "Counted from the top edge of the editor, 0 being the top line " +
                                "itself. Near the start of the file it lands higher, as there " +
                                "is nothing above it to scroll to.",
                        )
                        row("Visible declaration needs at least:") {
                            spinner(NAVIGATION_LINES_RANGE, NAVIGATION_LINES_STEP)
                                .bindIntValue(config::labelNavigationMinimumTopLines)
                            label("lines above it")
                        }.rowComment(
                            "A declaration already on screen but closer to the top than this " +
                                "is pulled down just far enough; one with that much room does " +
                                "not scroll at all. Cannot exceed the landing line -- it is " +
                                "lowered to it on apply.",
                        )
                    }.enabledIf(labelNavigation.selected)
                }.enabledIf(accentBraces.selected)

                group("Types") {
                    lateinit var accentTypes: Cell<JBCheckBox>
                    row {
                        accentTypes = checkBox("Recognise class, struct, interface, enum, record")
                            .bindSelected(config::accentTypes)
                    }

                    rowsRange {
                        separator()
                        row {
                            label("Braces").bold()
                        }
                        lateinit var typeBraces: Cell<JBCheckBox>
                        row {
                            typeBraces = checkBox("Colour the braces")
                                .bindSelected(config::typeBraces)
                                .comment(
                                    "The { and } themselves -- colour, weight and "  +
                                        "shadow. Independent of the label below: "  +
                                        "either can be on without the other.",
                                )
                        }
                        rowsRange {
                            // One row, not four plus two headings: nobody picks a light
                            // percentage without glancing at the dark one, and bold and
                            // shadow are the same decision about how loud a brace should be.
                            lateinit var typeShadow: Cell<JBCheckBox>
                            row("Push from the background, % light / dark:") {
                                spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::typeLightPercent)
                                spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::typeDarkPercent)
                                checkBox("Bold").bindSelected(config::typeBold)
                                typeShadow = checkBox("Shadow").bindSelected(config::typeShadow)
                            }.rowComment(
                                "Towards black on a light scheme, towards white on a dark " +
                                    "one. The shift is measured from the background rather " +
                                    "than always towards black: darkening a brace on Darcula " +
                                    "would sink it into the background instead of lifting it " +
                                    "out.",
                            )
                            rowsRange {
                                row("Shadow strength %, offset X, Y:") {
                                    spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::typeShadowPercent)
                                    spinner(SHADOW_OFFSET_RANGE, SHADOW_OFFSET_STEP).bindIntValue(config::typeShadowOffsetX)
                                    spinner(SHADOW_OFFSET_RANGE, SHADOW_OFFSET_STEP).bindIntValue(config::typeShadowOffsetY)
                                }.rowComment(
                                    "Strength 0 hides the shadow and 100 makes it solid grey. " +
                                        "X=1, Y=0 gives faux bold instead of depth.",
                                )
                            }.enabledIf(typeShadow.selected)
                        }.enabledIf(typeBraces.selected)

                        separator()
                        row {
                            label("End-of-block label").bold()
                        }
                        lateinit var typeLabel: Cell<JBCheckBox>
                        row {
                            typeLabel = checkBox("Label the end of the block")
                                .bindSelected(config::typeLabel)
                                .comment("}  class CrateShelf")
                        }
                        rowsRange {
                            row("From this block length in lines, towards grey, %:") {
                                spinner(BLOCK_LINES_RANGE, BLOCK_LINES_STEP)
                                    .bindIntValue(config::typeLabelMinLines)
                                spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::typeLabelGreyPercent)
                            }.rowComment(
                                "A shorter block is still named when it is nested, or when " +
                                    "its [N] is repeated after the closing brace -- both of " +
                                    "those rules sit above this one.",
                            )
                        }.enabledIf(typeLabel.selected)
                    }.enabledIf(accentTypes.selected)
                }.enabledIf(accentBraces.selected)

                group("Functions") {
                    lateinit var accentFunctions: Cell<JBCheckBox>
                    row {
                        accentFunctions = checkBox("Recognise methods, properties and lambdas")
                            .bindSelected(config::accentFunctions)
                            .comment(
                                "A lambda has no name of its own, so the colour and the label " +
                                    "come from the method it is passed to, or from the " +
                                    "assignment target.",
                            )
                    }

                    row {
                        label("Which function blocks").bold()
                    }
                    rowsRange {
                        row {
                            checkBox("Methods and operators").bindSelected(config::accentMethods)
                                .comment(
                                    "fun Update, and an operator or conversion under its own " +
                                        "label: op +, op ==, op int. One switch, because an " +
                                        "operator is a method -- only \"fun +\" would have " +
                                        "read as a method called +.",
                                )
                        }
                        row {
                            checkBox("Constructors and destructors")
                                .bindSelected(config::accentConstructors)
                                .comment("ctor, static ctor, dtor")
                        }
                        row {
                            checkBox("Properties").bindSelected(config::accentProperties)
                                .comment("prop Name -- the property's own braces, not its accessors.")
                        }
                        row {
                            checkBox("Accessors").bindSelected(config::accentAccessors)
                                .comment("get, set, init -- the accessor bodies inside a property.")
                        }
                        lateinit var accentLambdas: Cell<JBCheckBox>
                        row {
                            accentLambdas = checkBox("Lambdas").bindSelected(config::accentLambdas)
                        }
                        rowsRange {
                            row {
                                checkBox("Show the lambda symbol")
                                    .bindSelected(config::lambdaSymbolEnabled)
                                    .comment("\u03bb -- a lambda has no declaration to be a fun of.")
                            }
                            row {
                                checkBox("Show the borrowed name")
                                    .bindSelected(config::lambdaNameEnabled)
                                    .comment(
                                        "The method the lambda is passed to, or the assignment " +
                                            "target. With both off a lambda gets no label at all.",
                                    )
                            }
                        }.enabledIf(accentLambdas.selected)
                    }.enabledIf(accentFunctions.selected)

                    rowsRange {
                        separator()
                        row {
                            label("Braces").bold()
                        }
                        lateinit var functionBraces: Cell<JBCheckBox>
                        row {
                            functionBraces = checkBox("Colour the braces")
                                .bindSelected(config::functionBraces)
                                .comment(
                                    "The { and } themselves -- colour, weight and "  +
                                        "shadow. Independent of the label below: "  +
                                        "either can be on without the other.",
                                )
                        }
                        rowsRange {
                            // One row, not four plus two headings: nobody picks a light
                            // percentage without glancing at the dark one, and bold and
                            // shadow are the same decision about how loud a brace should be.
                            lateinit var functionShadow: Cell<JBCheckBox>
                            row("Push from the background, % light / dark:") {
                                spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::functionLightPercent)
                                spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::functionDarkPercent)
                                checkBox("Bold").bindSelected(config::functionBold)
                                functionShadow = checkBox("Shadow").bindSelected(config::functionShadow)
                            }.rowComment(
                                "Towards black on a light scheme, towards white on a dark " +
                                    "one. The shift is measured from the background rather " +
                                    "than always towards black: darkening a brace on Darcula " +
                                    "would sink it into the background instead of lifting it " +
                                    "out.",
                            )
                            rowsRange {
                                row("Shadow strength %, offset X, Y:") {
                                    spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::functionShadowPercent)
                                    spinner(SHADOW_OFFSET_RANGE, SHADOW_OFFSET_STEP).bindIntValue(config::functionShadowOffsetX)
                                    spinner(SHADOW_OFFSET_RANGE, SHADOW_OFFSET_STEP).bindIntValue(config::functionShadowOffsetY)
                                }.rowComment(
                                    "Strength 0 hides the shadow and 100 makes it solid grey. " +
                                        "X=1, Y=0 gives faux bold instead of depth.",
                                )
                            }.enabledIf(functionShadow.selected)
                        }.enabledIf(functionBraces.selected)

                        separator()
                        row {
                            label("End-of-block label").bold()
                        }
                        lateinit var functionLabel: Cell<JBCheckBox>
                        row {
                            functionLabel = checkBox("Label the end of the block")
                                .bindSelected(config::functionLabel)
                                .comment("}  fun CollectShards")
                        }
                        rowsRange {
                            row("From this block length in lines, towards grey, %:") {
                                spinner(BLOCK_LINES_RANGE, BLOCK_LINES_STEP)
                                    .bindIntValue(config::functionLabelMinLines)
                                spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::functionLabelGreyPercent)
                            }.rowComment(
                                "A shorter block is still named when it is nested, or when " +
                                    "its [N] is repeated after the closing brace -- both of " +
                                    "those rules sit above this one.",
                            )
                        }.enabledIf(functionLabel.selected)
                    }.enabledIf(accentFunctions.selected)
                }.enabledIf(accentBraces.selected)

                group("Namespaces") {
                    lateinit var accentNamespaces: Cell<JBCheckBox>
                    row {
                        accentNamespaces = checkBox("Recognise namespaces")
                            .bindSelected(config::accentNamespaces)
                            .comment(
                                "A namespace has no name in its label and no minimum length: " +
                                    "its closing brace is the one furthest from its declaration, " +
                                    "so it is always labelled.",
                            )
                    }

                    rowsRange {
                        separator()
                        row {
                            label("Braces").bold()
                        }
                        lateinit var namespaceBraces: Cell<JBCheckBox>
                        row {
                            namespaceBraces = checkBox("Colour the braces")
                                .bindSelected(config::namespaceBraces)
                                .comment(
                                    "The { and } themselves -- colour, weight and "  +
                                        "shadow. Independent of the label below: "  +
                                        "either can be on without the other.",
                                )
                        }
                        rowsRange {
                            // One row, not four plus two headings: nobody picks a light
                            // percentage without glancing at the dark one, and bold and
                            // shadow are the same decision about how loud a brace should be.
                            lateinit var namespaceShadow: Cell<JBCheckBox>
                            row("Push from the background, % light / dark:") {
                                spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::namespaceLightPercent)
                                spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::namespaceDarkPercent)
                                checkBox("Bold").bindSelected(config::namespaceBold)
                                namespaceShadow = checkBox("Shadow").bindSelected(config::namespaceShadow)
                            }.rowComment(
                                "Towards black on a light scheme, towards white on a dark " +
                                    "one. A namespace has no name to sample, so the colour " +
                                    "starts from the editor's keyword colour instead.",
                            )
                            rowsRange {
                                row("Shadow strength %, offset X, Y:") {
                                    spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::namespaceShadowPercent)
                                    spinner(SHADOW_OFFSET_RANGE, SHADOW_OFFSET_STEP).bindIntValue(config::namespaceShadowOffsetX)
                                    spinner(SHADOW_OFFSET_RANGE, SHADOW_OFFSET_STEP).bindIntValue(config::namespaceShadowOffsetY)
                                }.rowComment(
                                    "Strength 0 hides the shadow and 100 makes it solid grey. " +
                                        "X=1, Y=0 gives faux bold instead of depth.",
                                )
                            }.enabledIf(namespaceShadow.selected)
                        }.enabledIf(namespaceBraces.selected)

                        separator()
                        row {
                            label("End-of-block label").bold()
                        }
                        lateinit var namespaceLabel: Cell<JBCheckBox>
                        row {
                            namespaceLabel = checkBox("Label the end of the block")
                                .bindSelected(config::namespaceLabel)
                                .comment("}  ns")
                        }
                        row("Label towards grey, %:") {
                            spinner(PERCENT_RANGE, PERCENT_STEP).bindIntValue(config::namespaceLabelGreyPercent)
                        }.enabledIf(namespaceLabel.selected)
                    }.enabledIf(accentNamespaces.selected)
                }.enabledIf(accentBraces.selected)
            }.enabledIf(pluginEnabled.selected)

            group("Edge whitespace") {
                lateinit var edgeWhitespace: Cell<JBCheckBox>
                row {
                    edgeWhitespace = checkBox("Flag whitespace at the start and end of the file")
                        .bindSelected(config::edgeWhitespaceEnabled)
                        .comment(
                            "\"whitespaces (N)\" in reddish grey -- blank lines before the " +
                                "first real character, or anything after the last one: extra " +
                                "blank lines, trailing spaces and tabs, or even a single " +
                                "trailing newline. The file is expected to end on its last " +
                                "real character, not on whitespace of any kind. A third, " +
                                "independent mechanic: it has nothing to do with braces, so " +
                                "it works whether or not \"Move braces down\" or \"Accent " +
                                "braces\" is turned on.",
                        )
                }
                rowsRange {
                    row {
                        checkBox("At the start of the file").bindSelected(config::edgeWhitespaceLeading)
                    }
                    row {
                        checkBox("At the end of the file").bindSelected(config::edgeWhitespaceTrailing)
                    }
                    row("Only once this many lines or characters are found:") {
                        spinner(EDGE_WHITESPACE_MIN_RANGE, EDGE_WHITESPACE_MIN_STEP)
                            .bindIntValue(config::edgeWhitespaceMinCount)
                            .comment(
                                "Counted in lines when the whitespace spans a full line break, " +
                                    "in characters otherwise -- whichever the label ends up " +
                                    "reporting.",
                            )
                    }
                    row("Marker towards grey, %:") {
                        spinner(PERCENT_RANGE, PERCENT_STEP)
                            .bindIntValue(config::edgeWhitespaceGreyPercent)
                            .comment("Mixed from a plain warning red, like every other marker's own base colour.")
                    }
                }.enabledIf(edgeWhitespace.selected)
            }.enabledIf(pluginEnabled.selected)

            group("Member spacing") {
                lateinit var memberSpacing: Cell<JBCheckBox>
                row {
                    memberSpacing = checkBox("Flag members with no blank line between them")
                        .bindSelected(config::memberSpacingEnabled)
                        .comment(
                            "A red underline under the earlier member's closing brace where " +
                                "two adjacent types, namespaces, functions or properties sit " +
                                "with no blank line between them. Flagged only when at least " +
                                "one of the two spans more than one line -- packed one-line " +
                                "properties or one-line methods read fine with nothing " +
                                "between them. A lambda is never a member, and a property's " +
                                "own accessors are parts of that property, not each other's " +
                                "neighbours. A fourth, independent mechanic: it has nothing " +
                                "to do with braces, so it works whether or not \"Move braces " +
                                "down\" or \"Accent braces\" is turned on.",
                        )
                }
                rowsRange {
                    row {
                        checkBox("Types").bindSelected(config::memberSpacingTypes)
                        checkBox("Namespaces").bindSelected(config::memberSpacingNamespaces)
                        checkBox("Functions").bindSelected(config::memberSpacingFunctions)
                        checkBox("Properties").bindSelected(config::memberSpacingProperties)
                    }
                    row("Underline towards grey, %:") {
                        spinner(PERCENT_RANGE, PERCENT_STEP)
                            .bindIntValue(config::memberSpacingGreyPercent)
                            .comment("Mixed from a plain warning red, like every other marker's own base colour.")
                    }
                }.enabledIf(memberSpacing.selected)
            }.enabledIf(pluginEnabled.selected)

            group("Which files") {
                row {
                    checkBox("No formatting markers in files you cannot edit")
                        .bindSelected(config::formattingMarkersSkipForeign)
                        .comment(
                            "Edge whitespace and member spacing are left out of read-only " +
                                "files, decompiled sources, files that are not on the local " +
                                "disk, and Unity packages under Library/PackageCache -- their " +
                                "formatting is somebody else's, and there is nothing to fix. " +
                                "The braces are still drawn.",
                        )
                }
                row {
                    checkBox("All text files")
                        .bindSelected(config::allFiles)
                        .comment("When on, the extension list below is ignored.")
                }
                row("Extensions:") {
                    textArea()
                        .bindText(
                            { config.extensions ?: Dialects.DEFAULT_EXTENSIONS },
                            { config.extensions = it },
                        )
                        .rows(4)
                        .align(AlignX.FILL)
                        .comment(
                            "Separated by comma, space or newline. The dot and the star are optional.",
                        )
                }
                row {
                    button("Restore the default list") {
                        config.extensions = Dialects.DEFAULT_EXTENSIONS
                        reset()
                    }
                }
            }

            row {
                comment(
                    "String literal parsing adapts to the language: C# (@\"\", \"\"\"\"\"\", ${'$'}\"\"), " +
                        "C/C++ (R\"()\", 1'000'000), JVM and Swift (text blocks \"\"\"\"\"\"), " +
                        "JS/TS/Go (`templates`). An unknown extension is parsed by the generic " +
                        "rules, which are enough for any language with curly blocks.",
                )
            }
            row {
                comment(
                    "Nothing is written to the file: a phantom line is a block inlay and the " +
                        "caret never enters it. Copying, search and git see the real text.",
                )
            }
        }
    }

    override fun apply() {
        super.apply()
        limitNavigationMinimum()
        AllmanService.getInstance().refreshAll()
    }

    /**
     * A minimum above the landing line would put a declaration fetched from off screen inside
     * its own "too close to the top" zone, so it is lowered rather than rejected. Validating
     * instead would block the whole page over one spinner the reader may not have touched.
     */
    private fun limitNavigationMinimum() {
        val config = AllmanSettings.getInstance().state
        if (config.labelNavigationMinimumTopLines <= config.labelNavigationLandingLines) {
            return
        }
        config.labelNavigationMinimumTopLines = config.labelNavigationLandingLines
        // Pull the corrected value back into the spinner, or the page would keep showing the
        // number that was just overridden and report itself modified.
        reset()
    }

    private companion object {
        /** Every colour setting is a percentage, and every one of them uses this same spinner. */
        val PERCENT_RANGE = 0..ColorBalance.MAX_PERCENT
        const val PERCENT_STEP = 5

        /** Pixels, and only a few of them: a shadow further out than this is a second glyph. */
        val SHADOW_OFFSET_RANGE = -8..8
        const val SHADOW_OFFSET_STEP = 1

        /** A block length in lines. The ceiling is only there to keep the spinner sane. */
        val BLOCK_LINES_RANGE = 1..2000
        const val BLOCK_LINES_STEP = 5

        /** How many lines or characters of edge whitespace the spinner can ask to wait for. */
        val EDGE_WHITESPACE_MIN_RANGE = 1..50
        const val EDGE_WHITESPACE_MIN_STEP = 1

        /** Lines from the top edge of the editor, for both label navigation spinners. */
        val NAVIGATION_LINES_RANGE = 0..LabelNavigationGeometry.MAX_TOP_LINES
        const val NAVIGATION_LINES_STEP = 1
    }
}

class ToggleAllmanAction : AnAction(), Toggleable {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val isEnabled = AllmanSettings.getInstance().state.enabled
        Toggleable.setSelected(event.presentation, isEnabled)

        if (isEnabled) {
            event.presentation.text = "Allman View (on)"
        } else {
            event.presentation.text = "Allman View (off)"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val config = AllmanSettings.getInstance().state
        config.enabled = !config.enabled
        AllmanService.getInstance().refreshAll()
    }
}
