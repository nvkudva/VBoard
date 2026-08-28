package com.vboard.core.qa

import com.vboard.core.model.FakeFetcher
import com.vboard.core.model.Fetcher
import com.vboard.core.model.InstallError
import com.vboard.core.model.ModelFileSpec
import com.vboard.core.model.ModelKind
import com.vboard.core.model.ModelPack
import com.vboard.core.model.PackInstaller
import com.vboard.core.model.PackState
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QA edge cases for [PackInstaller] beyond PackInstallerTest (VB-402/403/406):
 * zero-byte specs, servers that deliver MORE bytes than the manifest says,
 * concurrent duplicate installs, and delete-during-download.
 */
class ModelInstallerQaTest {

    @TempDir
    lateinit var root: Path

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun body(size: Int, seed: Int = 1): ByteArray =
        ByteArray(size) { i -> ((i * 31 + seed * 17) and 0xff).toByte() }

    private fun url(rel: String) = "https://models.test/$rel"

    private fun pack(files: List<ModelFileSpec>, id: String = "qa-pack", version: Int = 1) = ModelPack(
        id = id,
        displayName = "QA pack",
        kind = ModelKind.STREAMING_ASR,
        version = version,
        files = files,
        licenseNote = "test",
        required = true,
    )

    /**
     * Fetcher that suspends (yield) between chunks, so two installs launched in
     * the same runTest scheduler genuinely interleave - [FakeFetcher] writes a
     * whole body without suspending and cannot exercise concurrency.
     */
    private class YieldingFetcher(private val bodies: Map<String, ByteArray>) : Fetcher {
        var chunkSize = 16

        override suspend fun fetch(url: String, rangeStart: Long, sink: OutputStream, onBytes: (Long) -> Unit) {
            val bodyBytes = bodies[url] ?: throw IOException("no body for $url")
            var pos = rangeStart.toInt()
            var newBytes = 0L
            while (pos < bodyBytes.size) {
                coroutineContext.ensureActive()
                val end = minOf(bodyBytes.size, pos + chunkSize)
                sink.write(bodyBytes, pos, end - pos)
                sink.flush()
                newBytes += end - pos
                pos = end
                onBytes(newBytes)
                yield()
            }
        }

        override suspend fun contentLength(url: String) = bodies[url]?.size?.toLong() ?: -1L
    }

    // ------------------------------------------------------------ zero-byte files

    @Test
    fun `zero-byte file spec installs cleanly with verified empty digest`() = runTest {
        val empty = ByteArray(0)
        val spec = ModelFileSpec("empty.cfg", url("empty.cfg"), sha256Hex(empty), 0L)
        val fetcher = FakeFetcher().apply { bodies[url("empty.cfg")] = empty }
        val installer = PackInstaller(root, fetcher)
        val p = pack(listOf(spec))
        val states = mutableListOf<PackState>()

        val result = installer.install(p) { states += it }

        assertEquals(PackState.Installed, result)
        assertEquals(PackState.Installed, installer.stateOf(p))
        val dir = installer.installedDir(p)!!
        assertEquals(0L, Files.size(dir.resolve("empty.cfg")))
        assertEquals(PackState.Installed, states.last())
        // A fresh installer (process restart) agrees.
        assertEquals(PackState.Installed, PackInstaller(root, FakeFetcher()).stateOf(p))
    }

    @Test
    fun `zero-byte file alongside a normal file installs`() = runTest {
        val empty = ByteArray(0)
        val content = body(64)
        val fetcher = FakeFetcher().apply {
            bodies[url("empty.cfg")] = empty
            bodies[url("model.onnx")] = content
        }
        val p = pack(
            listOf(
                ModelFileSpec("empty.cfg", url("empty.cfg"), sha256Hex(empty), 0L),
                ModelFileSpec("model.onnx", url("model.onnx"), sha256Hex(content), 64L),
            ),
        )
        val installer = PackInstaller(root, fetcher)

        assertEquals(PackState.Installed, installer.install(p))
        assertTrue(content.contentEquals(Files.readAllBytes(installer.installedDir(p)!!.resolve("model.onnx"))))
    }

    // ------------------------------------------------------------ size drift (server sends MORE)

    @Test
    fun `server sending more bytes than the spec with unpinned sha is tolerated without crash or marker corruption`() = runTest {
        // Catalog comment: "the installer tolerates small drift from upstream".
        val served = body(120, seed = 9)
        val spec = ModelFileSpec("drift.bin", url("drift.bin"), sha256 = "", sizeBytes = 100L)
        val fetcher = FakeFetcher().apply { bodies[url("drift.bin")] = served }
        val installer = PackInstaller(root, fetcher)
        val p = pack(listOf(spec), id = "drift-pack")

        val result = installer.install(p)

        assertEquals(PackState.Installed, result)
        // The marker is consistent: state says Installed and the activated file is
        // exactly what the server served (all 120 bytes, nothing truncated).
        val installed = installer.installedDir(p)!!.resolve("drift.bin")
        assertTrue(served.contentEquals(Files.readAllBytes(installed)))
        // Re-install short-circuits; no staging leftovers.
        assertEquals(PackState.Installed, installer.install(p))
        assertFalse(Files.exists(root.resolve("drift-pack/staging-v1")))
    }

