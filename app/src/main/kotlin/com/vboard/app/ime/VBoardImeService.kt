package com.vboard.app.ime

import android.animation.ValueAnimator
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.vboard.app.VBoardApp
import com.vboard.app.keyboard.EmojiPanelView
import com.vboard.app.keyboard.KeyAction
import com.vboard.app.keyboard.KeyIcon
import com.vboard.app.keyboard.KeyboardLayer
import com.vboard.app.keyboard.KeyboardLayouts
import com.vboard.app.keyboard.KeyboardMetrics
import com.vboard.app.keyboard.KeyboardTheme
import com.vboard.app.keyboard.KeyboardView
import com.vboard.app.keyboard.SuggestionStripView
import com.vboard.app.onboarding.OnboardingActivity
import com.vboard.app.settings.SettingsSnapshot
import com.vboard.app.voice.VoiceBarView
import com.vboard.app.voice.VoiceSessionController
import com.vboard.core.suggest.Suggestion
import com.vboard.core.suggest.SuggestionRequest
import com.vboard.core.suggest.SuggestionResult
import com.vboard.core.text.CommitPlanner
import com.vboard.core.text.FieldKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VBoardImeService : InputMethodService() {

    private val app get() = application as VBoardApp

    private lateinit var serviceScope: CoroutineScope
    private var suggestionJob: Job? = null

    private lateinit var root: LinearLayout
    private lateinit var strip: SuggestionStripView
    private lateinit var contentFrame: FrameLayout
    private lateinit var keyboardView: KeyboardView
    private var emojiPanel: EmojiPanelView? = null
    private var voiceBar: VoiceBarView? = null
    private var voiceController: VoiceSessionController? = null

    private var theme: KeyboardTheme = KeyboardTheme.LIGHT
    private var profile: EditorProfile = EditorProfile.from(null)
    private var layer: KeyboardLayer = KeyboardLayer.LETTERS

    /** Current uncommitted word (composing region). */
    private val composing = StringBuilder()
    private var pendingAutocorrect: Suggestion? = null

    /** Last autocorrect applied, for backspace revert: (typed, corrected). */
    private var lastAutocorrect: Pair<String, String>? = null

    /** Committed voice utterances by index, for scratch-that / refinement replace. */
    private val voiceCommits = HashMap<Int, String>()

    private val settings: SettingsSnapshot get() = app.settings.snapshot.value

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope.launch {
            app.settings.snapshot.collect { applySettings(it) }
        }
    }

    override fun onDestroy() {
        voiceController?.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ views

    override fun onCreateInputView(): View {
        // Everything below is rebuilt on every configuration change, but voiceBar
        // and emojiPanel are long-lived fields whose attach guards test
        // `parent == null`. After a rotation their parent is the OLD, detached
        // frame, so they were never added to the new one: the mic key produced a
        // blank keyboard with a live microphone behind it. Drop them here so the
        // next press builds fresh views; removeView also detaches them, which is
        // what stops their animators.
        voiceController?.cancelSessionSilently()
        detachPanel(voiceBar)
        voiceBar?.listener = null
        voiceBar = null
        detachPanel(emojiPanel)
        emojiPanel?.listener = null
        emojiPanel = null

        theme = KeyboardTheme.forContext(this, settings.themeMode)

        keyboardView = KeyboardView(this, theme).apply {
            listener = keyListener
        }
        strip = SuggestionStripView(this, theme).apply {
            listener = SuggestionStripView.Listener { pickSuggestion(it) }
        }
        contentFrame = FrameLayout(this)
        contentFrame.addView(
            keyboardView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.bgKeyboard)
            addView(
                strip,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(
                contentFrame,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        applyWindowInsetsPadding()
        return root
    }

    /** Detaches a panel from whatever frame it currently belongs to, if any. */
    private fun detachPanel(view: View?) {
        (view?.parent as? ViewGroup)?.removeView(view)
    }

    private fun applyWindowInsetsPadding() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bottom = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemGestures(),
            ).bottom
            v.setPadding(0, 0, 0, maxOf(bottom, dp(8)))
            insets
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Fires for every new editor session, including restarts that reuse the
     * existing view — [onStartInputView] alone therefore misses some of them,
     * and stale composing state leaks between fields.
     */
    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        profile = EditorProfile.from(info)
        resetEditingState()
    }

    /**
     * The counterpart of [onStartInput]. [onFinishInputView] is a different event
     * (the view being hidden) and does not fire on every editor teardown, so the
     * shadow composing state has to be dropped here too.
     */
    override fun onFinishInput() {
        endVoiceSession(hideOnly = true, finalizePending = false)
        resetEditingState()
        super.onFinishInput()
    }

    /**
     * The editor is the source of truth for the composing region; [composing] is
     * only a shadow copy. Nothing used to reconcile the two, so any cursor move,
     * external edit or autofill left us confidently editing text that had moved —
     * committing the wrong word, or reverting an autocorrect over a different
     * region entirely.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        val moved = newSelStart != oldSelStart || newSelEnd != oldSelEnd
        if (composing.isEmpty()) {
            // A backspace-revert is only valid immediately after the autocorrect
            // that produced it, at the cursor where it happened.
            if (moved) lastAutocorrect = null
            return
        }
        // candidatesStart == -1 means the editor dropped the composing region.
        val insideComposing = candidatesStart >= 0 &&
            newSelStart == newSelEnd &&
            newSelStart >= candidatesStart &&
            newSelStart <= candidatesEnd
        if (insideComposing) return

        composing.setLength(0)
        pendingAutocorrect = null
        lastAutocorrect = null
        // Let go of a region we no longer own rather than keep composing into it.
        currentInputConnection?.finishComposingText()
        if (viewsReady()) refreshSuggestions()
    }

    /**
     * Drops every piece of state derived from the current editor.
     *
     * voiceCommits is deliberately kept across selection changes: it is only ever
     * used after verifying the text before the cursor still matches, so a stale
     * entry is inert rather than dangerous. It is cleared here, where the editor
     * itself is changing.
     */
    private fun resetEditingState() {
        composing.setLength(0)
        pendingAutocorrect = null
        lastAutocorrect = null
        voiceCommits.clear()
    }

    /** True once [onCreateInputView] has built the view hierarchy. */
    private fun viewsReady(): Boolean = ::keyboardView.isInitialized && ::strip.isInitialized

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        profile = EditorProfile.from(info)
        resetEditingState()

        theme = KeyboardTheme.forContext(this, settings.themeMode)
        keyboardView.applyTheme(theme)
        strip.applyTheme(theme)
        root.setBackgroundColor(theme.bgKeyboard)

        setLayer(KeyboardLayer.LETTERS)
        keyboardView.enterIcon = profile.enterIcon
        keyboardView.micEnabled = profile.fieldKind.allowsVoice
        endVoiceSession(hideOnly = true, finalizePending = false)
        updateShiftForContext()
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Anything already spoken must still reach the field (VB-107), so the
        // buffered audio is finalized rather than dropped on the floor.
        endVoiceSession(hideOnly = true, finalizePending = true)
        commitComposingAsIs()
        super.onFinishInputView(finishingInput)
    }

    private fun applySettings(s: SettingsSnapshot) {
        if (!::keyboardView.isInitialized) return
        keyboardView.hapticsEnabled = s.hapticsEnabled
        keyboardView.keyPreviewEnabled = s.keyPreviewEnabled
        val newTheme = KeyboardTheme.forContext(this, s.themeMode)
        if (newTheme != theme) {
            theme = newTheme
            keyboardView.applyTheme(theme)
            strip.applyTheme(theme)
            emojiPanel?.applyTheme(theme)
            voiceBar?.applyTheme(theme)
            root.setBackgroundColor(theme.bgKeyboard)
        }
    }

    // ------------------------------------------------------------- key events

    private val keyListener = object : KeyboardView.Listener {
        override fun onKeyAction(action: KeyAction, shifted: Boolean) {
            when (action) {
                is KeyAction.Text -> onText(action.text, shifted)
                KeyAction.Backspace -> onBackspace()
                KeyAction.Enter -> onEnter()
                KeyAction.Space -> onSpace()
                KeyAction.Mic -> startVoice()
                KeyAction.ToSymbols -> setLayer(KeyboardLayer.SYMBOLS)
                KeyAction.ToSymbols2 -> setLayer(KeyboardLayer.SYMBOLS2)
                KeyAction.ToLetters -> setLayer(KeyboardLayer.LETTERS)
                KeyAction.ToEmoji -> setLayer(KeyboardLayer.EMOJI)
                KeyAction.Shift -> Unit // handled inside the view
            }
        }

        override fun onKeyLongPressText(text: String) {
            onText(text, shifted = false, fromLongPress = true)
        }

        override fun onSpacebarCursorMove(steps: Int) {
            if (steps == 0) return
            commitComposingAsIs()
            val ic = currentInputConnection ?: return
            val key = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            repeat(kotlin.math.abs(steps)) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            }
        }

        override fun onShiftChanged(state: KeyboardView.ShiftState) = Unit
    }

    private fun onText(raw: String, shifted: Boolean, fromLongPress: Boolean = false) {
        val ic = currentInputConnection ?: return
        lastAutocorrect = null
        val text = if (shifted && layer == KeyboardLayer.LETTERS && !fromLongPress) raw.uppercase() else raw
        val isWordChar = text.length == 1 && (text[0].isLetter() || text[0] == '\'') ||
            fromLongPress && text.all { it.isLetter() }

        if (isWordChar && suggestionsActive()) {
            composing.append(text)
            ic.setComposingText(composing, 1)
            keyboardView.consumeShift()
            refreshSuggestions()
            return
        }

        // Separator or symbol: settle the current word first.
        val isTerminator = text.length == 1 && text[0] in SENTENCE_ENDER_CHARS
        val attachesToWord = text.length == 1 && text[0] in ATTACHING_PUNCT
        finishComposing(applyAutocorrect = attachesToWord || isTerminator)
        ic.commitText(text, 1)
        keyboardView.consumeShift()
        updateShiftForContext()
        refreshSuggestions()
    }

    private fun onSpace() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            finishComposing(applyAutocorrect = true)
            ic.commitText(" ", 1)
        } else {
            if (settings.doubleSpacePeriod &&
                profile.fieldKind.allowsAutoCapitalize &&
                CommitPlanner.doubleSpacePeriodApplies(precedingText(4))
            ) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
            } else {
                ic.commitText(" ", 1)
            }
        }
        updateShiftForContext()
        refreshSuggestions()
    }

    private fun onEnter() {
        val ic = currentInputConnection ?: return
        finishComposing(applyAutocorrect = false)
        val action = profile.imeActionId
        if (!profile.isMultiline && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
        updateShiftForContext()
        refreshSuggestions()
    }

    private fun onBackspace() {
        val ic = currentInputConnection ?: return
        lastAutocorrect?.let { (typed, corrected) ->
            // One backspace right after autocorrect reverts to the literal word.
            val expect = "$corrected "
            val before = ic.getTextBeforeCursor(expect.length, 0)
            if (before?.toString() == expect) {
                ic.deleteSurroundingText(expect.length, 0)
                ic.commitText("$typed ", 1)
                lastAutocorrect = null
                return
            }
            lastAutocorrect = null
        }
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            ic.setComposingText(composing, 1)
            refreshSuggestions()
            if (composing.isEmpty()) updateShiftForContext()
            return
        }
        // Delete one grapheme-ish unit (handle surrogate pairs).
        val before = ic.getTextBeforeCursor(2, 0)
        val len = when {
            before.isNullOrEmpty() -> 0
            before.length >= 2 && Character.isSurrogatePair(before[before.length - 2], before[before.length - 1]) -> 2
            else -> 1
        }
        if (len > 0) ic.deleteSurroundingText(len, 0)
        updateShiftForContext()
        refreshSuggestions()
    }

    // ---------------------------------------------------------- suggestions

    private fun suggestionsActive(): Boolean =
        settings.suggestionsEnabled && profile.fieldKind.allowsSuggestions

    private fun refreshSuggestions() {
        if (!suggestionsActive() || app.suggestionEngine == null) {
            strip.clearSuggestions()
            pendingAutocorrect = null
            return
        }
        val engine = app.suggestionEngine ?: return
        val composingWord = composing.toString()
        val prevWord = previousCommittedWord()
        suggestionJob?.cancel()
        suggestionJob = serviceScope.launch {
            // Every touch of the engine goes through the app's single suggestion
            // thread; UserHistory is an access-ordered LRU, so even a read
            // structurally mutates it and it cannot be shared across threads.
            val result: SuggestionResult = withContext(app.suggestDispatcher) {
                engine.suggest(
                    SuggestionRequest(
                        composing = composingWord,
                        previousWord = prevWord,
                        fieldKind = profile.fieldKind,
                        mode = settings.autocorrectMode,
                    ),
                )
            }
            pendingAutocorrect = result.autocorrect
            strip.setSuggestions(result.suggestions, result.autocorrect != null)
        }
    }

    private fun pickSuggestion(suggestion: Suggestion) {
        val ic = currentInputConnection ?: return
        ic.setComposingText(suggestion.text, 1)
        ic.finishComposingText()
        ic.commitText(" ", 1)
        learn(suggestion.text)
        composing.setLength(0)
        pendingAutocorrect = null
        updateShiftForContext()
        refreshSuggestions()
    }

    /** Ends the composing region, optionally applying the pending autocorrect. */
    private fun finishComposing(applyAutocorrect: Boolean) {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) return
        val typed = composing.toString()
        val correction = pendingAutocorrect?.takeIf {
            applyAutocorrect && settings.autocorrectMode != com.vboard.core.suggest.AutocorrectMode.OFF
        }
        val committed = correction?.text ?: typed
        ic.setComposingText(committed, 1)
        ic.finishComposingText()
        if (correction != null && correction.text != typed) {
            lastAutocorrect = typed to correction.text
        }
        learn(committed)
        composing.setLength(0)
        pendingAutocorrect = null
    }

    private fun commitComposingAsIs() {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) return
        ic.finishComposingText()
        composing.setLength(0)
        pendingAutocorrect = null
    }

    private fun learn(word: String) {
        if (!profile.fieldKind.allowsLearning || profile.noPersonalizedLearning) return
        if (word.length < 2 || word.any { it.isDigit() }) return
        val engine = app.suggestionEngine ?: return
        val prev = previousCommittedWord()
        serviceScope.launch(app.suggestDispatcher) {
            engine.recordCommittedWord(prev, word)
            app.scheduleHistorySave()
        }
    }

    // ------------------------------------------------------------- shift/caps

    private fun updateShiftForContext() {
        if (!settings.autoCapitalize || !profile.fieldKind.allowsAutoCapitalize) return
        if (keyboardView.shiftState == KeyboardView.ShiftState.CAPS_LOCK) return
        val before = precedingText(3).trimEnd(' ')
        val shouldCap = before.isEmpty() ||
            before.last() in SENTENCE_ENDER_CHARS ||
            before.last() == '\n'
        keyboardView.shiftState =
            if (shouldCap) KeyboardView.ShiftState.SHIFT else KeyboardView.ShiftState.OFF
    }

    private fun precedingText(chars: Int): String =
        currentInputConnection?.getTextBeforeCursor(chars.coerceAtLeast(1) * 8, 0)?.toString() ?: ""

    private fun previousCommittedWord(): String? {
        val before = precedingText(6).trimEnd()
        if (before.isEmpty()) return null
        val lastToken = before.takeLastWhile { it.isLetter() || it == '\'' }
        return lastToken.ifEmpty { null }
    }

    // ------------------------------------------------------------------ layers

    private fun setLayer(newLayer: KeyboardLayer) {
        layer = newLayer
        if (newLayer == KeyboardLayer.EMOJI) {
            showEmojiPanel()
            return
        }
        hideEmojiPanel()
        keyboardView.layout = KeyboardLayouts.forLayer(newLayer)
        keyboardView.shiftState = KeyboardView.ShiftState.OFF
        if (newLayer == KeyboardLayer.LETTERS) updateShiftForContext()
    }

    private fun showEmojiPanel() {
        val panel = emojiPanel ?: EmojiPanelView(this, theme, keyboardView.height.takeIf { it > 0 }
            ?: dp(298)).also { p ->
            p.listener = object : EmojiPanelView.Listener {
                override fun onEmoji(emoji: String) {
                    commitComposingAsIs()
                    currentInputConnection?.commitText(emoji, 1)
                }

                override fun onBackToLetters() = setLayer(KeyboardLayer.LETTERS)
                override fun onBackspace() = onBackspaceFromPanel()
            }
            emojiPanel = p
        }
        if (panel.parent == null) contentFrame.addView(panel)
        keyboardView.visibility = View.GONE
        panel.visibility = View.VISIBLE
        strip.clearSuggestions()
    }

    private fun onBackspaceFromPanel() {
        onBackspace()
    }

    private fun hideEmojiPanel() {
        emojiPanel?.visibility = View.GONE
        keyboardView.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------- voice

    private fun startVoice() {
        if (!profile.fieldKind.allowsVoice) return
        commitComposingAsIs()
        strip.clearSuggestions()

        val controller = voiceController ?: VoiceSessionController(
            service = this,
            app = app,
            host = voiceHost,
        ).also { voiceController = it }

        val bar = voiceBar ?: VoiceBarView(this, theme).also { bar ->
            voiceBar = bar
            bar.listener = object : VoiceBarView.Listener {
                override fun onOrbTapped() = controller.stopAndFinalize()
                override fun onBackToKeyboard() = controller.cancelSession()
                override fun onErrorAction(kind: VoiceBarView.ErrorActionKind) {
                    when (kind) {
                        VoiceBarView.ErrorActionKind.OPEN_PERMISSION,
                        VoiceBarView.ErrorActionKind.OPEN_DOWNLOAD,
                        -> {
                            val intent = Intent(this@VBoardImeService, OnboardingActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                            controller.cancelSession()
                        }
                        VoiceBarView.ErrorActionKind.DISMISS -> controller.cancelSession()
                    }
                }
            }
        }
        if (bar.parent == null) {
            contentFrame.addView(
                bar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    dp(KeyboardMetrics.VOICE_BAR_HEIGHT_DP.toInt()),
                ),
            )
        }
        // Never open onto the previous session's (possibly another app's) text.
        bar.resetForSession()
        animateToVoiceBar(bar)
        controller.startSession(profile.fieldKind, settings)
    }

    private val voiceHost = object : VoiceSessionController.Host {
        override fun precedingText(): String = this@VBoardImeService.precedingText(16)

        override fun fieldKind(): FieldKind = profile.fieldKind

        override fun updatePartial(text: String) {
            voiceBar?.showPartial(text)
        }

        override fun commitUtterance(index: Int, text: String) {
            val ic = currentInputConnection ?: run {
                // The editor went away before the final pass returned. Nothing to
                // do here, but it must not be invisible in a bug report.
                Log.w(TAG, "no input connection; dictated utterance $index dropped")
                return
            }
            val joined = CommitPlanner.joinForInsertion(this@VBoardImeService.precedingText(4), text)
            ic.commitText(joined, 1)
            voiceCommits[index] = joined
            voiceBar?.showCommitted(text)
        }

        override fun replaceUtterance(index: Int, newText: String) {
            val ic = currentInputConnection ?: return
            val old = voiceCommits[index] ?: return
            val before = ic.getTextBeforeCursor(old.length, 0)?.toString() ?: return
            if (before != old) return // user edited since: discard refinement
            val joined = CommitPlanner.joinForInsertion(
                this@VBoardImeService.precedingText(4).removeSuffix(old),
                newText,
            )
            ic.beginBatchEdit()
            ic.deleteSurroundingText(old.length, 0)
            ic.commitText(joined, 1)
            ic.endBatchEdit()
            voiceCommits[index] = joined
        }

        override fun deleteLastUtterance() {
            val ic = currentInputConnection ?: return
            val lastIndex = voiceCommits.keys.maxOrNull() ?: return
            val old = voiceCommits.remove(lastIndex) ?: return
            val before = ic.getTextBeforeCursor(old.length, 0)?.toString() ?: return
            if (before == old) ic.deleteSurroundingText(old.length, 0)
        }

        override fun onSessionEnded() {
            endVoiceSession(hideOnly = false)
        }

        override fun showError(message: String, action: VoiceBarView.ErrorActionKind) {
            voiceBar?.showError(message, action)
        }

        override fun showListening() {
            voiceBar?.showListening()
        }

        override fun showFinalizing() {
            voiceBar?.showFinalizing()
        }

        override fun showRefining() {
            voiceBar?.showRefining()
        }

        override fun onAmplitude(rms: Float) {
            voiceBar?.setAmplitude(rms)
        }
    }

    private fun animateToVoiceBar(bar: VoiceBarView) {
        val from = keyboardView.height.takeIf { it > 0 } ?: dp(298)
        val to = dp(KeyboardMetrics.VOICE_BAR_HEIGHT_DP.toInt())
        keyboardView.visibility = View.GONE
        emojiPanel?.visibility = View.GONE
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        val lp = bar.layoutParams
        lp.height = from
        bar.layoutParams = lp
        ValueAnimator.ofInt(from, to).apply {
            duration = 250
            interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f) // M3 emphasized decelerate
            addUpdateListener {
                lp.height = it.animatedValue as Int
                bar.layoutParams = lp
                bar.alpha = it.animatedFraction
            }
            start()
        }
    }

    /**
     * Returns the UI to the keyboard. [hideOnly] is used from lifecycle
     * teardown paths where the controller is already stopping (or must be
     * stopped silently); the non-hideOnly path is the controller telling us a
     * session ended normally. [finalizePending] asks the controller to commit
     * what has already been spoken instead of discarding it.
     */
    private fun endVoiceSession(hideOnly: Boolean, finalizePending: Boolean = false) {
        if (hideOnly) {
            if (finalizePending) {
                voiceController?.finishSession()
            } else {
                voiceController?.cancelSessionSilently()
            }
        }
        // Resetting the bar here is what stops its infinite breathing animator
        // (the view stays attached, so onDetachedFromWindow never runs) and
        // clears one app's transcript before it can be shown in the next.
        voiceBar?.resetForSession()
        voiceBar?.visibility = View.GONE
        if (!viewsReady()) return
        keyboardView.visibility = View.VISIBLE
        keyboardView.alpha = 1f
        if (!hideOnly) {
            updateShiftForContext()
            refreshSuggestions()
        }
    }

    companion object {
        private const val TAG = "VBoardIme"
        private val SENTENCE_ENDER_CHARS = setOf('.', '!', '?')
        private val ATTACHING_PUNCT = setOf('.', ',', '!', '?', ';', ':', ')', ']', '}')
    }
}
