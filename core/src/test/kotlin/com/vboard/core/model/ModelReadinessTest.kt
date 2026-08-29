package com.vboard.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelReadinessTest {

    private val streaming = ModelCatalog.byId("zipformer-en-streaming")!!
    private val parakeet = ModelCatalog.byId("parakeet-tdt-0.6b-v2")!!
    private val refiner = ModelCatalog.byId("qwen25-05b-refiner")!!

    // ------------------------------------------------ "enough to dictate"

    @Test
    fun `nothing installed means the user cannot dictate`() {
        assertFalse(ModelReadiness.canDictate(emptySet()))
    }

    @Test
    fun `the streaming pack alone is enough to dictate`() {
        assertTrue(ModelReadiness.canDictate(setOf(streaming.id)))
    }

    @Test
    fun `the optional packs neither grant nor withhold the ability to dictate`() {
        // Present without the streaming pack: still no dictation.
        assertFalse(ModelReadiness.canDictate(setOf(parakeet.id)))
        assertFalse(ModelReadiness.canDictate(setOf(refiner.id)))
        assertFalse(ModelReadiness.canDictate(setOf(parakeet.id, refiner.id)))

        // Added on top of the streaming pack: no change either way.
        assertTrue(ModelReadiness.canDictate(setOf(streaming.id, parakeet.id)))
        assertTrue(ModelReadiness.canDictate(setOf(streaming.id, refiner.id)))
        assertTrue(ModelReadiness.canDictate(ModelCatalog.packs.map { it.id }.toSet()))
    }

    @Test
    fun `an unknown installed id cannot fake readiness`() {
        assertFalse(ModelReadiness.canDictate(setOf("some-other-pack")))
    }

    // ------------------------------------------------------ required set

    @Test
    fun `only the streaming pack is required, so setup asks for one download`() {
        assertEquals(listOf(streaming.id), ModelCatalog.requiredPacks.map { it.id })
        assertEquals(
            listOf(parakeet.id, refiner.id),
            ModelCatalog.optionalPacks.map { it.id },
        )
    }

    @Test
    fun `required is exactly the set dictation cannot run without`() {
        // If these ever diverge, one of the two definitions is lying to the user: either
        // setup blocks on a pack dictation does not need, or it lets them finish without
        // one it does.
        val installedIds = ModelCatalog.packs.map { it.id }.toSet()
        for (pack in ModelCatalog.packs) {
            val withoutIt = installedIds - pack.id
            assertEquals(
                pack.required,
                !ModelReadiness.canDictate(withoutIt),
                "${pack.id}: required=${pack.required} but canDictate-without=${ModelReadiness.canDictate(withoutIt)}",
            )
        }
    }

    @Test
    fun `missingRequired names only what still blocks dictation`() {
        assertEquals(listOf(streaming.id), ModelReadiness.missingRequired(emptySet()).map { it.id })
        assertEquals(
            emptyList(),
            ModelReadiness.missingRequired(setOf(streaming.id)).map { it.id },
        )
        // The optional packs never appear here, installed or not.
        assertEquals(
            emptyList(),
            ModelReadiness.missingRequired(setOf(streaming.id, parakeet.id, refiner.id)).map { it.id },
        )
    }

    @Test
    fun `remaining required bytes is the streaming pack, then zero`() {
        assertEquals(streaming.totalBytes, ModelReadiness.remainingRequiredBytes(emptySet()))
        assertEquals(0L, ModelReadiness.remainingRequiredBytes(setOf(streaming.id)))
        // Installing an optional pack does not reduce it, because it never counted.
        assertEquals(
            streaming.totalBytes,
            ModelReadiness.remainingRequiredBytes(setOf(parakeet.id, refiner.id)),
        )
    }

    @Test
    fun `upgrades on offer shrink as optional packs are installed`() {
        assertEquals(
            listOf(parakeet.id, refiner.id),
            ModelReadiness.availableUpgrades(setOf(streaming.id)).map { it.id },
        )
        assertEquals(
            listOf(refiner.id),
            ModelReadiness.availableUpgrades(setOf(streaming.id, parakeet.id)).map { it.id },
        )
        assertEquals(
            emptyList(),
            ModelReadiness.availableUpgrades(ModelCatalog.packs.map { it.id }.toSet()).map { it.id },
        )
    }

    // --------------------------------------------- catalog-independence

    @Test
    fun `readiness follows the pack list it is given, not the shipped catalog`() {
        val other = streaming.copy(id = "some-other-streaming-model")
        assertTrue(ModelReadiness.canDictate(setOf(other.id), packs = listOf(other)))
        assertFalse(ModelReadiness.canDictate(setOf(streaming.id), packs = listOf(other)))
    }
}
