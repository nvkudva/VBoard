package com.vboard.app.keyboard

import com.vboard.core.keyboard.NumberRow

/** What a key does when tapped. */
sealed interface KeyAction {
    /** Commits [text] (a letter, symbol, or emoji). */
    data class Text(val text: String) : KeyAction

    data object Backspace : KeyAction
    data object Shift : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object Mic : KeyAction
    data object ToSymbols : KeyAction
    data object ToSymbols2 : KeyAction
    data object ToLetters : KeyAction
    data object ToEmoji : KeyAction

    /** Opens the clipboard panel (long-press on `?123`). */
    data object ToClipboard : KeyAction
}

enum class KeyIcon { NONE, BACKSPACE, SHIFT, SHIFT_FILLED, CAPS_LOCK, ENTER, SEARCH, SEND, MIC, EMOJI, GLOBE }

data class Key(
    val action: KeyAction,
    /** Base (lowercase) label; the view uppercases per shift state. */
    val label: String = "",
    /** Small hint character drawn top-right (e.g. the digit on qwerty row 1). */
    val hint: String? = null,
    /** Width in grid units; a plain letter key is 1.0. */
    val widthUnits: Float = 1f,
    /** Long-press candidates; first entry is pre-highlighted. */
    val longPress: List<String> = emptyList(),
    /**
     * Fired instead of a candidate popup when this key is held. Mutually
     * exclusive with [longPress]; used by `?123` to open the clipboard.
     */
    val longPressAction: KeyAction? = null,
    val icon: KeyIcon = KeyIcon.NONE,
    /** Function keys use the alt surface color. */
    val isFunction: Boolean = false,
    /** The accent-filled hero key (mic). */
    val isAccent: Boolean = false,
)

data class KeyRow(
    val keys: List<Key>,
    /** Extra leading/trailing space in grid units (row 2 is indented half a key). */
    val leftPadUnits: Float = 0f,
    val rightPadUnits: Float = 0f,
)

data class KeyboardLayout(
    val rows: List<KeyRow>,
    val totalUnits: Float = 10f,
    /**
     * Which layer this layout draws. The view keys letter-specific behaviour
     * (uppercasing, label size) off this rather than off identity: LETTERS now
     * has two variants and `layout === LETTERS` silently stopped being true for
     * one of them.
     */
    val layer: KeyboardLayer = KeyboardLayer.LETTERS,
)

enum class KeyboardLayer { LETTERS, SYMBOLS, SYMBOLS2, EMOJI, CLIPBOARD }

object KeyboardLayouts {

    private fun letter(c: String, hint: String? = null, longPress: List<String> = emptyList()) =
        Key(KeyAction.Text(c), label = c, hint = hint, longPress = longPress)

    /** LETTERS without the number row: the qwerty row keeps its small digit hints. */
    val LETTERS = lettersLayout(withNumberRow = false)

    /**
     * LETTERS with the number row on top. The qwerty hints are dropped — the
     * digits are right there — but long-press-for-digit still works.
     */
    val LETTERS_NUMBER_ROW = lettersLayout(withNumberRow = true)

    private fun lettersLayout(withNumberRow: Boolean): KeyboardLayout {
        val digitHint: (String) -> String? = { if (withNumberRow) null else it }
        val qwerty = KeyRow(
            listOf(
                letter("q", digitHint("1"), listOf("1")),
                letter("w", digitHint("2"), listOf("2")),
                letter("e", digitHint("3"), listOf("3", "è", "é", "ê", "ë")),
                letter("r", digitHint("4"), listOf("4")),
                letter("t", digitHint("5"), listOf("5")),
                letter("y", digitHint("6"), listOf("6")),
                letter("u", digitHint("7"), listOf("7", "ù", "ú", "û", "ü")),
                letter("i", digitHint("8"), listOf("8", "ì", "í", "î", "ï")),
                letter("o", digitHint("9"), listOf("9", "ò", "ó", "ô", "ö", "õ")),
                letter("p", digitHint("0"), listOf("0")),
            ),
        )
        return KeyboardLayout(
            rows = listOfNotNull(
                numberRow().takeIf { withNumberRow },
                qwerty,
                KeyRow(
                    listOf(
                        letter("a", longPress = listOf("à", "á", "â", "ä", "ã", "å")),
                        letter("s", longPress = listOf("ß")),
                        letter("d"),
                        letter("f"),
                        letter("g"),
                        letter("h"),
                        letter("j"),
                        letter("k"),
                        letter("l"),
                    ),
                    leftPadUnits = 0.5f,
                    rightPadUnits = 0.5f,
                ),
                KeyRow(
                    listOf(
                        Key(KeyAction.Shift, icon = KeyIcon.SHIFT, widthUnits = 1.5f, isFunction = true),
                        letter("z"),
                        letter("x"),
                        letter("c", longPress = listOf("ç")),
                        letter("v"),
                        letter("b"),
                        letter("n", longPress = listOf("ñ")),
                        letter("m"),
                        Key(KeyAction.Backspace, icon = KeyIcon.BACKSPACE, widthUnits = 1.5f, isFunction = true),
                    ),
                ),
                bottomRow(),
            ),
            layer = KeyboardLayer.LETTERS,
        )
    }

