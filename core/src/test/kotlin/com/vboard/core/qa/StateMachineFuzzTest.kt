package com.vboard.core.qa

import com.vboard.core.session.DictationStateMachine
import com.vboard.core.session.DictationStateMachine.Effect
import com.vboard.core.session.DictationStateMachine.Event
import com.vboard.core.session.DictationStateMachine.State
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seeded-random event-sequence fuzzing of [DictationStateMachine] (VB-101..108,
 * VB-120..125): 200 runs x 50 events = 10_000 random events, each run with a
 * fixed seed so any failure reproduces exactly. After every event a set of
 * lifecycle invariants is checked; violations report the seed and the trailing
 * event trace.
 *
 * Invariants:
 *  1. onEvent never throws.
 *  2. StartAudio is only emitted while the voice bar is visible (ShowVoiceBar
 *     seen and not yet hidden) - audio never runs without visible UI (VB-107).
 *  3. StartAudio/StopAudio strictly alternate: no double-start, no stop while
 *     audio is off.
 *  4. Whenever the machine rests in Idle or Error, audio is off (mic released,
 *     VB-107/VB-620).
 *  5. CommitUtterance only fires from Listening/Finalizing (never Idle/Error/
 *     PreparingModels) and never with blank text.
 *  6. Leaving Listening/Finalizing for Idle always carries StopAudio.
 *  7. Entering Idle from any other state always carries HideVoiceBar.
 *  8. utteranceIndex is non-decreasing across consecutive in-session states.
 *  9. After StopRequested the machine is Idle, or Finalizing with the stop
 *     deferred until the in-flight utterance commits (BL-2).
 * 10. After ErrorDismissed the machine is never stuck in Error.
 */
class StateMachineFuzzTest {

    private val partials = listOf(
        "", " ", "hello", "hello world", "um", "ok so", "℀ unicode ✓", "a".repeat(500),
    )

    private fun randomEvent(rnd: Random): Event = when (rnd.nextInt(12)) {
        0 -> Event.MicPressed
        1 -> Event.ModelsReady
        2 -> Event.ModelsMissing
        3 -> Event.PermissionDenied
        4 -> Event.AudioError
        5 -> Event.Partial(partials[rnd.nextInt(partials.size)])
        6 -> Event.EndpointDetected
        7 -> Event.FinalTranscript(partials[rnd.nextInt(partials.size)])
        8 -> Event.ScratchThat
        9 -> Event.StopRequested
        10 -> Event.SilenceTimeout
        else -> Event.ErrorDismissed
    }

    private fun indexOf(state: State): Int? = when (state) {
        is State.Listening -> state.utteranceIndex
        is State.Finalizing -> state.utteranceIndex
        else -> null
    }

    @Test
    fun `10_000 random events uphold all lifecycle invariants`() {
        val runs = 200
        val eventsPerRun = 50
        for (run in 0 until runs) {
            val seed = 42L + run
            val rnd = Random(seed)
            val machine = DictationStateMachine(
                DictationStateMachine.Config(refineEnabled = run % 2 == 0),
            )
            var barVisible = false
            var audioActive = false
            var lastIndex: Int? = null
            val trace = ArrayDeque<String>()

            repeat(eventsPerRun) { step ->
                val event = randomEvent(rnd)
                val pre = machine.state
                trace.addLast("$pre --$event-->")
                if (trace.size > 12) trace.removeFirst()
                fun ctx() = "seed=$seed step=$step trace=${trace.joinToString(" | ")}"

                // Invariant 1: never throws.
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
                            // Invariant 2: audio only starts under a visible bar.
                            assertTrue(barVisible, "StartAudio with hidden voice bar: ${ctx()}")
                            // Invariant 3: no double start.
                            assertTrue(!audioActive, "StartAudio while audio already active: ${ctx()}")
                            audioActive = true
                        }
                        Effect.StopAudio -> {
                            assertTrue(audioActive, "StopAudio while audio not active: ${ctx()}")
                            audioActive = false
                        }
                        is Effect.CommitUtterance -> {
                            // Invariant 5.
                            assertTrue(
                                pre is State.Listening || pre is State.Finalizing,
                                "CommitUtterance from $pre: ${ctx()}",
                            )
                            assertTrue(effect.text.isNotBlank(), "blank CommitUtterance: ${ctx()}")
                        }
                        else -> {}
                    }
                }

                // Invariant 4: at rest in Idle/Error, mic is released.
                if (post is State.Idle || post is State.Error) {
                    assertTrue(!audioActive, "audio active while $post: ${ctx()}")
                }

                // Invariant 6: leaving Listening/Finalizing for Idle stops audio.
                if ((pre is State.Listening || pre is State.Finalizing) && post is State.Idle) {
                    assertTrue(
                        Effect.StopAudio in effects,
                        "left $pre for Idle without StopAudio: ${ctx()}",
                    )
                }

