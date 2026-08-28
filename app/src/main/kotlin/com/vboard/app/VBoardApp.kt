package com.vboard.app

import android.app.Application
import com.vboard.app.models.AndroidFetcher
import com.vboard.app.models.ModelStore
import com.vboard.app.settings.SettingsRepository
import com.vboard.core.model.PackInstaller
import com.vboard.core.suggest.Lexicon
import com.vboard.core.suggest.SuggestionEngine
import com.vboard.core.suggest.UserHistory
import com.vboard.core.text.TranscriptCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class VBoardApp : Application() {

    lateinit var appScope: CoroutineScope
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var modelStore: ModelStore
        private set
    lateinit var packInstaller: PackInstaller
        private set

    val cleaner = TranscriptCleaner()

    /** Loaded off the main thread; IME falls back to no-suggestions until ready. */
    @Volatile
    var suggestionEngine: SuggestionEngine? = null
        private set

    private var userHistory: UserHistory = UserHistory()
    private val historyFile: File by lazy { File(filesDir, "user_history.txt") }
    private val historyMutex = Mutex()
    private var historySaveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settings = SettingsRepository(this, appScope)
        modelStore = ModelStore(this)
        packInstaller = PackInstaller(
            rootDir = modelStore.rootDir.toPath(),
            fetcher = AndroidFetcher(),
            freeBytes = { filesDir.usableSpace },
        )
        appScope.launch {
            val history = runCatching {
                if (historyFile.exists()) {
                    UserHistory.restore(historyFile.readText())
                } else {
                    UserHistory()
                }
            }.getOrElse { UserHistory() }
            userHistory = history
            val lexicon = Lexicon.english()
            suggestionEngine = SuggestionEngine(lexicon, history)
        }
    }

    /** Debounced persistence of learned words (never on the key-press path). */
    fun scheduleHistorySave() {
        historySaveJob?.cancel()
        historySaveJob = appScope.launch {
            kotlinx.coroutines.delay(5_000)
            historyMutex.withLock {
                runCatching { historyFile.writeText(userHistory.snapshot()) }
            }
        }
    }
}
