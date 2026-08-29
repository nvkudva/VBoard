package com.vboard.app.models

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Where the ~1GB of models lives, and how a pack already installed in app data
 * gets out of it. The point of the move is that an uninstall does not take the
 * download with it, so the two rules that matter are: never read from a root the
 * models have not arrived in yet, and never delete the copy still in use.
 */
class ModelRootsTest {

    private fun installPack(root: File, id: String, bytes: Int = 8) {
        File(root, "$id/v1").mkdirs()
        File(root, "$id/v1/installed.marker").writeText("1")
        File(root, "$id/v1/extracted").mkdirs()
        File(root, "$id/v1/extracted/encoder.onnx").writeBytes(ByteArray(bytes))
    }

    @Test
    fun `a fresh install goes to the root that survives uninstall`(@TempDir tmp: File) {
        val external = File(tmp, "external").apply { mkdirs() }
        val internal = File(tmp, "internal").apply { mkdirs() }
        assertEquals(external, ModelRoots.choose(external, internal))
    }

    @Test
    fun `a device with no external volume keeps using app data`(@TempDir tmp: File) {
        val internal = File(tmp, "internal").apply { mkdirs() }
        assertEquals(internal, ModelRoots.choose(null, internal))
    }

    @Test
    fun `packs already in app data are read from there until they are copied`(@TempDir tmp: File) {
        val external = File(tmp, "external").apply { mkdirs() }
        val internal = File(tmp, "internal").apply { mkdirs() }
        installPack(internal, "streaming")

        assertEquals(internal, ModelRoots.choose(external, internal))

        val moved = ModelRoots.copyPacks(internal, external, pid = 1) { _, e -> throw e }
        assertEquals(1, moved)

        // The copy landed, and the original is untouched: the process that asked
        // for the migration is still reading it.
        assertTrue(File(external, "streaming/v1/extracted/encoder.onnx").exists())
        assertTrue(File(internal, "streaming/v1/installed.marker").exists())
        // Only now does the next process start reading the new root.
        assertEquals(external, ModelRoots.choose(external, internal))
    }

    @Test
    fun `a half-finished copy does not make the new root look ready`(@TempDir tmp: File) {
        val external = File(tmp, "external").apply { mkdirs() }
        val internal = File(tmp, "internal").apply { mkdirs() }
        installPack(internal, "streaming")
        // What an interrupted migration leaves behind.
        File(external, "${ModelRoots.STAGING_PREFIX}123-streaming/v1").mkdirs()

        assertFalse(ModelRoots.hasPacks(external))
        assertEquals(internal, ModelRoots.choose(external, internal))
    }

    @Test
    fun `a pack that is already across is not copied twice`(@TempDir tmp: File) {
        val external = File(tmp, "external").apply { mkdirs() }
        val internal = File(tmp, "internal").apply { mkdirs() }
        installPack(internal, "streaming")
        installPack(internal, "parakeet")
        installPack(external, "streaming")

        val moved = ModelRoots.copyPacks(internal, external, pid = 2) { _, e -> throw e }
        assertEquals(1, moved)
        assertTrue(File(external, "parakeet/v1/installed.marker").exists())
    }

    @Test
    fun `the size estimate counts every file under the root`(@TempDir tmp: File) {
        val internal = File(tmp, "internal").apply { mkdirs() }
        installPack(internal, "streaming", bytes = 100)
        assertEquals(101L, ModelRoots.sizeOf(internal))
    }
}
