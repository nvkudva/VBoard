package com.vboard.app.models

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowEnvironment

/**
 * Where the ~1GB of models lives, and how a pack already installed in app data
 * gets out of it. The point of the move is that an uninstall does not take the
 * download with it, so the rules that matter here are: never read from a root
 * the models have not arrived in yet, and never delete the copy still in use.
 */
@RunWith(RobolectricTestRunner::class)
class ModelStoreLocationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.externalMediaDirs.filterNotNull().forEach {
            it.mkdirs()
            ShadowEnvironment.setExternalStorageState(it, Environment.MEDIA_MOUNTED)
        }
        File(context.filesDir, "models").deleteRecursively()
        mediaModelsDir()?.deleteRecursively()
    }

    private fun mediaModelsDir(): File? =
        context.externalMediaDirs.firstOrNull()?.let { File(it, "models") }

    private fun installPack(root: File, id: String) {
        File(root, "$id/v1").mkdirs()
        File(root, "$id/v1/installed.marker").writeText("1")
    }

    @Test
    fun `a fresh install downloads outside app data`() {
        val store = ModelStore(context)
        assertTrue(store.isOutsideAppData)
        assertEquals(mediaModelsDir()!!.absolutePath, store.rootDir.absolutePath)
    }

    @Test
    fun `packs already in app data keep being read from there until they are copied`() {
        val internal = File(context.filesDir, "models")
        installPack(internal, "streaming")

        val before = ModelStore(context)
        assertFalse(before.isOutsideAppData)
        assertEquals(internal.absolutePath, before.rootDir.absolutePath)

        before.migrateFromInternalStorage()

        // The copy landed, and the original is still there for the process that
        // is using it.
        assertTrue(File(mediaModelsDir(), "streaming/v1/installed.marker").exists())
        assertTrue(File(internal, "streaming/v1/installed.marker").exists())

        // The next start reads the copy, and reclaims what it replaced.
        val after = ModelStore(context)
        assertTrue(after.isOutsideAppData)
        after.migrateFromInternalStorage()
        assertFalse(internal.exists())
    }

    @Test
    fun `an unmounted external volume falls back to app data`() {
        context.externalMediaDirs.filterNotNull().forEach {
            ShadowEnvironment.setExternalStorageState(it, Environment.MEDIA_REMOVED)
        }
        val store = ModelStore(context)
        assertFalse(store.isOutsideAppData)
        assertEquals(File(context.filesDir, "models").absolutePath, store.rootDir.absolutePath)
    }

    @Test
    fun `a half-finished copy does not make the new root look ready`() {
        val internal = File(context.filesDir, "models")
        installPack(internal, "streaming")
        // What an interrupted migration leaves behind.
        File(mediaModelsDir(), ".staging-123-streaming/v1").mkdirs()

        val store = ModelStore(context)
        assertFalse(store.isOutsideAppData)
        assertEquals(internal.absolutePath, store.rootDir.absolutePath)
    }
}