    @Test
    fun `server sending more bytes than the pinned sha covers fails cleanly with checksum mismatch`() = runTest {
        // sha pinned to the EXPECTED 100-byte artifact, server serves 120 bytes.
        val served = body(120, seed = 9)
        val expected = served.copyOf(100)
        val spec = ModelFileSpec("drift.bin", url("drift.bin"), sha256Hex(expected), 100L)
        val fetcher = FakeFetcher().apply { bodies[url("drift.bin")] = served }
        val installer = PackInstaller(root, fetcher)
        val p = pack(listOf(spec), id = "drift-sha-pack")
        val states = mutableListOf<PackState>()

        val result = installer.install(p) { states += it }

        assertEquals(PackState.Failed(InstallError.CHECKSUM_MISMATCH), result)
        assertEquals(PackState.NotInstalled, installer.stateOf(p))
        assertNull(installer.installedDir(p))
        // Corrupt payload dropped so a retry redownloads from scratch (VB-403).
        assertFalse(Files.exists(root.resolve("drift-sha-pack/staging-v1/drift.bin.part")))
        assertFalse(Files.exists(root.resolve("drift-sha-pack/staging-v1/drift.bin")))
        assertEquals(0L, installer.bytesOnDisk(p))
    }

    // ------------------------------------------------------------ concurrent duplicate install

    @Test
    fun `concurrent duplicate installs of the same pack never throw`() = runTest {
        val content = body(200)
        val spec = ModelFileSpec("model.onnx", url("model.onnx"), sha256Hex(content), 200L)
        val p = pack(listOf(spec), id = "concurrent-pack")
        val installer = PackInstaller(root, YieldingFetcher(mapOf(url("model.onnx") to content)))

        var r1: PackState? = null
        var r2: PackState? = null
        val j1 = launch { r1 = installer.install(p) }
        val j2 = launch { r2 = installer.install(p) }
        j1.join()
        j2.join()

        // Both invocations must complete with a PackState, not an exception.
        assertTrue(r1 is PackState.Installed || r1 is PackState.Failed, "r1=$r1")
        assertTrue(r2 is PackState.Installed || r2 is PackState.Failed, "r2=$r2")
    }

    @Test
    fun `concurrent duplicate installs never activate corrupt files`() = runTest {
        // VB-QA-07 fixed: install() serializes per pack id on a Mutex, so the
        // second caller waits and short-circuits on the Installed marker.
        val content = body(200)
        val spec = ModelFileSpec("model.onnx", url("model.onnx"), sha256Hex(content), 200L)
        val p = pack(listOf(spec), id = "concurrent-pack-2")
        val installer = PackInstaller(root, YieldingFetcher(mapOf(url("model.onnx") to content)))

        val j1 = launch { installer.install(p) }
        val j2 = launch { installer.install(p) }
        j1.join()
        j2.join()

        if (installer.stateOf(p) == PackState.Installed) {
            val installed = Files.readAllBytes(installer.installedDir(p)!!.resolve("model.onnx"))
            assertTrue(
                content.contentEquals(installed),
                "pack activated with corrupt content: ${installed.size} bytes on disk, expected ${content.size}",
            )
        }
    }

    // ------------------------------------------------------------ delete during download

    @Test
    fun `cancel mid-download then delete leaves no files at all`() = runTest {
        val content = body(100)
        val u = url("model.onnx")
        val spec = ModelFileSpec("model.onnx", u, sha256Hex(content), 100L)
        val p = pack(listOf(spec), id = "delete-pack")
        val fetcher = FakeFetcher().apply {
            bodies[u] = content
            hangAfterBytes[u] = 30
        }
        val installer = PackInstaller(root, fetcher)

        val job = launch { installer.install(p) }
        advanceUntilIdle() // download runs until it hangs at 30 bytes
        job.cancel()
        job.join()
        assertTrue(Files.exists(root.resolve("delete-pack/staging-v1/model.onnx.part")))

        installer.delete(p)

        assertFalse(Files.exists(root.resolve("delete-pack")), "pack dir must be fully removed")
        assertEquals(0L, installer.bytesOnDisk(p))
        assertEquals(PackState.NotInstalled, installer.stateOf(p))
        assertNull(installer.installedDir(p))
    }

    @Test
    fun `install after delete starts from scratch and succeeds`() = runTest {
        val content = body(100)
        val u = url("model.onnx")
        val spec = ModelFileSpec("model.onnx", u, sha256Hex(content), 100L)
        val p = pack(listOf(spec), id = "delete-retry-pack")
        val fetcher = FakeFetcher().apply {
            bodies[u] = content
            hangAfterBytes[u] = 30
        }
        val installer = PackInstaller(root, fetcher)

        val job = launch { installer.install(p) }
        advanceUntilIdle()
        job.cancel()
        job.join()
        installer.delete(p)
        fetcher.hangAfterBytes.clear()

        val result = installer.install(p)

        assertEquals(PackState.Installed, result)
        // Fresh download restarted at byte 0 (nothing survived the delete).
        assertEquals(0L, fetcher.calls.last().rangeStart)
        assertTrue(content.contentEquals(Files.readAllBytes(installer.installedDir(p)!!.resolve("model.onnx"))))
    }

    @Test
    fun `delete while installed then reinstall works`() = runTest {
        val content = body(80)
        val u = url("model.onnx")
        val spec = ModelFileSpec("model.onnx", u, sha256Hex(content), 80L)
        val p = pack(listOf(spec), id = "reinstall-pack")
        val fetcher = FakeFetcher().apply { bodies[u] = content }
        val installer = PackInstaller(root, fetcher)

        assertEquals(PackState.Installed, installer.install(p))
        installer.delete(p)
        assertEquals(PackState.NotInstalled, installer.stateOf(p))

        assertEquals(PackState.Installed, installer.install(p))
        assertTrue(content.contentEquals(Files.readAllBytes(installer.installedDir(p)!!.resolve("model.onnx"))))
    }
}
