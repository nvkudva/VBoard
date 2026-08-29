package com.vboard.app.models

import android.content.Context
import android.os.Environment
import android.util.Log
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.ModelKind
import com.vboard.core.model.ModelPack
import com.vboard.core.model.PackInstaller
import com.vboard.core.model.PackState
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Knows where model packs live on disk and how to unpack downloaded archives.
 * Layout: <root>/<packId>/v<N>/<archive> plus an "extracted/" dir once the
 * archive has been unpacked.
 *
 * ## Where the root is, and why it moved
 *
 * The packs are around a gigabyte and cost the user a long download on a good
 * connection. `filesDir` is wiped by the package manager on uninstall, so
 * reinstalling — or sideloading the next alpha after uninstalling this one —
 * made them pay for all of it again.
 *
 * They now live in the app's external **media** directory
 * (`Android/media/<pkg>/models`), which is a real filesystem path that
 * sherpa-onnx can open directly, needs no permission on any supported API level,
 * and on most devices outlives an uninstall. It is not a guarantee: Android
 * documents the media directory as app-specific storage, and some versions and
 * OEMs do remove it. Treat surviving uninstall as the common case, not a
 * promise — nothing here breaks if the directory is gone, the packs simply
 * download again.
 *
 * Internal storage stays the fallback for a device with no usable external
 * volume, and packs already installed there are migrated across once, in the
 * background, by [migrateFromInternalStorage].
 */
class ModelStore(context: Context) {

    private val appContext: Context = context.applicationContext

    private val internalRoot: File = File(appContext.filesDir, DIR_NAME)

    /** Where packs are read from and written to for the life of this process. */
    val rootDir: File = chooseRoot(appContext, internalRoot)

    /** True when [rootDir] is the one that (usually) survives an uninstall. */
    val isOutsideAppData: Boolean = rootDir != internalRoot

    /**
     * One lock per pack id. [ensureExtracted] is called both by the download
     * service and by every single mic press, with nothing between them: one
     * caller could delete the directory the other was in the middle of writing.
     */
    private val extractionLocks = ConcurrentHashMap<String, Any>()

    private fun lockFor(pack: ModelPack): Any = extractionLocks.computeIfAbsent(pack.id) { Any() }

    data class SpeechModelPaths(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
    )

    /**
     * True when the user can press the mic and get words: the streaming recognizer is
     * installed AND extracted.
     *
     * Deliberately does not consult the accuracy pack or the refiner. Those are opt-in
     * upgrades ([com.vboard.core.model.ModelReadiness]); treating them as prerequisites is
     * what made a first run cost 610 MB with no way past it.
     */
    fun dictationReady(installer: PackInstaller): Boolean = streamingPaths(installer) != null

    /** True when the optional high-accuracy final pass is installed and extracted. */
    fun accuracyModelReady(installer: PackInstaller): Boolean = parakeetPaths(installer) != null

    /**
     * True when both speech packs are installed and extracted, i.e. the two-pass pipeline is
     * fully available.
     *
     * Note the changed meaning: this used to be the app's definition of "ready to dictate",
     * which is now [dictationReady]. Use that for anything that gates the mic.
     */
    fun allSpeechModelsReady(installer: PackInstaller): Boolean =
        dictationReady(installer) && accuracyModelReady(installer)

    /** Ids of the packs currently installed, for [com.vboard.core.model.ModelReadiness]. */
    fun installedPackIds(installer: PackInstaller): Set<String> =
        ModelCatalog.packs
            .filter { installer.stateOf(it) == PackState.Installed }
            .mapTo(mutableSetOf()) { it.id }

    fun refinerModelPath(installer: PackInstaller): String? {
        val pack = ModelCatalog.byKind(ModelKind.REFINER_LLM).firstOrNull() ?: return null
        val dir = installer.installedDir(pack)?.toFile() ?: return null
        val file = File(dir, pack.files.single().relativePath)
        return file.takeIf { it.exists() }?.absolutePath
    }

    fun streamingPaths(installer: PackInstaller): SpeechModelPaths? {
        val pack = ModelCatalog.byKind(ModelKind.STREAMING_ASR).firstOrNull() ?: return null
        val dir = extractedDir(installer, pack) ?: return null
        return findTransducer(dir)
    }

    fun parakeetPaths(installer: PackInstaller): SpeechModelPaths? {
        val pack = ModelCatalog.byKind(ModelKind.FINAL_ASR).firstOrNull() ?: return null
        val dir = extractedDir(installer, pack) ?: return null
        return findTransducer(dir)
    }

