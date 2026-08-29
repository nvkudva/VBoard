package com.vboard.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Audio focus and telephony awareness for a dictation session (VB-123).
 *
 * There was none of this before. When a call arrived mid-dictation the telephony
 * stack took the microphone; reads started returning silence or errors, and the
 * session sat there looking like it was listening until the stall watchdog fired
 * — with the user's buffered speech thrown away. The fix has two halves:
 *
 *  1. **Ask.** Holding transient-exclusive focus for the length of a session is
 *     what makes other apps stop playing audio into our microphone, and is what
 *     the platform notifies us through when something more important (a call)
 *     needs the audio path.
 *  2. **Notice.** Focus callbacks are not the only way the mic goes away, and on
 *     some devices they are not the first. [isCallActive] reads the audio mode,
 *     which goes to MODE_RINGTONE / MODE_IN_CALL / MODE_IN_COMMUNICATION as soon
 *     as the phone rings — no READ_PHONE_STATE permission, no manifest change,
 *     and no dependency on which of the two signals a given OEM sends first.
 *
 * [onLoss] is invoked on the main thread, at most once per [request] — the
 * session's response (finalize the buffered utterance, then stop capture) must
 * not be run twice for one interruption.
 */
class AudioFocusGuard(
    context: Context,
    private val onLoss: (Reason) -> Unit,
) {

    enum class Reason { FOCUS_LOST, CALL_ACTIVE }

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** True between a granted [request] and its [abandon]. */
    private var held = false

    /** Guards against reporting one interruption through both signals. */
    private var reported = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // A call, or another app taking the audio path outright. Both mean
            // the microphone is about to stop producing our audio.
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> report(Reason.FOCUS_LOST)
            // Ducking is a request to be quieter, and we are not playing
            // anything. It does not touch the microphone, so it is not an
            // interruption — treating it as one would end sessions for a
            // notification chime.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
            // VB-123 is explicit that regaining focus does NOT auto-resume; the
            // user re-taps the mic. Nothing to do here.
            else -> Unit
        }
    }

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // We never play audio, so there is nothing to duck; say so rather
            // than letting the framework wait for a fade-out that never comes.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(listener, mainHandler)
            .build()

    /**
     * Requests focus for a session that is entering the listening state.
     *
     * @return true when focus was granted. A refusal is logged but is not by
     *   itself fatal: the microphone may still be ours, and refusing to record
     *   because the framework declined a focus request would turn a cosmetic
     *   problem into a broken keyboard. The interruption path below is what
     *   handles actually losing the mic.
     */
    fun request(): Boolean {
        val manager = audioManager ?: return false
        reported = false
        val result = runCatching { manager.requestAudioFocus(focusRequest) }
            .onFailure { Log.w(TAG, "audio focus request failed", it) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!held) Log.w(TAG, "audio focus not granted (result=$result); recording anyway")
        return held
    }

    /**
     * Releases focus. Called from the same teardown path as the microphone, so
     * it lands well inside the 500ms budget after leaving the listening state,
     * and is safe to call when no focus is held.
     */
    fun abandon() {
        val manager = audioManager ?: return
        if (!held) return
        held = false
        runCatching { manager.abandonAudioFocusRequest(focusRequest) }
            .onFailure { Log.w(TAG, "abandoning audio focus failed", it) }
    }

    /**
     * True when the telephony stack owns the audio path: ringing, in a cellular
     * call, or in a VoIP call. Polled by the session watchdog because a focus
     * callback is not guaranteed to arrive first (or at all) on every device.
     */
    fun isCallActive(): Boolean {
        val mode = audioManager?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    /** Reports a call detected by the poller; deduplicated against focus loss. */
    fun reportCallActive() = report(Reason.CALL_ACTIVE)

    private fun report(reason: Reason) {
        if (reported) return
        reported = true
        // Counts and reasons only: never audio, never transcript text.
        Log.i(TAG, "dictation interrupted by $reason")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onLoss(reason)
        } else {
            mainHandler.post { onLoss(reason) }
        }
    }

    private companion object {
        const val TAG = "VBoardFocus"
    }
}
