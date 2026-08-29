package com.vboard.app.settings

import com.vboard.core.session.SilenceTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-JVM half of the :app test source set — no Robolectric runner, no Android
 * framework, so it runs on the JUnit Platform directly alongside the
 * Robolectric (vintage) tests.
 *
 * Guards the silence-timeout default, which is a privacy commitment rather than
 * a tuning constant: the code used to hold the mic open for 30s, which matched
 * neither spec, and the point of the setting is that changing the shipped
 * default is one line.
 */
class VoiceSettingsTest {

    @Test
    fun `the default keeps the mic open for eight seconds, not thirty`() {
        assertEquals(SilenceTimeout.S8, SettingsSnapshot().silenceTimeout)
        assertEquals(8_000L, SettingsSnapshot().silenceTimeout.millis)
    }

    @Test
    fun `off means no automatic stop at all`() {
        assertNull(SilenceTimeout.OFF.millis)
    }

    @Test
    fun `every option the design spec lists is offered`() {
        assertEquals(
            listOf(SilenceTimeout.OFF, SilenceTimeout.S5, SilenceTimeout.S8, SilenceTimeout.S15),
            SilenceTimeout.entries.toList(),
        )
        assertEquals(listOf(null, 5_000L, 8_000L, 15_000L), SilenceTimeout.entries.map { it.millis })
    }

    @Test
    fun `dictating on the keyboard is the shipped default`() {
        // The mic key keeps the keys on screen unless the user turns this off,
        // in which case the full voice bar takes over as before.
        assertEquals(true, SettingsSnapshot().inlineDictation)
    }

    @Test
    fun `raw transcript mode still disables cleanup with the new field present`() {
        // The snapshot gained a field; this is the cheap regression guard that
        // its default did not disturb anything downstream of it.
        val snapshot = SettingsSnapshot(rawTranscriptMode = true)
        assertEquals(true, snapshot.cleanupOptions().rawMode)
    }
}
