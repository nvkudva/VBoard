package com.vboard.app.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vboard.app.keyboard.ThemeMode
import com.vboard.core.session.SilenceTimeout
import com.vboard.core.suggest.AutocorrectMode
import com.vboard.core.text.CleanupOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore by preferencesDataStore(name = "vboard_settings")

/** Everything the IME needs per keystroke, resolved once per settings change. */
data class SettingsSnapshot(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hapticsEnabled: Boolean = true,
    val keyPreviewEnabled: Boolean = true,
    val soundEnabled: Boolean = false,
    val autoCapitalize: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val autocorrectMode: AutocorrectMode = AutocorrectMode.CONSERVATIVE,
    val suggestionsEnabled: Boolean = true,
    val numberRowEnabled: Boolean = false,
    // Clipboard
    val clipboardHistoryEnabled: Boolean = true,
    val clipboardSuggestionsEnabled: Boolean = true,
    /**
     * When the user last asked for the whole clip history to be deleted. The
     * settings screen and the IME live in one process but not one object graph,
     * so this timestamp is how "delete all" reaches a keyboard that is already
     * running: the IME watches it change and drops what it holds in memory.
     */
    val clipboardClearedAt: Long = 0L,
    // Voice
    /**
     * Dictate without leaving the keyboard: the mic key starts listening in
     * place, the keys stay live, and spoken words land at the cursor. Off falls
     * back to the full-screen voice bar with its streaming transcript.
     */
    val inlineDictation: Boolean = true,
    val removeFillers: Boolean = true,
    val aggressiveFillers: Boolean = false,
    val resolveSelfCorrections: Boolean = true,
    val autoPunctuate: Boolean = true,
    val spokenCommands: Boolean = true,
    val rawTranscriptMode: Boolean = false,
    val llmRefineEnabled: Boolean = false,
    /**
     * How long the mic may stay open with nothing being said. See
     * [SilenceTimeout]: the shipped default is 8s, not the 30s the code used to
     * hard-code, which matched neither spec.
     */
    val silenceTimeout: SilenceTimeout = SilenceTimeout.DEFAULT,
) {
    fun cleanupOptions(): CleanupOptions =
        if (rawTranscriptMode) {
            CleanupOptions.RAW
        } else {
            CleanupOptions(
                removeFillers = removeFillers,
                aggressiveFillers = aggressiveFillers,
                resolveSelfCorrections = resolveSelfCorrections,
                collapseRepetitions = true,
                autoPunctuate = autoPunctuate,
                autoCapitalize = autoCapitalize,
                spokenCommands = spokenCommands,
            )
        }
}

class SettingsRepository(context: Context, scope: CoroutineScope) {

    private val dataStore = context.applicationContext.dataStore

    object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val HAPTICS = booleanPreferencesKey("haptics")
        val KEY_PREVIEW = booleanPreferencesKey("key_preview")
        val SOUND = booleanPreferencesKey("sound")
        val AUTO_CAP = booleanPreferencesKey("auto_capitalize")
        val DOUBLE_SPACE = booleanPreferencesKey("double_space_period")
        val AUTOCORRECT = stringPreferencesKey("autocorrect_mode")
        val SUGGESTIONS = booleanPreferencesKey("suggestions")
        val NUMBER_ROW = booleanPreferencesKey("number_row")
        val CLIPBOARD_HISTORY = booleanPreferencesKey("clipboard_history")
        val CLIPBOARD_SUGGESTIONS = booleanPreferencesKey("clipboard_suggestions")
        val CLIPBOARD_CLEARED_AT = longPreferencesKey("clipboard_cleared_at")
        val INLINE_DICTATION = booleanPreferencesKey("inline_dictation")
        val FILLERS = booleanPreferencesKey("remove_fillers")
        val FILLERS_AGGRESSIVE = booleanPreferencesKey("aggressive_fillers")
        val SELF_CORRECT = booleanPreferencesKey("self_corrections")
        val AUTO_PUNCT = booleanPreferencesKey("auto_punctuate")
        val SPOKEN_COMMANDS = booleanPreferencesKey("spoken_commands")
        val RAW_MODE = booleanPreferencesKey("raw_transcript")
        val LLM_REFINE = booleanPreferencesKey("llm_refine")
        val SILENCE_TIMEOUT = stringPreferencesKey("voice_silence_timeout")
    }

    val flow: Flow<SettingsSnapshot> = dataStore.data.map { prefs -> prefs.toSnapshot() }

    /** Hot cached snapshot for the IME's key-press path (never blocks). */
    val snapshot: StateFlow<SettingsSnapshot> =
        flow.stateIn(scope, SharingStarted.Eagerly, SettingsSnapshot())

    private fun Preferences.toSnapshot() = SettingsSnapshot(
        themeMode = enumOrDefault(this[Keys.THEME], ThemeMode.SYSTEM),
        hapticsEnabled = this[Keys.HAPTICS] ?: true,
        keyPreviewEnabled = this[Keys.KEY_PREVIEW] ?: true,
        soundEnabled = this[Keys.SOUND] ?: false,
        autoCapitalize = this[Keys.AUTO_CAP] ?: true,
        doubleSpacePeriod = this[Keys.DOUBLE_SPACE] ?: true,
        autocorrectMode = enumOrDefault(this[Keys.AUTOCORRECT], AutocorrectMode.CONSERVATIVE),
        suggestionsEnabled = this[Keys.SUGGESTIONS] ?: true,
        numberRowEnabled = this[Keys.NUMBER_ROW] ?: false,
        clipboardHistoryEnabled = this[Keys.CLIPBOARD_HISTORY] ?: true,
        clipboardSuggestionsEnabled = this[Keys.CLIPBOARD_SUGGESTIONS] ?: true,
        clipboardClearedAt = this[Keys.CLIPBOARD_CLEARED_AT] ?: 0L,
        inlineDictation = this[Keys.INLINE_DICTATION] ?: true,
        removeFillers = this[Keys.FILLERS] ?: true,
        aggressiveFillers = this[Keys.FILLERS_AGGRESSIVE] ?: false,
        resolveSelfCorrections = this[Keys.SELF_CORRECT] ?: true,
        autoPunctuate = this[Keys.AUTO_PUNCT] ?: true,
        spokenCommands = this[Keys.SPOKEN_COMMANDS] ?: true,
        rawTranscriptMode = this[Keys.RAW_MODE] ?: false,
        llmRefineEnabled = this[Keys.LLM_REFINE] ?: false,
        silenceTimeout = enumOrDefault(this[Keys.SILENCE_TIMEOUT], SilenceTimeout.DEFAULT),
    )

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
        raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

    suspend fun setBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    suspend fun setString(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
    }

    suspend fun setLong(key: Preferences.Key<Long>, value: Long) {
        dataStore.edit { it[key] = value }
    }
}
