package com.vboard.app.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.vboard.app.keyboard.KeyIcon
import com.vboard.core.text.FieldKind

/** Everything derived from [EditorInfo] once per field focus. */
data class EditorProfile(
    val fieldKind: FieldKind,
    val enterIcon: KeyIcon,
    val imeActionId: Int,
    val isMultiline: Boolean,
    val noPersonalizedLearning: Boolean,
) {
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
            // VB-701 names all six actions. Anything short of the full set draws a
            // return arrow over a key that performs Go or Done — the key lying about
            // what it does is worse than it being unlabelled.
            val enterIcon = if (noEnterAction || multiline) {
                KeyIcon.ENTER
            } else {
                when (action) {
                    EditorInfo.IME_ACTION_SEARCH -> KeyIcon.SEARCH
                    EditorInfo.IME_ACTION_SEND -> KeyIcon.SEND
                    EditorInfo.IME_ACTION_GO -> KeyIcon.GO
                    EditorInfo.IME_ACTION_NEXT -> KeyIcon.NEXT
                    EditorInfo.IME_ACTION_DONE -> KeyIcon.DONE
                    EditorInfo.IME_ACTION_PREVIOUS -> KeyIcon.PREVIOUS
                    else -> KeyIcon.ENTER
                }
            }

            val noLearning =
                (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

            return EditorProfile(
                fieldKind = fieldKind,
                enterIcon = enterIcon,
                // IME_FLAG_NO_ENTER_ACTION is the editor asking for a literal
                // newline no matter what action it also declared; dropping the
                // action here is what makes onEnter fall through to committing one.
                imeActionId = if (noEnterAction) EditorInfo.IME_ACTION_NONE else action,
                isMultiline = multiline,
                noPersonalizedLearning = noLearning,
            )
        }
    }
}
