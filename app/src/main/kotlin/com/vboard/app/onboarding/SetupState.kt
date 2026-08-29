package com.vboard.app.onboarding

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Remembers that first-run setup is behind the user, and rations the "you could download the
 * voice models" prompt.
 *
 * PRODUCT_SPEC VB-408: onboarding is skippable, every skipped step is re-offerable
 * contextually, and *the app never nags more than once per session*. Without the flag below,
 * a user who finished setup without downloading anything was sent back to the download screen
 * by every single launch, because the launcher activity recomputed "first incomplete step"
 * from disk and models were still missing.
 *
 * Kept in its own SharedPreferences file rather than the settings DataStore so that reading it
 * during `onCreate` is a synchronous, already-warm lookup and cannot race the settings flow's
 * first emission.
 */
object SetupState {

    private const val FILE = "vboard_setup"
    private const val KEY_COMPLETED = "setup_completed"

    /**
     * Process-scoped. "Session" here is the keyboard process's lifetime, which is what the
     * user experiences as one sitting: the IME process is created when they start typing and
     * lives until the system reclaims it.
     */
    private val promptOffered = AtomicBoolean(false)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * True once the user has reached the end of setup — including by skipping the downloads.
     * Finishing with nothing downloaded is a legitimate outcome, not an unfinished funnel.
     */
    fun isComplete(context: Context): Boolean = prefs(context).getBoolean(KEY_COMPLETED, false)

    fun markComplete(context: Context) {
        prefs(context).edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    /**
     * Returns true the first time it is called in this process, false afterwards.
     *
     * Call it before steering the user to the download screen off the back of something they
     * did for another reason (a mic tap). A second nudge in the same sitting is nagging: they
     * already saw the offer and chose to keep typing.
     */
    fun claimPromptSlot(): Boolean = promptOffered.compareAndSet(false, true)

    /** True when the download offer has already been made in this process. */
    val promptAlreadyOffered: Boolean get() = promptOffered.get()
}
