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

        // Both speech models are required. The streaming pack is what makes the mic
        // produce words at all; the accuracy pass is required because streaming-only
        // output is not good enough to be the product's typing experience. Only the
        // refiner - which rewrites already-committed text - is an opt-in upgrade.
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
    fun `pinned hashes are well formed and sizes sum into totalBytes`() {
        for (pack in ModelCatalog.packs) {
            assertEquals(1, pack.version)
            assertTrue(pack.files.isNotEmpty())
            for (file in pack.files) {
                // Empty means "skip verification"; anything else must be a real digest,
                // because a malformed hash would fail every install rather than none.
                if (file.sha256.isNotEmpty()) {
                    assertEquals(64, file.sha256.length, "${file.relativePath} sha256 length")
                    assertTrue(
                        file.sha256.all { it in "0123456789abcdef" },
                        "${file.relativePath} sha256 must be lowercase hex",
                    )
                }
                assertTrue(file.sizeBytes > 0)
            }
            assertEquals(pack.files.sumOf { it.sizeBytes }, pack.totalBytes)
        }
        // Both speech packs are pinned to digests measured from the upstream assets, so a
        // corrupted-but-complete download can no longer install. The refiner stays
        // unpinned until its host can be hashed from the release pipeline.
        for (id in listOf("zipformer-en-streaming", "parakeet-tdt-0.6b-v2")) {
            assertTrue(
                ModelCatalog.byId(id)!!.files.all { it.sha256.isNotEmpty() },
                "$id must ship a pinned digest",
            )
        }
        // Sizes measured from the upstream release assets; the installer re-checks with
        // the server, so drift here only affects progress and the storage pre-check.
        assertEquals(127_887_156L, ModelCatalog.byId("zipformer-en-streaming")!!.totalBytes)
        assertEquals(482_468_385L, ModelCatalog.byId("parakeet-tdt-0.6b-v2")!!.totalBytes)
        assertEquals(547_000_000L, ModelCatalog.byId("qwen25-05b-refiner")!!.totalBytes)
    }

    @Test
    fun `a pack fetched from a mutable ref or without a digest can never be required`() {
        // The refiner is pulled from a Hugging Face branch ref with no pinned digest, so its
        // bytes can change under us and nothing verifies them. That is tolerable for an
        // opt-in extra and not for anything on the setup critical path, so the two
        // properties are wired together here rather than left to a code review.
        for (pack in ModelCatalog.packs) {
            val unverified = pack.files.any { it.sha256.isEmpty() }
            val mutableRef = pack.files.any { it.url.contains("/resolve/main/") }
            if (unverified || mutableRef) {
                assertFalse(
                    pack.required,
                    "${pack.id} must be optional until its URL is pinned to an immutable " +
                        "revision and its sha256 is filled in from the release pipeline",
                )
            }
        }
    }

    @Test
    fun `every download url is https`() {
        for (pack in ModelCatalog.packs) {
            for (file in pack.files) {
                assertTrue(file.url.startsWith("https://"), "${file.relativePath} must use https")
            }
        }
    }

    @Test
    fun `archive packs budget extra disk for extraction the plain file pack does not`() {
        val parakeet = ModelCatalog.byId("parakeet-tdt-0.6b-v2")!!
        assertEquals(parakeet.totalBytes * 5 / 2, parakeet.installFootprintBytes)

        // The LLM .task is downloaded as-is, so its footprint is just its size.
        val refiner = ModelCatalog.byId("qwen25-05b-refiner")!!
        assertEquals(refiner.totalBytes, refiner.installFootprintBytes)
    }

    @Test
    fun `downloading fraction is bytesDone over bytesTotal and safe at zero total`() {
        assertEquals(0.25, PackState.Downloading(25, 100).fraction)
        assertEquals(0.0, PackState.Downloading(0, 100).fraction)
        assertEquals(1.0, PackState.Downloading(100, 100).fraction)
        assertEquals(0.0, PackState.Downloading(0, 0).fraction)
    }
}