    /**
     * Ensures the pack's archive is extracted; call off the main thread.
     * Returns the extraction dir or null when the pack isn't installed.
     *
     * The whole operation is serialized per pack and staged: unpack into a temp
     * directory, verify the models it produced actually resolve, rename it into
     * place, only then write the completion marker, and only then reclaim the
     * archive. Doing this in-place (delete, unpack, mark, delete the archive) had
     * no step at which a failure was recoverable — a half-extracted pack kept its
     * installed.marker, so the UI reported it Installed and offered no repair.
     */
    @Throws(IOException::class)
    fun ensureExtracted(installer: PackInstaller, pack: ModelPack): File? =
        synchronized(lockFor(pack)) { ensureExtractedLocked(installer, pack) }

    private fun ensureExtractedLocked(installer: PackInstaller, pack: ModelPack): File? {
        if (installer.stateOf(pack) != PackState.Installed) return null
        val installDir = installer.installedDir(pack)?.toFile() ?: return null
        val target = File(installDir, EXTRACTED_DIR)
        val marker = File(target, COMPLETE_MARKER)
        if (marker.exists()) return target

        val archiveSpec = pack.files.firstOrNull { it.archive } ?: run {
            // Non-archive packs (the LLM .task file) need no extraction.
            target.mkdirs()
            writeMarkerDurably(marker)
            return target
        }
        val archiveFile = File(installDir, archiveSpec.relativePath)
        if (!archiveFile.exists()) {
            // No marker and no archive: the payload is gone (an earlier run
            // deleted the archive without completing). Reporting "installed"
            // forever is a dead end, so force a re-download instead.
            Log.e(TAG, "pack ${pack.id} has neither an extraction nor its archive")
            installer.invalidate(pack)
            return null
        }

        val staging = File(installDir, EXTRACTED_STAGING)
        deleteQuietly(staging)
        deleteQuietly(target) // remnants of an interrupted attempt
        if (!staging.mkdirs() && !staging.isDirectory) {
            throw IOException("cannot create extraction staging dir")
        }

        try {
            extractTarBz2(archiveFile, staging)
            // Verify before committing: an archive that unpacked without error but
            // yielded no usable model is a corrupt install and must never be
            // marked complete.
            if (pack.kind != ModelKind.REFINER_LLM &&
                findTransducer(staging) == null
            ) {
                throw IOException("extracted archive contains no usable transducer")
            }
            syncDir(staging)
            if (!staging.renameTo(target)) {
                throw IOException("cannot activate the extraction dir")
            }
            syncDir(installDir)
            writeMarkerDurably(File(target, COMPLETE_MARKER))
        } catch (e: Throwable) {
            deleteQuietly(staging)
            deleteQuietly(target)
            // Clearing the marker is what makes the failure recoverable: the pack
            // now reads as NotInstalled, so the error's "Download" action leads
            // somewhere that can actually fix it.
            installer.invalidate(pack)
            Log.e(TAG, "model extraction failed for ${pack.id}", e)
            throw if (e is IOException) e else IOException("extraction failed", e)
        }

        // Only now is the archive redundant.
        if (!archiveFile.delete()) {
            Log.w(TAG, "could not reclaim the archive for ${pack.id}")
        }
        return target
    }

    private fun extractedDir(installer: PackInstaller, pack: ModelPack): File? {
        val installDir = installer.installedDir(pack)?.toFile() ?: return null
        val dir = File(installDir, EXTRACTED_DIR)
        return if (File(dir, COMPLETE_MARKER).exists()) dir else null
    }

    /** Locates encoder/decoder/joiner/tokens anywhere under [dir] (archives nest a folder). */
    private fun findTransducer(dir: File): SpeechModelPaths? {
        val all = dir.walkTopDown().filter { it.isFile }.toList()

        /**
         * Quantization is chosen per role, not per model. The streaming Zipformer archive
         * ships both variants of all three graphs, and sherpa-onnx's own configuration for
         * it pairs an int8 encoder and joiner with a *float* decoder: the decoder is a small
         * embedding + convolution network, so quantizing it costs accuracy while saving
         * almost nothing. Preferring int8 everywhere silently ran the model in a
         * configuration upstream does not recommend.
         *
         * Packs that ship only one variant (Parakeet is int8-only) fall through to whatever
         * is present, so this is a no-op for them.
         */
        fun pick(role: String, preferInt8: Boolean): File? {
            val candidates = all.filter {
                it.name.startsWith(role) && it.name.endsWith(".onnx")
            }
            val preferred = candidates.filter { it.name.contains("int8") == preferInt8 }
            return preferred.firstOrNull() ?: candidates.firstOrNull()
        }
        val encoder = pick("encoder", preferInt8 = true) ?: return null
        val decoder = pick("decoder", preferInt8 = false) ?: return null
        val joiner = pick("joiner", preferInt8 = true) ?: return null
        val tokens = all.firstOrNull { it.name == "tokens.txt" } ?: return null
        return SpeechModelPaths(
            encoder.absolutePath,
            decoder.absolutePath,
            joiner.absolutePath,
            tokens.absolutePath,
        )
    }

