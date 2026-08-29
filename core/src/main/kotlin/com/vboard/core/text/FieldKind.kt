package com.vboard.core.text

/**
 * Semantic kind of the text field the IME is attached to, derived from
 * [android.view.inputmethod.EditorInfo] in the app layer. Core logic keys off
 * this instead of Android input types so it stays platform-free and testable.
 */
enum class FieldKind {
    /** Free-form text (messages, notes, documents). */
    TEXT,

    /** Email address fields: no autocorrect, no auto-capitalization. */
    EMAIL,

    /** URLs and URIs: no autocorrect, no auto-capitalization. */
    URI,

    /**
     * Password and other sensitive fields: voice input, suggestions, and any
     * learning MUST be disabled entirely.
     */
    PASSWORD,

    /** Search boxes: suggestions allowed, cleanup stays query-styled (no trailing period). */
    SEARCH,

    /** Numeric/phone fields: the QWERTY layers are replaced and suggestions disabled. */
    NUMBER;

    val allowsSuggestions: Boolean
        get() = this == TEXT || this == SEARCH

    val allowsAutocorrect: Boolean
        get() = this == TEXT || this == SEARCH

    val allowsVoice: Boolean
        get() = this != PASSWORD && this != NUMBER

    val allowsAutoCapitalize: Boolean
        get() = this == TEXT || this == SEARCH

    val allowsLearning: Boolean
        get() = this == TEXT
}
