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

    /** Whether the audio mode already looked like a call when this session began. */
    private var callModeAtStart = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (isLosingMicrophone(change)) report(Reason.FOCUS_LOST)
    }

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // setWillPauseWhenDucked is deliberately left at false. Setting it
            // converts every duckable interruption into AUDIOFOCUS_LOSS_TRANSIENT
            // — which we treat as "the microphone is gone" — so a notification
            // chime would end the user's dictation. We play nothing, so there is
            // nothing to duck and nothing to pause.
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
        callModeAtStart = isCallActive()
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

    /**
     * True when a call has begun *since this session started*.
     *
     * The transition matters: some devices are slow to leave
     * MODE_IN_COMMUNICATION after a VoIP call ends, and treating a stale mode as
     * an interruption would kill a dictation that never had a call at all. If
     * the mode was already call-like when we started, the microphone was never
     * ours to begin with and the capture stall watchdog is the right reporter.
     */
    fun callStarted(): Boolean = isCallActive() && !callModeAtStart

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

    internal companion object {
        private const val TAG = "VBoardFocus"

        /**
         * Whether a focus change means the microphone is going away.
         *
         * AUDIOFOCUS_LOSS is another app taking the audio path outright;
         * AUDIOFOCUS_LOSS_TRANSIENT is what an incoming call sends. Ducking is
         * neither: it asks us to be quieter, we are not playing anything, and it
         * does not touch capture — ending a dictation session because a
         * notification chimed would be its own bug. Regaining focus is not
         * handled at all: VB-123 is explicit that the user re-taps the mic.
         */
        fun isLosingMicrophone(change: Int): Boolean =
            change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    }
}