    @Throws(IOException::class)
    private fun extractTarBz2(archive: File, target: File) {
        BufferedInputStream(archive.inputStream()).use { raw ->
            BZip2CompressorInputStream(raw).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextEntry
                    val targetCanonical = target.canonicalPath + File.separator
                    while (entry != null) {
                        val out = File(target, entry.name)
                        // Zip-slip guard.
                        if (!out.canonicalPath.startsWith(targetCanonical)) {
                            throw IOException("Blocked archive path traversal: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else if (!entry.name.contains("test_wavs")) {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { sink ->
                                tar.copyTo(sink)
                                sink.flush()
                                // The completion marker vouches for these bytes, so
                                // they have to reach the disk before it is written.
                                sink.fd.sync()
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun writeMarkerDurably(marker: File) {
        marker.parentFile?.mkdirs()
        FileOutputStream(marker).use { out ->
            out.write(MARKER_CONTENT)
            out.flush()
            out.fd.sync()
        }
        syncDir(marker.parentFile)
    }

    /**
     * Forces a directory's entries to disk. Best effort: opening a directory as a
     * channel is not portable, and a filesystem that refuses just leaves us where
     * the code was before.
     */
    private fun syncDir(dir: File?) {
        if (dir == null) return
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            // Not supported here.
        } catch (e: UnsupportedOperationException) {
            // Ditto.
        }
    }

    private fun deleteQuietly(dir: File) {
        if (!dir.exists()) return
        if (!dir.deleteRecursively()) {
            Log.w(TAG, "could not fully delete ${dir.name}")
        }
    }

    /**
     * Copies packs installed under the old internal root to [rootDir], once.
     *
     * Call off the main thread. It is a copy, never a move: the running process
     * is still reading models out of the internal root (see [chooseRoot]), and
     * the originals are reclaimed on the next start, when the external copy is
     * the one being used. A partial copy is never activated — it is staged under
     * a process-unique name and renamed into place — so an interrupted migration
     * costs disk, not models.
     */
    fun migrateFromInternalStorage() {
        if (isOutsideAppData) {
            // Already running on the external root: this start is the one that
            // gets to reclaim whatever the previous migration left behind.
            if (internalRoot.exists() && hasPacks(rootDir)) deleteQuietly(internalRoot)
            return
        }
        val external = externalRoot(appContext) ?: return
        if (external.exists() && hasPacks(external)) return
        if (!hasPacks(internalRoot)) return

        val needed = ModelRoots.sizeOf(internalRoot)
        val volume = external.parentFile ?: external
        if (volume.usableSpace < needed + MIGRATION_HEADROOM_BYTES) {
            Log.w(TAG, "not enough room on the external volume to move the models")
            return
        }

        // Every process runs this (keyboard, settings, the download worker), and
        // two of them copying a gigabyte at once helps nobody. Whoever gets the
        // lock does the work; the others come back on their next start.
        val lockFile = File(external.parentFile, "$DIR_NAME.migrating")
        FileChannel.open(
            lockFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val lock = runCatching { channel.tryLock() }.getOrNull() ?: return
            lock.use { copyInternalRootTo(external) }
        }
    }

    private fun copyInternalRootTo(external: File) {
        val moved = ModelRoots.copyPacks(
            from = internalRoot,
            to = external,
            pid = android.os.Process.myPid(),
        ) { packName, e -> Log.w(TAG, "could not migrate pack $packName", e) }
        if (moved > 0) {
            syncDir(external)
            Log.i(TAG, "migrated $moved pack(s) out of app data; originals go on the next start")
        }
    }

    private companion object {
        const val TAG = "VBoardModels"
        const val DIR_NAME = "models"
        const val MIGRATION_HEADROOM_BYTES = 50_000_000L
        const val EXTRACTED_DIR = "extracted"
        const val EXTRACTED_STAGING = "extracted.staging"
        const val COMPLETE_MARKER = ".complete"
        val MARKER_CONTENT = "ok".toByteArray()

        /**
         * The media directory is indexed by MediaStore; without this, a model
         * graph can be offered to the user as a media file to open or share.
         */
        const val NOMEDIA = ".nomedia"

        fun chooseRoot(context: Context, internalRoot: File): File =
            ModelRoots.choose(externalRoot(context), internalRoot)

        /**
         * `Android/media/<pkg>/models` on the primary volume, or null when there
         * is no mounted external storage to put it on.
         */
        fun externalRoot(context: Context): File? {
            val media = context.externalMediaDirs.firstOrNull() ?: return null
            if (Environment.getExternalStorageState(media) != Environment.MEDIA_MOUNTED) return null
            val root = File(media, DIR_NAME)
            if (!root.exists() && !root.mkdirs() && !root.isDirectory) return null
            val marker = File(root, NOMEDIA)
            if (!marker.exists()) runCatching { marker.createNewFile() }
            return root
        }

        fun hasPacks(root: File): Boolean = ModelRoots.hasPacks(root)
    }
}
