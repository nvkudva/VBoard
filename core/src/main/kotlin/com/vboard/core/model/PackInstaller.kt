package com.vboard.core.model

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * Resumable, checksum-verified downloader/installer for [ModelPack]s.
 *
 * On-disk layout under [rootDir]:
 * ```
 * <packId>/v<version>/                    final (activated) dir, contains "installed.marker"
 * <packId>/staging-v<version>/            staging dir during download
 * <packId>/staging-v<version>/<file>.part in-flight download of one file
 * <packId>/staging-v<version>/<file>      file fully downloaded and verified, awaiting activation
 * ```
 *
 * All state is derived from the filesystem (no in-memory cache) so it survives process death.
 */
class PackInstaller(
    private val rootDir: Path,
    private val fetcher: Fetcher,
    private val freeBytes: () -> Long = { Long.MAX_VALUE },
) {

    /** Current state derived from disk (marker files), safe to call anytime. */
    fun stateOf(pack: ModelPack): PackState {
        val marker = markerFile(pack)
        if (Files.isRegularFile(marker)) {
            val markedVersion = runCatching { Files.readString(marker).trim().toInt() }.getOrNull()
            if (markedVersion == pack.version) return PackState.Installed
        }
        return PackState.NotInstalled
    }

    /** Directory containing the activated pack files, or null unless [PackState.Installed]. */
    fun installedDir(pack: ModelPack): Path? =
        finalDir(pack).takeIf { stateOf(pack) == PackState.Installed }

    /**
     * Downloads all files (resuming any `.part` files), verifies sha256 (when non-empty), then
     * atomically activates the pack. Emits progress via [onState] and returns the final state.
     * Coroutine cancellation yields [PackState.Failed] with [InstallError.CANCELLED]; partial
     * files are retained for resume.
     */
    suspend fun install(pack: ModelPack, onState: (PackState) -> Unit = {}): PackState {
        if (stateOf(pack) == PackState.Installed) {
            onState(PackState.Installed)
            return PackState.Installed
        }

        val totalBytes = pack.totalBytes
        var lastEmittedBytes = -1L

        fun emitProgress(bytesDone: Long) {
            lastEmittedBytes = maxOf(bytesDone, lastEmittedBytes)
            onState(PackState.Downloading(lastEmittedBytes, totalBytes))
        }

        fun fail(error: InstallError): PackState {
            val state = PackState.Failed(error)
            onState(state)
            return state
        }

        // Storage check: no network calls if the remaining bytes plus headroom won't fit.
        val remainingBytes = (totalBytes - bytesOnDisk(pack)).coerceAtLeast(0L)
        if (freeBytes() < remainingBytes + STORAGE_HEADROOM_BYTES) {
            return fail(InstallError.INSUFFICIENT_STORAGE)
        }

        val staging = stagingDir(pack)
        try {
            Files.createDirectories(staging)
        } catch (e: IOException) {
            return fail(InstallError.IO)
        }

        var completedBytes = 0L
        for (spec in pack.files) {
            val target = staging.resolve(spec.relativePath)
            val part = staging.resolve(spec.relativePath + PART_SUFFIX)

            try {
                target.parent?.let(Files::createDirectories)
                // Completed earlier (already verified before its rename): skip.
                if (Files.isRegularFile(target) && Files.size(target) == spec.sizeBytes) {
                    completedBytes += spec.sizeBytes
                    emitProgress(completedBytes)
                    continue
                }
            } catch (e: IOException) {
                return fail(InstallError.IO)
            }

            val digest = MessageDigest.getInstance("SHA-256")

            // Re-hash any existing partial bytes so verification stays a single streamed pass.
            var existingBytes = 0L
            try {
                if (Files.isRegularFile(part)) {
                    Files.newInputStream(part).use { input ->
                        val buffer = ByteArray(HASH_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            existingBytes += read
                        }
                    }
                }
            } catch (e: IOException) {
                return fail(InstallError.IO)
            }

            emitProgress(completedBytes + existingBytes)
            val progressBase = completedBytes + existingBytes

            try {
                DigestOutputStream(
                    Files.newOutputStream(
                        part,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND,
                    ),
                    digest,
                ).use { sink ->
                    fetcher.fetch(spec.url, existingBytes, sink) { newBytes ->
                        emitProgress(progressBase + newBytes)
                    }
                }
            } catch (e: CancellationException) {
                // Partial files retained for resume.
                return fail(InstallError.CANCELLED)
            } catch (e: IOException) {
                // Network failure; the .part stays on disk for resume.
                return fail(InstallError.NETWORK)
            }

            try {
                if (Files.size(part) < spec.sizeBytes) {
                    // Server delivered a short body without erroring; keep the .part for resume.
                    return fail(InstallError.NETWORK)
                }
                if (spec.sha256.isNotEmpty()) {
                    onState(PackState.Verifying)
                    val actual = toHex(digest.digest())
                    if (!actual.equals(spec.sha256, ignoreCase = true)) {
                        // Drop the corrupt payload so a retry redownloads this file only.
                        Files.deleteIfExists(part)
                        Files.deleteIfExists(target)
                        return fail(InstallError.CHECKSUM_MISMATCH)
                    }
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                return fail(InstallError.IO)
            }

            completedBytes += spec.sizeBytes
            emitProgress(completedBytes)
        }

        // Activation: atomic move of the staging dir into place, then write the marker.
        try {
            val final = finalDir(pack)
            deleteRecursively(final) // stale, never-activated remnants
            try {
                Files.move(staging, final, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(staging, final)
            }
            Files.writeString(final.resolve(MARKER_NAME), pack.version.toString())
        } catch (e: IOException) {
            return fail(InstallError.IO)
        }

        onState(PackState.Installed)
        return PackState.Installed
    }

    /** Deletes installed files AND partial downloads for the pack, including old versions. */
    fun delete(pack: ModelPack) {
        deleteRecursively(packDir(pack))
    }

    /** Bytes already downloaded (partial + complete) for resume-aware UI. */
    fun bytesOnDisk(pack: ModelPack): Long {
        val final = finalDir(pack)
        val staging = stagingDir(pack)
        return pack.files.sumOf { spec ->
            maxOf(
                sizeOrZero(final.resolve(spec.relativePath)),
                sizeOrZero(staging.resolve(spec.relativePath)),
                sizeOrZero(staging.resolve(spec.relativePath + PART_SUFFIX)),
            )
        }
    }

    private fun packDir(pack: ModelPack): Path = rootDir.resolve(pack.id)

    private fun finalDir(pack: ModelPack): Path = packDir(pack).resolve("v${pack.version}")

    private fun stagingDir(pack: ModelPack): Path = packDir(pack).resolve("staging-v${pack.version}")

    private fun markerFile(pack: ModelPack): Path = finalDir(pack).resolve(MARKER_NAME)

    private fun sizeOrZero(path: Path): Long =
        try {
            if (Files.isRegularFile(path)) Files.size(path) else 0L
        } catch (e: IOException) {
            0L
        }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(HEX_DIGITS[v ushr 4])
            append(HEX_DIGITS[v and 0x0f])
        }
    }

    private companion object {
        const val MARKER_NAME = "installed.marker"
        const val PART_SUFFIX = ".part"
        const val STORAGE_HEADROOM_BYTES = 50_000_000L // 50 MB
        const val HASH_BUFFER_SIZE = 64 * 1024
        const val HEX_DIGITS = "0123456789abcdef"
    }
}