                // Invariant 7: entering Idle hides the voice bar.
                if (pre !is State.Idle && post is State.Idle) {
                    assertTrue(
                        Effect.HideVoiceBar in effects,
                        "entered Idle without HideVoiceBar: ${ctx()}",
                    )
                }

                // Invariant 8: utteranceIndex non-decreasing within a session.
                val postIndex = indexOf(post)
                if (postIndex != null) {
                    val prev = if (indexOf(pre) != null) lastIndex else null
                    if (prev != null) {
                        assertTrue(
                            postIndex >= prev,
                            "utteranceIndex decreased $prev -> $postIndex: ${ctx()}",
                        )
                    }
                    lastIndex = postIndex
                } else {
                    lastIndex = null
                }

                // Invariant 9: StopRequested lands in Idle, or parks in Finalizing
                // with the stop deferred until the in-flight utterance commits.
                if (event == Event.StopRequested) {
                    val deferred = post is State.Finalizing && post.stopAfterCommit
                    assertTrue(
                        post is State.Idle || deferred,
                        "StopRequested neither reached Idle nor deferred: ${ctx()}",
                    )
                }

                // Invariant 10: ErrorDismissed never leaves the machine in Error.
                if (event == Event.ErrorDismissed) {
                    assertTrue(post !is State.Error, "still in Error after dismiss: ${ctx()}")
                }
            }
        }
    }

    @Test
    fun `fuzz with heavy dictation bias reaches deep utterance indices safely`() {
        // Biased generator: mostly Partial/Endpoint/Final so long continuous
        // sessions actually happen, exercising index growth (VB-104 loop).
        for (run in 0 until 50) {
            val seed = 9000L + run
            val rnd = Random(seed)
            val machine = DictationStateMachine()
            machine.onEvent(Event.MicPressed)
            machine.onEvent(Event.ModelsReady)
            var maxIndex = 0
            var lastIndex = 0
            repeat(200) {
                val event = when (rnd.nextInt(10)) {
                    in 0..3 -> Event.Partial("partial ${rnd.nextInt(100)}")
                    in 4..6 -> Event.EndpointDetected
                    in 7..8 -> Event.FinalTranscript("final ${rnd.nextInt(100)}")
                    else -> Event.ScratchThat
                }
                machine.onEvent(event)
                val idx = indexOf(machine.state)
                if (idx != null) {
                    assertTrue(idx >= lastIndex, "seed=$seed index went $lastIndex -> $idx")
                    lastIndex = idx
                    if (idx > maxIndex) maxIndex = idx
                }
            }
            assertTrue(maxIndex > 0, "seed=$seed: biased fuzz never committed an utterance")
            // Session still tears down cleanly from wherever it ended up. Stopping
            // mid-finalize is deferred by design, so the commit that resolves it is
            // what completes the teardown.
            val effects = machine.onEvent(Event.StopRequested)
            assertTrue(Effect.StopAudio in effects)
            if (machine.state is State.Finalizing) {
                val finalEffects = machine.onEvent(Event.FinalTranscript("tail"))
                assertTrue(Effect.HideVoiceBar in finalEffects)
            } else {
                assertTrue(Effect.HideVoiceBar in effects)
            }
            assertEquals(State.Idle, machine.state)
        }
    }

    @Test
    fun `stop during finalizing cuts the mic but defers teardown to the commit`() {
        val machine = DictationStateMachine()
        machine.onEvent(Event.MicPressed)
        machine.onEvent(Event.ModelsReady)
        machine.onEvent(Event.Partial("in flight"))
        machine.onEvent(Event.EndpointDetected)
        assertTrue(machine.state is State.Finalizing)

        val stopEffects = machine.onEvent(Event.StopRequested)
        // The mic goes quiet immediately, but the bar stays up: hiding it here is
        // what used to lose the sentence the user had just watched appear.
        assertTrue(Effect.StopAudio in stopEffects)
        assertTrue(Effect.HideVoiceBar !in stopEffects)
        assertTrue(machine.state is State.Finalizing)

        val finalEffects = machine.onEvent(Event.FinalTranscript("In flight."))
        assertTrue(Effect.CommitUtterance("In flight.", 0, refine = false) in finalEffects)
        assertTrue(Effect.HideVoiceBar in finalEffects)
        assertEquals(State.Idle, machine.state)
    }

    @Test
    fun `new session after stop restarts utterance numbering at zero`() {
        val machine = DictationStateMachine()
        machine.onEvent(Event.MicPressed)
        machine.onEvent(Event.ModelsReady)
        machine.onEvent(Event.Partial("one"))
        machine.onEvent(Event.EndpointDetected)
        machine.onEvent(Event.FinalTranscript("One."))
        assertEquals(State.Listening("", 1), machine.state)
        machine.onEvent(Event.StopRequested)
        machine.onEvent(Event.MicPressed)
        machine.onEvent(Event.ModelsReady)
        assertEquals(State.Listening("", 0), machine.state)
    }
}
