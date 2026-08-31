package com.vboard.app.correct

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.vboard.app.R
import com.vboard.app.VBoardApp
import com.vboard.app.keyboard.ToolbarView
import com.vboard.app.voice.VoiceEngines
import com.vboard.core.correct.EditKind
import com.vboard.core.correct.FixButtonState
import com.vboard.core.correct.FixChunker
import com.vboard.core.correct.FixEdit
import com.vboard.core.correct.FixEdits
import com.vboard.core.correct.FixRefusal
import com.vboard.core.correct.FixResult
import com.vboard.core.correct.FixStatus
import com.vboard.core.correct.FixUndoStore
import com.vboard.core.correct.SmartFailure
import com.vboard.core.correct.SmartOutput
import com.vboard.core.correct.SmartRefiner
import com.vboard.core.correct.SmartTier
import com.vboard.core.correct.TextFixer
import com.vboard.core.text.FieldKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "AI fix", on Android (VB-231, VB-235, VB-237).
 *
 * The decisions all live in [TextFixer] and are unit-tested there; this class
 * does the four things that need a device: read the field, run the fix off the
 * main thread, write the result back inside one batch edit, and keep the
 * toolbar button honest about what is happening.
 *
 * Threading, because that is where an IME goes wrong:
 *  - [InputConnection] is touched **only** on the main thread, as everywhere
 *    else in this keyboard. Its calls are bounded IPC and the framework times
 *    them out; the alternative — reading a connection from a background thread —
 *    is not a documented guarantee and is not worth the risk here;
 *  - the rules pass runs on `Dispatchers.Default`, the model on
 *    `Dispatchers.IO`, so neither ever blocks a frame;
 *  - one fix at a time. A second tap while one is running is ignored, not
 *    queued, and the whole run is abandoned when the editor changes.
 */
