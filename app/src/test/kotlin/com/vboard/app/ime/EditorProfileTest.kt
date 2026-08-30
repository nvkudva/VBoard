package com.vboard.app.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.vboard.app.keyboard.KeyIcon
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the Enter key promises and what it does are both read off this profile
 * (VB-701), so they are asserted together: an icon that disagrees with
 * [EditorProfile.imeActionId] is the defect, not a cosmetic issue.
 */
@RunWith(RobolectricTestRunner::class)
class EditorProfileTest {

    private fun info(imeOptions: Int, inputType: Int = InputType.TYPE_CLASS_TEXT) =
        EditorInfo().also {
            it.imeOptions = imeOptions
            it.inputType = inputType
        }

    @Test
    fun `every editor action gets its own enter icon`() {
        val cases = listOf(
            EditorInfo.IME_ACTION_SEARCH to KeyIcon.SEARCH,
            EditorInfo.IME_ACTION_SEND to KeyIcon.SEND,
            EditorInfo.IME_ACTION_GO to KeyIcon.GO,
            EditorInfo.IME_ACTION_NEXT to KeyIcon.NEXT,
            EditorInfo.IME_ACTION_DONE to KeyIcon.DONE,
            EditorInfo.IME_ACTION_PREVIOUS to KeyIcon.PREVIOUS,
        )
        for ((action, icon) in cases) {
            val profile = EditorProfile.from(info(action))
            assertEquals(icon, profile.enterIcon)
            assertEquals(action, profile.imeActionId)
        }
    }

    @Test
    fun `an editor with no action keeps the return arrow`() {
        val profile = EditorProfile.from(info(EditorInfo.IME_ACTION_NONE))
        assertEquals(KeyIcon.ENTER, profile.enterIcon)
        assertEquals(EditorInfo.IME_ACTION_NONE, profile.imeActionId)
    }

    @Test
    fun `IME_FLAG_NO_ENTER_ACTION drops the action so enter types a newline`() {
        val profile = EditorProfile.from(
            info(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION),
        )
        assertEquals(KeyIcon.ENTER, profile.enterIcon)
        // onEnter fires performEditorAction for anything but NONE/UNSPECIFIED.
        assertEquals(EditorInfo.IME_ACTION_NONE, profile.imeActionId)
    }

    @Test
    fun `a multiline field draws the return arrow but keeps its action`() {
        val profile = EditorProfile.from(
            info(
                EditorInfo.IME_ACTION_DONE,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            ),
        )
        assertEquals(KeyIcon.ENTER, profile.enterIcon)
        assertEquals(EditorInfo.IME_ACTION_DONE, profile.imeActionId)
    }

    @Test
    fun `a null editor is a plain text field`() {
        val profile = EditorProfile.from(null)
        assertEquals(KeyIcon.ENTER, profile.enterIcon)
        assertEquals(EditorInfo.IME_ACTION_NONE, profile.imeActionId)
    }
}
