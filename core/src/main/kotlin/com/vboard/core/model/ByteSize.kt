package com.vboard.core.model

import java.util.Locale

/**
 * Human-readable transfer sizes, in the decimal units carriers and app stores use.
 *
 * This is the *only* place VBoard turns a byte count into words. Setup copy used to carry
 * hand-written figures ("about 1 GB", "about 1.4 GB") that drifted from
 * [ModelCatalog] until the scariest number on the bail-out screen overstated the real
 * download by 65%. Everything user-visible now derives from the catalog through here, so a
 * size can only ever be wrong if the catalog itself is.
 */
object ByteSize {

    private const val MB = 1_000_000L
    private const val GB = 1_000_000_000L

    /**
     * "128 MB", "1.2 GB". Rounds rather than truncates: truncation always understates, and a
     * download that turns out bigger than advertised is the failure mode users punish.
     */
    fun format(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return if (safe >= GB) {
            String.format(Locale.US, "%.1f GB", safe.toDouble() / GB)
        } else {
            "${(safe + MB / 2) / MB} MB"
        }
    }
}

/**
 * The download figures for a catalog, split the way a user experiences them: what setup
 * actually asks for, and what the optional upgrades add on top.
 *
 * Built from a pack list rather than reading [ModelCatalog] directly so tests can prove the
 * rendered copy follows the catalog instead of a constant.
 */
data class DownloadSizes(
    val requiredBytes: Long,
    val optionalBytes: Long,
) {
    /** Everything in the catalog, required and optional. */
    val totalBytes: Long get() = requiredBytes + optionalBytes

    val requiredText: String get() = ByteSize.format(requiredBytes)

    val optionalText: String get() = ByteSize.format(optionalBytes)

    val totalText: String get() = ByteSize.format(totalBytes)

    companion object {
        fun of(packs: List<ModelPack> = ModelCatalog.packs): DownloadSizes = DownloadSizes(
            requiredBytes = packs.filter { it.required }.sumOf { it.totalBytes },
            optionalBytes = packs.filterNot { it.required }.sumOf { it.totalBytes },
        )
    }
}
