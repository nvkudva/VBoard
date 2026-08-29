package com.vboard.app.voice

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * VB-123, the half of it that can be checked without a phone ringing.
 *
 * See the "unit tests" section at the end of app/build.gradle.kts for why a
 * JUnit 4 runner works in a JUnit Platform module. Robolectric gives a real
 * AudioManager backed by a shadow, so focus requests and the audio mode behave
 * like the framework rather than like a mock written to match our own
 * assumptions.
 */
@RunWith(RobolectricTestRunner::class)
class AudioFocusGuardTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun audioManager(): AudioManager =
        context.getSystemService(AudioManager::class.java)

    @Test
    fun `focus is requested and abandoned without throwing`() {
        val guard = AudioFocusGuard(context) {}
        assertTrue("focus should be granted in an idle system", guard.request())
        guard.abandon()
        // Abandoning twice is what happens when a session ends and is then torn
        // down again by the IME lifecycle; it must be a no-op, not a crash.
        guard.abandon()
    }

    @Test
    fun `losing focus outright or transiently means the microphone is going away`() {
        assertTrue(AudioFocusGuard.isLosingMicrophone(AudioManager.AUDIOFOCUS_LOSS))
        assertTrue(AudioFocusGuard.isLosingMicrophone(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
    }

    @Test
    fun `ducking and regaining focus are not interruptions`() {
        // A notification chime ducks other apps. It does not take the mic, and
        // ending the user's dictation for it would be a bug of our own making.
        assertFalse(
            AudioFocusGuard.isLosingMicrophone(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
        // VB-123: no auto-resume on regain.
        assertFalse(AudioFocusGuard.isLosingMicrophone(AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test
    fun `a ringing or in-call audio mode counts as a call`() {
        val guard = AudioFocusGuard(context) {}
        val manager = audioManager()

        manager.mode = AudioManager.MODE_NORMAL
        assertFalse(guard.isCallActive())

        for (mode in listOf(
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
        )) {
            manager.mode = mode
            assertTrue("mode $mode should count as a call", guard.isCallActive())
        }
        manager.mode = AudioManager.MODE_NORMAL
    }

    @Test
    fun `only a call that starts during the session counts`() {
        val manager = audioManager()
        val guard = AudioFocusGuard(context) {}

        // A stale MODE_IN_COMMUNICATION left behind by a VoIP app that has
        // already hung up must not kill a dictation that never had a call.
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        guard.request()
        assertTrue(guard.isCallActive())
        assertFalse("a pre-existing call mode is not an interruption", guard.callStarted())

        manager.mode = AudioManager.MODE_NORMAL
        guard.request()
        manager.mode = AudioManager.MODE_IN_CALL
        assertTrue("a call arriving mid-session is", guard.callStarted())

        manager.mode = AudioManager.MODE_NORMAL
    }

    @Test
    fun `one interruption is reported once`() {
        // Focus loss and the call poller can both fire for a single incoming
        // call. The session's response — finalize the buffered utterance, then
        // stop capture — must not run twice for one interruption.
        var reports = 0
        val guard = AudioFocusGuard(context) { reports++ }
        guard.request()

        guard.reportCallActive()
        guard.reportCallActive()
        assertEquals(1, reports)
    }

    @Test
    fun `a new session can be interrupted again`() {
        var reports = 0
        val guard = AudioFocusGuard(context) { reports++ }
        guard.request()
        guard.reportCallActive()
        guard.abandon()

        guard.request()
        guard.reportCallActive()
        assertEquals(2, reports)
    }

    @Test
    fun `the reason names which signal fired`() {
        val reasons = mutableListOf<AudioFocusGuard.Reason>()
        val guard = AudioFocusGuard(context) { reasons += it }
        guard.request()
        guard.reportCallActive()
        assertEquals(listOf(AudioFocusGuard.Reason.CALL_ACTIVE), reasons)
    }
}
