package com.vboard.core.session

import com.vboard.core.session.DictationStateMachine.Effect
import com.vboard.core.session.DictationStateMachine.Event
import com.vboard.core.session.DictationStateMachine.State
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * The lifecycle invariants of [com.vboard.core.qa.StateMachineFuzzTest], re-run
 * over an event alphabet that also contains the interruption and overrun events
 * (VB-123, VB-106). Adding an event that finalizes *and* ends the session was
 * the most plausible way to reintroduce a double mic teardown or a session
 * stranded with a hot mic, so it gets its own seeded fuzz.
 *
 * Invariants (the audio ones are the load-bearing ones here):
 *  1. onEvent never throws.
 *  2. StartAudio/StopAudio strictly alternate — no double start, and never a
 *     teardown of a record that is already gone.
 *  3. At rest in Idle or Error the microphone is released.
 *  4. Entering Idle always hides the voice bar.
 *  5. A commit never fires from Idle/Error/PreparingModels, and is never blank.
 *  6. Losing audio focus with speech in flight always finalizes it — the
 *     machine never goes straight from Listening-with-a-partial to a state that
 *     has thrown that speech away.
 */
class InterruptFuzzTest {

    private val partials = listOf("", " ", "hello", "hello world", "a".repeat(300))

    private fun randomEvent(rnd: Random): Event = when (rnd.nextInt(15)) {
        0 -> Event.MicPressed
        1 -> Event.ModelsReady
        2 -> Event.ModelsMissing
        3 -> Event.ModelsUnusable
        4 -> Event.PermissionDenied
        5 -> Event.AudioError
        6 -> Event.Partial(partials[rnd.nextInt(partials.size)])
        7 -> Event.EndpointDetected
        8 -> Event.FinalTranscript(partials[rnd.nextInt(partials.size)])
        9 -> Event.ScratchThat
        10 -> Event.StopRequested
        11 -> Event.SilenceTimeout
        12 -> Event.ErrorDismissed
        13 -> Event.AudioFocusLost
        else -> Event.AudioOverrun(rnd.nextInt(0, 4) * 1_600)
    }

    @Test
    fun `interruptions and overruns uphold every lifecycle invariant`() {
        for (run in 0 until 300) {
            val seed = 1_000L + run
            val rnd = Random(seed)
            val machine = DictationStateMachine(
                DictationStateMachine.Config(refineEnabled = run % 2 == 0),
            )
            var audioActive = false
            var barVisible = false
            val trace = ArrayDeque<String>()

            repeat(60) { step ->
                val event = randomEvent(rnd)
                val pre = machine.state
                trace.addLast("$pre --$event-->")
                if (trace.size > 10) trace.removeFirst()
                fun ctx() = "seed=$seed step=$step trace=${trace.joinToString(" | ")}"

                val effects = try {
                    machine.onEvent(event)
                } catch (t: Throwable) {
                    throw AssertionError("onEvent threw for ${ctx()}", t)
                }
                val post = machine.state

                for (effect in effects) {
                    when (effect) {
                        Effect.ShowVoiceBar -> barVisible = true
                        Effect.HideVoiceBar -> barVisible = false
                        Effect.StartAudio -> {
                            assertTrue(barVisible, "StartAudio with no voice bar: ${ctx()}")
                            assertTrue(!audioActive, "StartAudio while recording: ${ctx()}")
                            audioActive = true
                        }
                        Effect.StopAudio -> {
                            assertTrue(audioActive, "StopAudio with no live record: ${ctx()}")
                            audioActive = false
                        }
                        is Effect.CommitUtterance -> {
                            assertTrue(
                                pre is State.Listening || pre is State.Finalizing,
                                "CommitUtterance from $pre: ${ctx()}",
                            )
                            assertTrue(effect.text.isNotBlank(), "blank commit: ${ctx()}")
                        }
                        else -> {}
                    }
                }

                if (post is State.Idle || post is State.Error) {
                    assertTrue(!audioActive, "microphone still hot in $post: ${ctx()}")
                }
                if (pre !is State.Idle && post is State.Idle) {
                    assertTrue(Effect.HideVoiceBar in effects, "Idle without hiding: ${ctx()}")
                }
                // Invariant 6: buffered speech is never thrown away by an
                // interruption — the whole point of VB-123.
                if (event == Event.AudioFocusLost && pre is State.Listening &&
                    pre.partial.isNotBlank()
                ) {
                    assertTrue(
                        effects.any { it is Effect.BeginFinalize },
                        "focus loss discarded a live utterance: ${ctx()}",
                    )
                }
                if (event is Event.AudioOverrun && event.droppedSamples > 0 &&
                    (pre is State.Listening || pre is State.Finalizing)
                ) {
                    assertTrue(
                        effects.any { it is Effect.NoteAudioOverrun },
                        "dropped audio went unreported: ${ctx()}",
                    )
                    assertTrue(post == pre, "an overrun changed state: ${ctx()}")
                }
            }
        }
    }
}
