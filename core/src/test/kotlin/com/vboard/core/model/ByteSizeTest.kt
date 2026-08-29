package com.vboard.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ByteSizeTest {

    @Test
    fun `formats megabytes below a gigabyte and gigabytes above`() {
        assertEquals("0 MB", ByteSize.format(0))
        assertEquals("1 MB", ByteSize.format(1_000_000))
        assertEquals("128 MB", ByteSize.format(127_887_156))
        assertEquals("482 MB", ByteSize.format(482_468_385))
        assertEquals("999 MB", ByteSize.format(999_000_000))
        assertEquals("1.0 GB", ByteSize.format(1_000_000_000))
        assertEquals("1.2 GB", ByteSize.format(1_157_355_541))
    }

    @Test
    fun `rounds rather than truncating, so a download is never advertised smaller than it is`() {
        // 127.9 MB truncates to "127 MB" - understating is the failure mode that stings.
        assertEquals("128 MB", ByteSize.format(127_900_000))
        assertEquals("2 MB", ByteSize.format(1_500_000))
        assertEquals("1 MB", ByteSize.format(1_499_999))
    }

    @Test
    fun `a negative or absurd count never renders as garbage`() {
        assertEquals("0 MB", ByteSize.format(-1))
        assertTrue(ByteSize.format(Long.MAX_VALUE).endsWith(" GB"))
    }

    // -------------------------------------------------- catalog-derived copy

    @Test
    fun `shipped catalog splits into the two speech packs and an optional refiner`() {
        val sizes = DownloadSizes.of()
        // Both speech models are required: streaming alone is not the typing experience.
        assertEquals(127_887_156L + 482_468_385L, sizes.requiredBytes)
        assertEquals(547_000_000L, sizes.optionalBytes)
        assertEquals(1_157_355_541L, sizes.totalBytes)

        assertEquals("610 MB", sizes.requiredText)
        assertEquals("1.2 GB", sizes.totalText)
    }

    @Test
    fun `size copy is derived from the catalog, so changing a pack size changes the words`() {
        val packs = ModelCatalog.packs.map { pack ->
            if (pack.required) {
                pack.copy(files = pack.files.map { it.copy(sizeBytes = 2_000_000_000L) })
            } else {
                pack
            }
        }
        val sizes = DownloadSizes.of(packs)

        // Two required packs, each rewritten to 2 GB.
        assertEquals(4_000_000_000L, sizes.requiredBytes)
        assertEquals("4.0 GB", sizes.requiredText)
        // and the total moves with it rather than staying on a hardcoded number
        assertEquals("4.5 GB", sizes.totalText)
    }

    @Test
    fun `flipping a pack between required and optional moves its bytes across the split`() {
        val allRequired = ModelCatalog.packs.map { it.copy(required = true) }
        val sizes = DownloadSizes.of(allRequired)
        assertEquals(1_157_355_541L, sizes.requiredBytes)
        assertEquals(0L, sizes.optionalBytes)
        // This is the figure setup used to demand before the accuracy pack became optional:
        // the two speech packs together.
        assertEquals(
            "610 MB",
            ByteSize.format(127_887_156L + 482_468_385L),
        )
    }
}
