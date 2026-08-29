package com.vboard.app.clipboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.vboard.core.clipboard.CaptureContext
import com.vboard.core.clipboard.ClipEntry
import com.vboard.core.clipboard.ClipboardHistory
import com.vboard.core.clipboard.Clock
import com.vboard.core.clipboard.OfferResult
import com.vboard.core.clipboard.PinResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Android side of the clipboard: it listens to the system clipboard, hands
 * what it reads to the platform-free [ClipboardHistory] for the actual capture
 * decision, and persists what that decides to keep.
 *
 * Everything interesting — the exclusion rules, retention, limits, the chip
 * window — lives in `:core` and is unit-tested there. This class does no
 * filtering of its own beyond refusing non-text clips, which is a question about
 * `ClipDescription` and so cannot live anywhere else.
 *
 * Confined to the main thread: [ClipboardHistory] is not thread-safe, and every
 * caller here is an IME lifecycle or input callback.
 */
class ClipboardRepository(
    context: Context,
    /** Main-thread scope, owned by the IME service: everything that touches [history]. */
    private val scope: CoroutineScope,
    /**
     * Process-lifetime scope for writes. Saves are debounced, so a scope that
     * dies with the service would silently drop the last one; this is the same
     * reason the learned-word store persists on the application scope.
     */
    persistenceScope: CoroutineScope,
    clock: Clock = Clock { System.currentTimeMillis() },
) {

    fun interface ChangeListener {
        fun onClipsChanged()
    }

    private val appContext = context.applicationContext
    private val history = ClipboardHistory(clock)
    private val store = ClipboardStore(appContext, persistenceScope)

    private val clipboardManager: ClipboardManager? =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /** Notified whenever the visible set of clips changes, so the UI can redraw. */
    var changeListener: ChangeListener? = null

    /** Mirrors the master switch. When false nothing is captured, kept, or saved. */
    var historyEnabled: Boolean = true
        private set

    /** The editor currently in focus, as far as capture is concerned. */
    private var captureContext = CaptureContext()

    private var listenerRegistered = false

    /** An IME may only read the clipboard while its input view is on screen. */
    private var inputViewShown = false

    private var loaded = false

    /**
     * Clips that arrived before the on-disk store finished loading. Replayed in
     * order once it has, so a copy made during keyboard startup is not lost and
     * cannot be clobbered by the restore that follows it.
     */
    private val deferred = ArrayDeque<DeferredClip>()

    private data class DeferredClip(val text: String, val context: CaptureContext, val sensitive: Boolean)

    private val primaryClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (inputViewShown) captureFromSystem()
    }

    // --------------------------------------------------------------- lifecycle

    /** Called from `onCreateInputView`. Safe to call again after a config change. */
    fun onInputViewCreated() {
        if (!listenerRegistered) {
            val manager = clipboardManager
            if (manager == null) {
                Log.w(TAG, "no clipboard service; clip history is unavailable")
            } else {
                runCatching { manager.addPrimaryClipChangedListener(primaryClipListener) }
                    .onSuccess { listenerRegistered = true }
                    .onFailure { Log.w(TAG, "could not observe the clipboard", it) }
            }
        }
        if (!loaded) loadFromDisk()
    }

    /** Called from `onStartInputView`, with the focused editor's constraints. */
    fun onInputViewShown(fieldIsPassword: Boolean, noPersonalizedLearning: Boolean) {
        inputViewShown = true
        captureContext = CaptureContext(fieldIsPassword, noPersonalizedLearning)
        // Poll once: the common case is copy-in-the-host-app then tap the reply
        // field, and that copy happened while some other IME (or none) was up.
        captureFromSystem()
    }

    fun onInputViewHidden() {
        inputViewShown = false
    }

    /** Called from the IME's `onDestroy`. */
    fun onDestroy() {
        flush()
        if (listenerRegistered) {
            runCatching { clipboardManager?.removePrimaryClipChangedListener(primaryClipListener) }
                .onFailure { Log.w(TAG, "could not stop observing the clipboard", it) }
            listenerRegistered = false
        }
        // One-time codes and card numbers die with the process, by design.
        history.clearSessionOnly()
    }

    // ---------------------------------------------------------------- settings

    /**
     * Applies the master switch. Turning it off wipes memory and the file at
     * once — the setting is a promise about what is on disk, not a filter.
     */
    fun setHistoryEnabled(enabled: Boolean) {
        if (historyEnabled == enabled) return
        historyEnabled = enabled
        if (!enabled) {
            history.deleteAll()
            store.deleteFile()
            changeListener?.onClipsChanged()
        }
    }

    /** Drops everything the user asked to be rid of, memory and disk alike. */
    fun deleteAll() {
        history.deleteAll()
        store.deleteFile()
        changeListener?.onClipsChanged()
    }

    // ----------------------------------------------------------------- capture

    /**
     * Reads the system clipboard and offers it to the history. Only ever called
     * while the input view is shown; the platform refuses the read otherwise.
     */
    private fun captureFromSystem() {
        if (!historyEnabled) return
        val incoming = readPrimaryClip() ?: return
        offer(incoming.text, incoming.sensitive)
    }

    private fun offer(text: String, sensitive: Boolean) {
        if (!loaded) {
            if (deferred.size >= MAX_DEFERRED) deferred.removeFirst()
            deferred.addLast(DeferredClip(text, captureContext, sensitive))
            return
        }
        when (history.offer(text, captureContext, sensitive)) {
            is OfferResult.Stored -> {
                store.scheduleSave(history.serialize())
                changeListener?.onClipsChanged()
            }
            is OfferResult.SessionOnly -> changeListener?.onClipsChanged()
            is OfferResult.Discarded -> Unit
        }
    }

    /**
     * The first item of the primary clip as text, or null.
     *
     * A clip whose description is not textual — an image, a bare content URI —
     * is ignored outright, so the previous text clip stays at the top of the
     * list rather than being replaced by something unpasteable.
     */
    private fun readPrimaryClip(): IncomingClip? {
        val manager = clipboardManager ?: return null
        val clip = try {
            manager.primaryClip
        } catch (e: SecurityException) {
            // Reading is refused when this IME is not the focused one.
            Log.w(TAG, "clipboard read refused", e)
            null
        } ?: return null

        val description: ClipDescription = clip.description ?: return null
        if (!description.hasMimeType("text/*")) return null
        if (clip.itemCount <= 0) return null

        val text = try {
            clip.getItemAt(0).coerceToText(appContext)?.toString()
        } catch (e: RuntimeException) {
            // coerceToText resolves content URIs; a hostile or dead provider
            // must not take the keyboard down with it.
            Log.w(TAG, "clip could not be coerced to text", e)
            null
        } ?: return null

        return IncomingClip(text, description.isMarkedSensitive())
    }

    /**
     * The flag is [ClipDescription.EXTRA_IS_SENSITIVE], added in API 33. It is
     * referenced by name rather than by constant so the code compiles and reads
     * identically on the API 29 devices this app supports, where the key is
     * simply absent.
     */
    private fun ClipDescription.isMarkedSensitive(): Boolean =
        extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true

    private data class IncomingClip(val text: String, val sensitive: Boolean)

    // ------------------------------------------------------------------- reads

    fun pinnedClips(): List<ClipEntry> = if (historyEnabled) history.pinned() else emptyList()

    fun recentClips(): List<ClipEntry> = if (historyEnabled) history.recent() else emptyList()

    fun hasClips(): Boolean = historyEnabled && !history.isEmpty()

    /** The clip to offer in the suggestion strip right now, or null. */
    fun chip(): ClipEntry? = if (historyEnabled) history.chip() else null

    /** Suppresses the chip: a keystroke happened, or the chip itself was tapped. */
    fun dismissChip() {
        history.dismissChip()
    }

    /** Writes any debounced change out now, at a natural lifecycle checkpoint. */
    fun flush() {
        if (!loaded) return
        store.saveNow(history.serialize())
    }

    // ---------------------------------------------------------------- mutation

    fun pin(text: String): PinResult {
        val result = history.pin(text)
        if (result == PinResult.PINNED) {
            store.scheduleSave(history.serialize())
            changeListener?.onClipsChanged()
        }
        return result
    }

    fun unpin(text: String) {
        history.unpin(text)
        store.scheduleSave(history.serialize())
        changeListener?.onClipsChanged()
    }

    fun delete(text: String) {
        history.delete(text)
        store.scheduleSave(history.serialize())
        changeListener?.onClipsChanged()
    }

    // -------------------------------------------------------------- persistence

    private fun loadFromDisk() {
        scope.launch {
            val raw = store.load()
            if (raw != null && !history.restore(raw)) {
                // Unreadable: start empty in memory, but never save over the file.
                store.markReadFailed()
                Log.w(TAG, "clip store could not be parsed; keeping the file")
            }
            loaded = true
            // The master switch may have been read after the load was started;
            // an "off" setting is a promise that nothing is kept, so honour it.
            if (!historyEnabled) {
                history.deleteAll()
                deferred.clear()
                store.deleteFile()
                changeListener?.onClipsChanged()
                return@launch
            }
            val queued = deferred.toList()
            deferred.clear()
            for (clip in queued) {
                history.offer(clip.text, clip.context, clip.sensitive)
            }
            if (queued.isNotEmpty()) store.scheduleSave(history.serialize())
            changeListener?.onClipsChanged()
        }
    }

    private companion object {
        const val TAG = "VBoardClips"
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        /** A brief startup window; more than this and the oldest is dropped. */
        const val MAX_DEFERRED = 4
    }
}