    /**
     * The optional digit row. Digit keys use the normal key surface, not the
     * function surface: they type characters like any letter does.
     */
    private fun numberRow(): KeyRow = KeyRow(
        NumberRow.KEYS.map { letter(it.digit, longPress = it.alternates) },
    )

    val SYMBOLS = KeyboardLayout(
        rows = listOf(
            KeyRow((1..9).map { letter(it.toString()) } + listOf(letter("0"))),
            KeyRow(
                listOf("@", "#", "$", "_", "&", "-", "+", "(", ")").map { letter(it) } +
                    listOf(letter("/")),
            ),
            KeyRow(
                listOf(
                    Key(KeyAction.ToSymbols2, label = "=\\<", widthUnits = 1.5f, isFunction = true),
                    letter("*"),
                    letter("\""),
                    letter("'"),
                    letter(":"),
                    letter(";"),
                    letter("!"),
                    letter("?"),
                    Key(KeyAction.Backspace, icon = KeyIcon.BACKSPACE, widthUnits = 1.5f, isFunction = true),
                ),
            ),
            bottomRow(lettersToggle = true),
        ),
        layer = KeyboardLayer.SYMBOLS,
    )

    val SYMBOLS2 = KeyboardLayout(
        rows = listOf(
            KeyRow(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { letter(it) }),
            KeyRow(listOf("£", "€", "¥", "¢", "^", "°", "=", "{", "}", "\\").map { letter(it) }),
            KeyRow(
                listOf(
                    symbolsKey(),
                    letter("%"),
                    letter("©"),
                    letter("®"),
                    letter("™"),
                    letter("✓"),
                    letter("["),
                    letter("]"),
                    Key(KeyAction.Backspace, icon = KeyIcon.BACKSPACE, widthUnits = 1.5f, isFunction = true),
                ),
            ),
            bottomRow(lettersToggle = true),
        ),
        layer = KeyboardLayer.SYMBOLS2,
    )

    /**
     * Bottom row per DESIGN_SPEC §3.1: ?123 (1.5u) · comma (1u) · mic (1.25u) ·
     * space (4u) · period (1u) · enter (1.25u).
     */
    private fun bottomRow(lettersToggle: Boolean = false): KeyRow {
        val layerKey = if (lettersToggle) {
            Key(KeyAction.ToLetters, label = "ABC", widthUnits = 1.5f, isFunction = true)
        } else {
            symbolsKey()
        }
        return KeyRow(
            listOf(
                layerKey,
                Key(KeyAction.Text(","), label = ",", longPress = listOf("!", "?"), isFunction = true),
                Key(KeyAction.Mic, icon = KeyIcon.MIC, widthUnits = 1.25f, isAccent = true),
                Key(KeyAction.Space, widthUnits = 4f),
                Key(KeyAction.Text("."), label = ".", longPress = listOf("…", ",", "?", "!", ":", ";"), isFunction = true),
                Key(KeyAction.Enter, icon = KeyIcon.ENTER, widthUnits = 1.25f, isFunction = true),
            ),
        )
    }

    /**
     * The `?123` key. Holding it opens the clipboard panel, so it is built in
     * one place: it appears on both the letters bottom row and the SYMBOLS2 row.
     */
    private fun symbolsKey(): Key = Key(
        KeyAction.ToSymbols,
        label = "?123",
        widthUnits = 1.5f,
        longPressAction = KeyAction.ToClipboard,
        isFunction = true,
    )

    fun forLayer(layer: KeyboardLayer, numberRow: Boolean = false): KeyboardLayout = when (layer) {
        KeyboardLayer.LETTERS -> if (numberRow) LETTERS_NUMBER_ROW else LETTERS
        KeyboardLayer.SYMBOLS -> SYMBOLS
        KeyboardLayer.SYMBOLS2 -> SYMBOLS2
        // Emoji and clipboard swap their own panel into the content frame; the
        // letters layout is what sits behind them.
        KeyboardLayer.EMOJI, KeyboardLayer.CLIPBOARD ->
            if (numberRow) LETTERS_NUMBER_ROW else LETTERS
    }
}