class AiFixController(
    private val context: Context,
    private val app: VBoardApp,
    private val scope: CoroutineScope,
    private val host: Host,
    private val fixer: TextFixer = TextFixer(),
    private val undo: FixUndoStore = FixUndoStore(),
) : ToolbarView.Listener {

    interface Host {
        /** The live connection, or null when the editor is gone. */
        fun inputConnection(): InputConnection?

        /** The current field, from `EditorProfile.fieldKind`. */
        fun fieldKind(): FieldKind

        /**
         * Called immediately before the field is rewritten, on the main thread.
         * The IME must drop its composing shadow here (`finishComposingText()`
         * plus clearing its own buffer): after this the field's contents no
         * longer match anything the IME thinks it is composing.
         */
        fun onBeforeFieldRewrite()
    }

    private var toolbar: ToolbarView? = null
    private var job: Job? = null
    private var undoTimer: Job? = null
    private var running = false

    /** When this class last wrote to the field; see [onUserEdit]. */
    private var lastWriteAt = 0L

    /** What the last fix changed, attributed by tier. See [editorialEdits]. */
    private var lastEdits: List<FixEdit> = emptyList()

    /**
     * Identity of the editor session. Bumped on every new field so an undo — or
     * a fix that finishes late — can never land in the wrong one.
     */
    private var fieldToken = 0L

    // --------------------------------------------------------------- lifecycle

    /** Binds the toolbar and takes over its action list. */
    fun attach(toolbar: ToolbarView) {
        this.toolbar = toolbar
        toolbar.listener = this
        toolbar.setActions(listOf(buildAction(currentState())))
    }

    fun detach() {
        toolbar?.listener = null
        toolbar = null
    }

    /** A new editor session: abandon any run and drop the undo. */
    fun onStartInput() {
        fieldToken++
        cancelRun()
        undo.clear()
        undoTimer?.cancel()
        toolbar?.clearMessageNow()
        refresh()
    }

    /**
     * The user typed or deleted something: the undo is stale (VB-232).
     *
     * Call this from the IME's key handlers, **not** from `onUpdateSelection` —
     * the rewrite this class performs moves the cursor, and an undo that dies on
     * its own write is an undo nobody ever sees. The short guard below makes a
     * mis-wiring survivable rather than silent, and [performUndo] verifies the
     * field independently, so correctness never rests on this being called.
     */
    fun onUserEdit() {
        if (!undo.isArmed) return
        if (now() - lastWriteAt < SELF_WRITE_GUARD_MS) return
        undo.clear()
        refresh()
    }

    /** The input view is going away. */
    fun onFinishInputView() {
        cancelRun()
        toolbar?.clearMessageNow()
    }

    fun destroy() {
        cancelRun()
        undoTimer?.cancel()
        detach()
    }

    /** Re-reads the field gate and redraws the button. */
    fun refresh() {
        toolbar?.updateAction(buildAction(currentState()))
    }

    private fun cancelRun() {
        job?.cancel()
        job = null
        running = false
    }

    // ------------------------------------------------------------------ actions

    override fun onToolbarAction(id: ToolbarView.ActionId) {
        when (id) {
            ToolbarView.ActionId.AI_FIX ->
                if (undo.peek(now(), fieldToken) != null) performUndo() else performFix()
            // The row also carries actions this controller does not own.
            ToolbarView.ActionId.CLIPBOARD, ToolbarView.ActionId.SETTINGS -> Unit
        }
    }

    private fun performFix() {
        // A second tap while one is in flight is a no-op, not a second run.
        if (running) return
        val kind = host.fieldKind()
        if (!fixer.isEnabledFor(kind)) {
            say(refusalMessage(refusalForKind(kind)))
            return
        }
        val ic = host.inputConnection() ?: run { say(R.string.ai_fix_unavailable); return }
        val snapshot = readField(ic) ?: run { say(R.string.ai_fix_unavailable); return }
        fixer.refusalFor(kind, snapshot.text)?.let { say(refusalMessage(it)); return }

        val token = fieldToken
        running = true
        refresh()
        job = scope.launch {
            val result = try {
                runFix(snapshot.text, kind)
            } finally {
                running = false
            }
            if (token != fieldToken) {
                // The user moved to another field while the model was thinking.
                refresh()
                return@launch
            }
            applyResult(snapshot, result)
            refresh()
        }
    }

    private suspend fun runFix(text: String, kind: FieldKind): FixResult {
        val refiner = buildRefiner()
        return if (refiner == null) {
            withContext(Dispatchers.Default) { fixer.fix(text, kind, null) }
        } else {
            VoiceEngines.beginUse()
            try {
                // A whole-field fix can be several chunks; this is the ceiling on
                // the user's wait, after which the remaining chunks are dropped
                // and whatever was corrected so far is kept.
                withTimeoutOrNull(TOTAL_BUDGET_MS) {
                    withContext(Dispatchers.Default) { fixer.fix(text, kind, refiner) }
                } ?: withContext(Dispatchers.Default) {
                    // Out of time: keep the deterministic pass and say the smart
                    // tier was too slow, rather than claiming it was missing.
                    fixer.fix(text, kind, TimedOutRefiner)
                }
            } finally {
                VoiceEngines.endUse()
            }
        }
    }

    /**
     * The LLM pass, or null when there is no usable pack.
     *
     * Deliberately not gated on the dictation Tier-2 setting: that toggle governs
     * whether *speech* gets refined automatically. Tapping a button labelled "AI
     * fix" is an explicit request, and refusing it because of a setting on
     * another screen would be the button doing nothing for no visible reason.
     */
    private suspend fun buildRefiner(): SmartRefiner? {
        val refiner = withContext(Dispatchers.IO) {
            runCatching { VoiceEngines.loadRefiner(context, app) }
                .onFailure { Log.w(TAG, "refiner unavailable", it) }
                .getOrNull()
        } ?: return null
        return object : SmartRefiner {
            override suspend fun refine(text: String): SmartOutput = refiner.correct(text)
        }
    }

    /** Stands in for the model once the whole-fix budget is gone. */
    private object TimedOutRefiner : SmartRefiner {
        override suspend fun refine(text: String): SmartOutput =
            SmartOutput.failed(SmartFailure.TIMED_OUT)
    }

    private fun applyResult(snapshot: FieldSnapshot, result: FixResult) {
        val ic = host.inputConnection() ?: run { say(R.string.ai_fix_unavailable); return }
        if (result.status == FixStatus.REFUSED) {
            say(refusalMessage(result.refusal))
            return
        }
        // The field must still hold what we read, or the user has typed since and
        // this correction describes text that no longer exists.
        val current = readField(ic)
        if (current == null || current.text != snapshot.text) {
            say(R.string.ai_fix_field_changed)
            return
        }
        if (result.status == FixStatus.UNCHANGED) {
            say(unchangedMessage(result.smart))
            return
        }
        val corrected = result.correctedText()
        host.onBeforeFieldRewrite()
        if (!replaceAll(ic, current.text.length, corrected)) {
            say(R.string.ai_fix_unavailable)
            return
        }
        undo.record(
            original = snapshot.text,
            selectionStart = snapshot.selectionStart,
            selectionEnd = snapshot.selectionEnd,
            applied = corrected,
            fieldToken = fieldToken,
            nowMs = now(),
        )
        lastEdits = result.edits
        armUndoTimer()
        announceApplied(result)
    }

    // --------------------------------------------------- per-change attribution

    /**
     * The changes the *model* made, in the corrected text, newest fix first.
     *
     * Mechanical edits (casing, a doubled word, spacing) are not in here: they
     * are the kind of change nobody wants to be consulted about. Editorial ones
     * are, which is why they are addressable and individually revertible through
     * [revertEdit].
     *
     * No UI reads this yet — attributing changes on screen needs a surface this
     * class does not own — but the model behind it is complete and tested.
     */
    fun editorialEdits(): List<FixEdit> = lastEdits.filter { it.kind == EditKind.EDITORIAL }

    /**
     * Takes one change back on its own, leaving the rest of the fix in place.
     *
     * Returns false and does nothing when the field has moved under the edit, so
     * a stale span can never overwrite something the user typed since. The
     * whole-field undo survives a per-change revert and still restores the
     * pre-fix original.
     */
    fun revertEdit(edit: FixEdit): Boolean {
        val ic = host.inputConnection() ?: return false
        val current = readField(ic) ?: return false
        val restored = FixEdits.revert(current.text, edit) ?: return false
        host.onBeforeFieldRewrite()
        if (!replaceAll(ic, current.text.length, restored)) return false
        lastEdits = FixEdits.rebase(lastEdits, edit)
        undo.reapply(restored, now(), fieldToken)
        refresh()
        return true
    }

    private fun performUndo() {
        val snapshot = undo.peek(now(), fieldToken) ?: run { refresh(); return }
        val ic = host.inputConnection() ?: run { say(R.string.ai_fix_unavailable); return }
        val current = readField(ic)
        if (current == null || !snapshot.matchesField(current.text)) {
            // Somebody edited since the fix. Restoring now would delete that.
            undo.clear()
            refresh()
            say(R.string.ai_fix_undo_stale)
            return
        }
        host.onBeforeFieldRewrite()
        val original = snapshot.originalText()
        val restored = replaceAll(
            ic = ic,
            currentLength = current.text.length,
            newText = original,
            selectionStart = snapshot.selectionStart,
            selectionEnd = snapshot.selectionEnd,
        )
        undo.consume(now(), fieldToken)
        undoTimer?.cancel()
        refresh()
        say(if (restored) R.string.ai_fix_undone else R.string.ai_fix_unavailable)
    }

    private fun armUndoTimer() {
        undoTimer?.cancel()
        undoTimer = scope.launch {
            delay(undo.windowMs + UNDO_TIMER_SLACK_MS)
            refresh()
        }
    }

    // ------------------------------------------------------- input connection

    /** Everything read from the field in one pass. */
    private class FieldSnapshot(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
    )

    /**
     * Reads the whole field, or null when it cannot be read whole.
     *
     * `getExtractedText` is the right tool and is tried first; it returns null on
     * a dead connection and on editors that decline to provide it, so the
     * before/after-cursor pair is the fallback. Either way a partial read is
     * refused rather than guessed at: correcting half a field and writing it back
     * over the whole one would destroy the other half.
     */
    private fun readField(ic: InputConnection): FieldSnapshot? {
        val request = ExtractedTextRequest().apply {
            token = 0
            flags = 0
            hintMaxChars = FixChunker.MAX_FIELD_CHARS
            hintMaxLines = 0
        }
        val extracted = runCatching { ic.getExtractedText(request, 0) }.getOrNull()
        val extractedText = extracted?.text
        // startOffset != 0 means the editor handed back a window, not the field;
        // a full-length result means there is more of it we cannot see. Either
        // way, fall through and try the cursor-relative read instead.
        if (extracted != null && extractedText != null &&
            extracted.startOffset == 0 &&
            extractedText.length < FixChunker.MAX_FIELD_CHARS
        ) {
            val text = extractedText.toString()
            return FieldSnapshot(
                text = text,
                selectionStart = extracted.selectionStart.coerceIn(0, text.length),
                selectionEnd = extracted.selectionEnd.coerceIn(0, text.length),
            )
        }
        val before = runCatching { ic.getTextBeforeCursor(FALLBACK_LIMIT, 0) }.getOrNull() ?: return null
        val after = runCatching { ic.getTextAfterCursor(FALLBACK_LIMIT, 0) }.getOrNull() ?: return null
        // A full-length read means there is more text we cannot see.
        if (before.length >= FALLBACK_LIMIT || after.length >= FALLBACK_LIMIT) return null
        return FieldSnapshot(
            text = before.toString() + after.toString(),
            selectionStart = before.length,
            selectionEnd = before.length,
        )
    }

    /**
     * Replaces the field's entire contents in **one** batch edit, so the host
     * app's own undo stack sees a single operation rather than a delete followed
     * by an insert.
     *
     * The cursor is left at the end of the new text unless [selectionStart] says
     * otherwise (undo restores the exact original selection). End-of-text is the
     * right default: after a whole-field rewrite the old offset may not point at
     * the same word any more, and end-of-text is where somebody who just fixed
     * their message is about to keep typing.
     */
    private fun replaceAll(
        ic: InputConnection,
        currentLength: Int,
        newText: String,
        selectionStart: Int = -1,
        selectionEnd: Int = -1,
    ): Boolean {
        lastWriteAt = now()
        if (!ic.beginBatchEdit()) return false
        return try {
            ic.finishComposingText()
            // Collapse any selection to the end, then delete backwards over the
            // whole field. Offsets are absolute and the read proved the field
            // starts at 0.
            ic.setSelection(currentLength, currentLength)
            if (currentLength > 0) ic.deleteSurroundingText(currentLength, 0)
            ic.commitText(newText, 1)
            if (selectionStart >= 0 && selectionEnd >= 0) {
                ic.setSelection(
                    selectionStart.coerceIn(0, newText.length),
                    selectionEnd.coerceIn(0, newText.length),
                )
            }
            true
        } finally {
            ic.endBatchEdit()
        }
    }

    // ----------------------------------------------------------------- button

    private fun currentState(): FixButtonState = undo.buttonState(
        nowMs = now(),
        fieldToken = fieldToken,
        running = running,
        enabled = fixer.isEnabledFor(host.fieldKind()),
    )

    private fun buildAction(state: FixButtonState) = ToolbarView.Action(
        id = ToolbarView.ActionId.AI_FIX,
        glyph = if (state == FixButtonState.UNDO) ToolbarView.Glyph.UNDO else ToolbarView.Glyph.SPARKLE,
        label = context.getString(
            when (state) {
                FixButtonState.RUNNING -> R.string.toolbar_ai_fix_working
                FixButtonState.UNDO -> R.string.toolbar_ai_fix_undo
                else -> R.string.toolbar_ai_fix
            },
        ),
        contentDescription = context.getString(
            when (state) {
                FixButtonState.RUNNING -> R.string.a11y_ai_fix_working
                FixButtonState.UNDO -> R.string.a11y_ai_fix_undo
                FixButtonState.DISABLED -> disabledDescription()
                FixButtonState.IDLE -> R.string.a11y_ai_fix
            },
        ),
        state = ToolbarView.stateFor(state),
    )

    private fun disabledDescription(): Int = when (host.fieldKind()) {
        FieldKind.PASSWORD -> R.string.a11y_ai_fix_off_password
        FieldKind.EMAIL, FieldKind.URI -> R.string.a11y_ai_fix_off_address
        else -> R.string.a11y_ai_fix_off_number
    }

    // --------------------------------------------------------------- messages

    private fun say(resId: Int) {
        toolbar?.showMessage(context.getString(resId))
    }

    private fun refusalForKind(kind: FieldKind): FixRefusal = when (kind) {
        FieldKind.PASSWORD -> FixRefusal.PASSWORD_FIELD
        FieldKind.EMAIL, FieldKind.URI -> FixRefusal.ADDRESS_FIELD
        else -> FixRefusal.NUMERIC_FIELD
    }

    private fun refusalMessage(refusal: FixRefusal?): Int = when (refusal) {
        FixRefusal.PASSWORD_FIELD -> R.string.ai_fix_off_password
        FixRefusal.ADDRESS_FIELD -> R.string.ai_fix_off_address
        FixRefusal.NUMERIC_FIELD -> R.string.ai_fix_off_number
        FixRefusal.EMPTY_FIELD -> R.string.ai_fix_nothing_to_fix
        FixRefusal.TOO_LONG -> R.string.ai_fix_too_long
        null -> R.string.ai_fix_unavailable
    }

    /**
     * Says what happened, in the user's words. A fix is never allowed to be
     * silent: a button that appears to do nothing is worse than one that admits
     * it only managed half the job (VB-235).
     *
     * Mechanical and editorial changes are reported differently on purpose.
     * Tidying up spacing needs no more than an acknowledgement; a word that was
     * swapped is an opinion the user is entitled to hear about before they hit
     * send.
     */
    private fun announceApplied(result: FixResult) {
        val editorial = result.editorialCount
        val message = when {
            result.smart == SmartTier.NOT_INSTALLED || result.smart == SmartTier.OFF ->
                context.getString(R.string.ai_fix_rules_only_missing)
            result.smart == SmartTier.UNAVAILABLE || result.smart == SmartTier.REJECTED ->
                context.getString(R.string.ai_fix_rules_only_failed)
            result.smart == SmartTier.TIMED_OUT ->
                context.getString(R.string.ai_fix_rules_only_slow)
            result.smart == SmartTier.TOO_LONG ->
                context.getString(R.string.ai_fix_rules_only_long)
            editorial > 0 -> context.resources.getQuantityString(
                R.plurals.ai_fix_done_editorial,
                editorial,
                editorial,
            )
            else -> context.getString(R.string.ai_fix_done_mechanical)
        }
        toolbar?.showMessage(message)
    }

    private fun unchangedMessage(tier: SmartTier): Int = when (tier) {
        SmartTier.NOT_INSTALLED, SmartTier.OFF -> R.string.ai_fix_nothing_to_fix_missing
        SmartTier.TIMED_OUT -> R.string.ai_fix_rules_only_slow
        SmartTier.UNAVAILABLE -> R.string.ai_fix_rules_only_failed
        SmartTier.TOO_LONG -> R.string.ai_fix_rules_only_long
        else -> R.string.ai_fix_nothing_to_fix
    }

    private fun now(): Long = SystemClock.uptimeMillis()

    companion object {
        private const val TAG = "VBoardAiFix"

        /** Ceiling on one whole-field fix, however many chunks it takes. */
        private const val TOTAL_BUDGET_MS = 20_000L

        /** Bound on the before/after-cursor fallback read. */
        private const val FALLBACK_LIMIT = FixChunker.MAX_FIELD_CHARS

        private const val UNDO_TIMER_SLACK_MS = 100L

        /** Edits reported this soon after our own write are our own write. */
        private const val SELF_WRITE_GUARD_MS = 250L
    }
}
