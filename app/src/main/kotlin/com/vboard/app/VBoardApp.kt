package com.vboard.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.vboard.app.models.AndroidFetcher
import com.vboard.app.models.ModelStore
import com.vboard.app.settings.SettingsRepository
import com.vboard.app.voice.VoiceEngines
import com.vboard.core.model.PackInstaller
import com.vboard.core.suggest.Lexicon
import com.vboard.core.suggest.SuggestionEngine
import com.vboard.core.suggest.UserHistory
import com.vboard.core.text.TranscriptCleaner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

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

    /**
     * Single thread that owns the suggestion engine and, through it, [UserHistory].
     *
     * UserHistory documents itself as confined to one thread and means it: its
     * maps are access-ordered LRUs, so a plain lookup restructures them. It was
     * being reached from three different Dispatchers.Default threads at once
     * (suggest on every keystroke, recordCommittedWord on every commit, snapshot
     * from the persistence job). Confinement is the fix; scattering @Synchronized
     * over individual methods would not have made an iteration over a map another
     * thread is re-linking any safer.
     */
    private val suggestExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vboard-suggest").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    val suggestDispatcher: CoroutineDispatcher = suggestExecutor.asCoroutineDispatcher()

    /** Loaded off the main thread; IME falls back to no-suggestions until ready. */
    @Volatile
    var suggestionEngine: SuggestionEngine? = null
        private set

    /** Touched only on [suggestDispatcher]. */
    private var userHistory: UserHistory = UserHistory()

    private val historyFile: File by lazy { File(filesDir, HISTORY_NAME) }
    private val historyTempFile: File by lazy { File(filesDir, "$HISTORY_NAME.tmp") }
    private val historyMutex = Mutex()
    private var historySaveJob: Job? = null

    /** When the oldest unsaved edit happened; 0 when everything is persisted. */
    @Volatile
    private var historyDirtySince = 0L

    /**
     * Set when an existing history file could not be parsed. Substituting an empty
     * history and then saving over the top turned a recoverable read failure into
     * permanent data loss, so the bad file is left alone for post-mortem instead.
     */
    @Volatile
    private var historyReadFailed = false

    override fun onCreate() {
        super.onCreate()
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settings = SettingsRepository(this, appScope)
        modelStore = ModelStore(this)
        packInstaller = PackInstaller(
            rootDir = modelStore.rootDir.toPath(),
            fetcher = AndroidFetcher(),
            // The volume the packs actually land on, which is no longer
            // necessarily the one filesDir is on.
            freeBytes = { modelStore.rootDir.usableSpace },
        )
        // Packs installed by an older build still live in app data, which an
        // uninstall would take with it. Moving ~1GB is not startup work: this
        // process keeps reading them where they are and the copy is picked up on
        // the next start.
        appScope.launch(Dispatchers.IO) { modelStore.migrateFromInternalStorage() }
        appScope.launch(suggestDispatcher) {
            val history = if (historyFile.exists()) {
                runCatching { UserHistory.restore(historyFile.readText()) }
                    .getOrElse { e ->
                        historyReadFailed = true
                        Log.w(TAG, "learned-word history unreadable; keeping the file", e)
                        UserHistory()
                    }
            } else {
                UserHistory()
            }
            userHistory = history
            val lexicon = Lexicon.english()
            suggestionEngine = SuggestionEngine(lexicon, history)
        }
    }

    /**
     * Debounced persistence of learned words (never on the key-press path).
     *
     * The debounce is bounded: an uninterrupted typing session used to reset the
     * timer on every word, so the save could be postponed for as long as the user
     * kept going and everything learned in between died with the process.
     */
    fun scheduleHistorySave() {
        val now = System.currentTimeMillis()
        if (historyDirtySince == 0L) historyDirtySince = now
        val pending = now - historyDirtySince
        historySaveJob?.cancel()
        historySaveJob = appScope.launch {
            val wait = minOf(SAVE_DEBOUNCE_MS, (MAX_SAVE_DELAY_MS - pending).coerceAtLeast(0L))
            if (wait > 0) delay(wait)
            persistHistory()
        }
    }

    private suspend fun persistHistory() {
        historyMutex.withLock {
            if (historyReadFailed) return
            val data = withContext(suggestDispatcher) { userHistory.snapshot() }
            try {
                // Write-then-rename. writeText truncates first, so a crash or a
                // full disk mid-write left a half-file that the next restore
                // rejected — losing everything the user had ever taught it.
                FileOutputStream(historyTempFile).use { out ->
                    out.write(data.toByteArray())
                    out.flush()
                    out.fd.sync()
                }
                if (!historyTempFile.renameTo(historyFile)) {
                    throw IOException("could not activate the learned-word file")
                }
                historyDirtySince = 0L
            } catch (e: IOException) {
                Log.w(TAG, "learned-word save failed", e)
                historyTempFile.delete()
            }
        }
    }

    // ------------------------------------------------------------ memory

    /**
     * The ASR pair plus the refiner pin on the order of 1.2GB of native memory,
     * and nothing ever released it: one dictation held it for the life of the
     * keyboard process. The refiner is the biggest and the least urgent, so it
     * goes as soon as the UI is hidden; real pressure drops everything.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::appScope.isInitialized) return
        val releaseEverything = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> false
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            -> true
            // TRIM_MEMORY_RUNNING_MODERATE: mild pressure, keep the models hot so
            // the next mic press stays instant.
            else -> return
        }
        // Off the main thread: releasing contends with the engine-load lock, which
        // a model load can hold for seconds, and this callback runs on main.
        appScope.launch {
            if (releaseEverything) VoiceEngines.releaseAll() else VoiceEngines.releaseRefiner()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!::appScope.isInitialized) return
        appScope.launch { VoiceEngines.releaseAll() }
    }

    private companion object {
        const val TAG = "VBoardApp"
        const val HISTORY_NAME = "user_history.txt"
        const val SAVE_DEBOUNCE_MS = 5_000L

        /** Ceiling on how long continuous typing may postpone a save. */
        const val MAX_SAVE_DELAY_MS = 60_000L
    }
}
