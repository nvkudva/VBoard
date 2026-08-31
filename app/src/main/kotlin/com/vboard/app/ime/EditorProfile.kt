package com.vboard.app.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.vboard.app.keyboard.KeyIcon
import com.vboard.core.text.FieldKind

/** Everything derived from [EditorInfo] once per field focus. */
data class EditorProfile(
    val fieldKind: FieldKind,
    val enterIcon: KeyIcon,
    /**
     * The action Enter performs, already resolved against the editor's wishes:
     * [EditorInfo.IME_ACTION_NONE] means Enter inserts a newline instead. A
     * multiline field, `IME_FLAG_NO_ENTER_ACTION`, and an unspecified action
     * all land here as NONE, so the key has one rule to follow.
     */
    val imeActionId: Int,
    val isMultiline: Boolean,
    val noPersonalizedLearning: Boolean,
) {
    /** True when Enter fires [imeActionId] rather than inserting a newline. */
    val performsImeAction: Boolean get() = imeActionId != EditorInfo.IME_ACTION_NONE

    companion object {
        fun from(info: EditorInfo?): EditorProfile {
            info ?: return EditorProfile(FieldKind.TEXT, KeyIcon.ENTER, EditorInfo.IME_ACTION_NONE, false, false)
            val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
            val variation = info.inputType and InputType.TYPE_MASK_VARIATION

            val isTextPassword = inputClass == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                )
            val isNumberPassword = inputClass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

            val fieldKind = when {
                isTextPassword || isNumberPassword -> FieldKind.PASSWORD
                inputClass == InputType.TYPE_CLASS_NUMBER ||
                    inputClass == InputType.TYPE_CLASS_PHONE ||
                    inputClass == InputType.TYPE_CLASS_DATETIME -> FieldKind.NUMBER
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
                variation == InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URI
                (info.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH ->
                    FieldKind.SEARCH
                else -> FieldKind.TEXT
            }

            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            val multiline = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            // An editor that wants a literal newline — a multiline field, or one
            // that set IME_FLAG_NO_ENTER_ACTION — keeps the newline even when it
            // also declares an action for the toolbar-style button next to it.
            // An editor with its own action label (a custom "Search" or "Post"
            // button) declares the id in actionId, not in imeOptions, so reading
            // imeOptions alone left Enter inserting a newline in exactly the
            // fields most likely to have one.
            val customActionId = info.actionLabel?.let { info.actionId.takeIf { id -> id != 0 } }
            val enterAction = if (noEnterAction || multiline) {
                EditorInfo.IME_ACTION_NONE
            } else if (customActionId != null) {
                customActionId
            } else {
                when (action) {
                    EditorInfo.IME_ACTION_GO,
                    EditorInfo.IME_ACTION_SEARCH,
                    EditorInfo.IME_ACTION_SEND,
                    EditorInfo.IME_ACTION_NEXT,
                    EditorInfo.IME_ACTION_PREVIOUS,
                    EditorInfo.IME_ACTION_DONE,
                    -> action
                    // NONE and UNSPECIFIED both mean "no action to fire".
                    else -> EditorInfo.IME_ACTION_NONE
                }
            }

            val enterIcon = when (enterAction) {
                EditorInfo.IME_ACTION_GO -> KeyIcon.GO
                EditorInfo.IME_ACTION_SEARCH -> KeyIcon.SEARCH
                EditorInfo.IME_ACTION_SEND -> KeyIcon.SEND
                EditorInfo.IME_ACTION_NEXT -> KeyIcon.NEXT
                EditorInfo.IME_ACTION_PREVIOUS -> KeyIcon.PREVIOUS
                EditorInfo.IME_ACTION_DONE -> KeyIcon.DONE
                // A custom action has a label we cannot draw, but it is still an
                // action: anything but the newline glyph would be a lie.
                else -> if (customActionId != null) KeyIcon.GO else KeyIcon.ENTER
            }

            val noLearning =
                (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

            return EditorProfile(
                fieldKind = fieldKind,
                enterIcon = enterIcon,
                imeActionId = enterAction,
                isMultiline = multiline,
                noPersonalizedLearning = noLearning,
            )
        }
    }
}
