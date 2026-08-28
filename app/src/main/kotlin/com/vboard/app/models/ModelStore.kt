package com.vboard.app.models

import android.content.Context
import com.vboard.core.model.ModelCatalog
import com.vboard.core.model.ModelKind
import com.vboard.core.model.ModelPack
import com.vboard.core.model.PackInstaller
import com.vboard.core.model.PackState
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException

/**
 * Knows where model packs live on disk and how to unpack downloaded archives.
 * Layout: filesDir/models/<packId>/v<N>/<archive> plus an "extracted/" dir once
 * the archive has been unpacked.
 */
class ModelStore(context: Context) {

    val rootDir: File = File(context.filesDir, "models")

    data class SpeechModelPaths(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
    )

    /** True when every required pack is installed AND extracted. */
    fun speechModelsReady(installer: PackInstaller): Boolean =
        streamingPaths(installer) != null && parakeetPaths(installer) != null

    fun refinerModelPath(installer: PackInstaller): String? {
        val pack = ModelCatalog.byKind(ModelKind.REFINER_LLM).firstOrNull() ?: return null
        val dir = installer.installedDir(pack)?.toFile() ?: return null
        val file = File(dir, pack.files.single().relativePath)
        return file.takeIf { it.exists() }?.absolutePath
    }

    fun streamingPaths(installer: PackInstaller): SpeechModelPaths? {
        val pack = ModelCatalog.byKind(ModelKind.STREAMING_ASR).firstOrNull() ?: return null
        val dir = extractedDir(installer, pack) ?: return null
        return findTransducer(dir, int8Preferred = true)
    }

    fun parakeetPaths(installer: PackInstaller): SpeechModelPaths? {
        val pack = ModelCatalog.byKind(ModelKind.FINAL_ASR).firstOrNull() ?: return null
        val dir = extractedDir(installer, pack) ?: return null
        return findTransducer(dir, int8Preferred = true)
    }

    /**
     * Ensures the pack's archive is extracted; call off the main thread.
     * Returns the extraction dir or null when the pack isn't installed.
     */
    @Throws(IOException::class)
    fun ensureExtracted(installer: PackInstaller, pack: ModelPack): File? {
        if (installer.stateOf(pack) != PackState.Installed) return null
        val installDir = installer.installedDir(pack)?.toFile() ?: return null
        val marker = File(installDir, "extracted/.complete")
        val target = File(installDir, "extracted")
        if (marker.exists()) return target

        val archiveSpec = pack.files.firstOrNull { it.archive } ?: run {
            // Non-archive packs (LLM .task file) need no extraction.
            target.mkdirs()
            marker.parentFile?.mkdirs()
            marker.writeText("ok")
            return target
        }
        val archiveFile = File(installDir, archiveSpec.relativePath)
        if (!archiveFile.exists()) return null

        target.deleteRecursively()
        target.mkdirs()
        extractTarBz2(archiveFile, target)
        marker.writeText("ok")
        // Reclaim the archive space once contents are safely extracted.
        archiveFile.delete()
        return target
    }

    private fun extractedDir(installer: PackInstaller, pack: ModelPack): File? {
        val installDir = installer.installedDir(pack)?.toFile() ?: return null
        val dir = File(installDir, "extracted")
        return if (File(dir, ".complete").exists()) dir else null
    }

    /** Locates encoder/decoder/joiner/tokens anywhere under [dir] (archives nest a folder). */
    private fun findTransducer(dir: File, int8Preferred: Boolean): SpeechModelPaths? {
        val all = dir.walkTopDown().filter { it.isFile }.toList()
        fun pick(role: String): File? {
            val candidates = all.filter {
                it.name.startsWith(role) && it.name.endsWith(".onnx")
            }
            return if (int8Preferred) {
                candidates.firstOrNull { it.name.contains("int8") } ?: candidates.firstOrNull()
            } else {
                candidates.firstOrNull { !it.name.contains("int8") } ?: candidates.firstOrNull()
            }
        }
        val encoder = pick("encoder") ?: return null
        val decoder = pick("decoder") ?: return null
        val joiner = pick("joiner") ?: return null
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
                            out.outputStream().use { tar.copyTo(it) }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }
}
