package com.vboard.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModelCatalogTest {

    @Test
    fun `catalog contains exactly the three expected packs`() {
        assertEquals(
            listOf("zipformer-en-streaming", "parakeet-tdt-0.6b-v2", "qwen25-05b-refiner"),
            ModelCatalog.packs.map { it.id },
        )
    }

    @Test
    fun `byId returns matching pack and null for unknown id`() {
        val zipformer = ModelCatalog.byId("zipformer-en-streaming")
        assertSame(ModelCatalog.packs[0], zipformer)
        assertEquals("Live transcription (English)", zipformer?.displayName)
        assertNull(ModelCatalog.byId("does-not-exist"))
    }

    @Test
    fun `byKind maps each kind to its pack`() {
        assertEquals(listOf("zipformer-en-streaming"), ModelCatalog.byKind(ModelKind.STREAMING_ASR).map { it.id })
        assertEquals(listOf("parakeet-tdt-0.6b-v2"), ModelCatalog.byKind(ModelKind.FINAL_ASR).map { it.id })
        assertEquals(listOf("qwen25-05b-refiner"), ModelCatalog.byKind(ModelKind.REFINER_LLM).map { it.id })
    }

    @Test
    fun `required and archive flags match spec`() {
        val zipformer = ModelCatalog.byId("zipformer-en-streaming")!!
        val parakeet = ModelCatalog.byId("parakeet-tdt-0.6b-v2")!!
        val refiner = ModelCatalog.byId("qwen25-05b-refiner")!!

        assertTrue(zipformer.required)
        assertTrue(parakeet.required)
        assertFalse(refiner.required)

        assertTrue(zipformer.files.single().archive)
        assertTrue(parakeet.files.single().archive)
        assertFalse(refiner.files.single().archive)

        assertEquals(ModelKind.FINAL_ASR, parakeet.kind)
        assertEquals("High-accuracy transcription (English)", parakeet.displayName)
        assertEquals("Qwen2.5, Apache-2.0 (LiteRT community build)", refiner.licenseNote)
    }

    @Test
    fun `placeholder hashes are empty and sizes sum into totalBytes`() {
        for (pack in ModelCatalog.packs) {
            assertEquals(1, pack.version)
            assertTrue(pack.files.isNotEmpty())
            for (file in pack.files) {
                assertEquals("", file.sha256)
                assertTrue(file.sizeBytes > 0)
            }
            assertEquals(pack.files.sumOf { it.sizeBytes }, pack.totalBytes)
        }
        assertEquals(130_000_000L, ModelCatalog.byId("zipformer-en-streaming")!!.totalBytes)
        assertEquals(700_000_000L, ModelCatalog.byId("parakeet-tdt-0.6b-v2")!!.totalBytes)
        assertEquals(547_000_000L, ModelCatalog.byId("qwen25-05b-refiner")!!.totalBytes)
    }

    @Test
    fun `downloading fraction is bytesDone over bytesTotal and safe at zero total`() {
        assertEquals(0.25, PackState.Downloading(25, 100).fraction)
        assertEquals(0.0, PackState.Downloading(0, 100).fraction)
        assertEquals(1.0, PackState.Downloading(100, 100).fraction)
        assertEquals(0.0, PackState.Downloading(0, 0).fraction)
    }
}
