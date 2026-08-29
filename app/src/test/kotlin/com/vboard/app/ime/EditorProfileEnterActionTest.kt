package com.vboard.app.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.vboard.app.keyboard.KeyIcon
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Enter follows the editor's declared IME action instead of always writing a
 * newline: Go, Search, Send, Next, Previous and Done each fire, each draw their
 * own glyph, and the two cases that genuinely want a newline still get one.
 */
class EditorProfileEnterActionTest {

    private fun info(
        imeOptions: Int = 0,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
    ): EditorInfo = EditorInfo().also {
        it.imeOptions = imeOptions
        it.inputType = inputType
    }

    @Test
    fun `each ime action is honoured and drawn as itself`() {
        val expected = mapOf(
            EditorInfo.IME_ACTION_GO to KeyIcon.GO,
            EditorInfo.IME_ACTION_SEARCH to KeyIcon.SEARCH,
            EditorInfo.IME_ACTION_SEND to KeyIcon.SEND,
            EditorInfo.IME_ACTION_NEXT to KeyIcon.NEXT,
            EditorInfo.IME_ACTION_PREVIOUS to KeyIcon.PREVIOUS,
            EditorInfo.IME_ACTION_DONE to KeyIcon.DONE,
        )
        for ((action, icon) in expected) {
            val profile = EditorProfile.from(info(imeOptions = action))
            assertTrue(profile.performsImeAction, "action $action should fire")
            assertEquals(action, profile.imeActionId)
            assertEquals(icon, profile.enterIcon)
        }
    }

    @Test
    fun `an unspecified or absent action inserts a newline`() {
        for (action in listOf(EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED)) {
            val profile = EditorProfile.from(info(imeOptions = action))
            assertFalse(profile.performsImeAction, "action $action should not fire")
            assertEquals(EditorInfo.IME_ACTION_NONE, profile.imeActionId)
            assertEquals(KeyIcon.ENTER, profile.enterIcon)
        }
    }

    @Test
    fun `IME_FLAG_NO_ENTER_ACTION keeps the newline even with an action declared`() {
        val profile = EditorProfile.from(
            info(imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION),
        )
        assertFalse(profile.performsImeAction)
        assertEquals(EditorInfo.IME_ACTION_NONE, profile.imeActionId)
        assertEquals(KeyIcon.ENTER, profile.enterIcon)
    }

    @Test
    fun `a multiline field keeps the newline even with an action declared`() {
        val profile = EditorProfile.from(
            info(
                imeOptions = EditorInfo.IME_ACTION_DONE,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            ),
        )
        assertTrue(profile.isMultiline)
        assertFalse(profile.performsImeAction)
        assertEquals(KeyIcon.ENTER, profile.enterIcon)
    }

    @Test
    fun `a null editor is a plain newline field`() {
        val profile = EditorProfile.from(null)
        assertFalse(profile.performsImeAction)
        assertEquals(KeyIcon.ENTER, profile.enterIcon)
    }
}
