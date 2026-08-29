package com.vboard.app.models

import java.io.File
import java.io.IOException

/**
 * The rules for *which* directory the model packs live in, and how they get
 * moved between two of them — with no Android in sight, so they can be tested
 * against real directories instead of an emulated storage volume.
 *
 * [ModelStore] owns the Android half: finding the external media directory,
 * deciding whether it is mounted, and when to run the migration.
 */
internal object ModelRoots {

    /** Dot-prefixed so [hasPacks] cannot mistake an in-flight copy for a pack. */
    const val STAGING_PREFIX = ".staging-"

    /**
     * A root "has packs" when a pack directory has been installed under it.
     * Dot-prefixed entries do not count: a migration in flight leaves staging
     * directories behind, and treating one as a pack would point the next
     * process at a root whose models have not arrived yet.
     */
    fun hasPacks(root: File): Boolean =
        root.isDirectory &&
            root.listFiles()?.any { it.isDirectory && !it.name.startsWith(".") } == true

    /**
     * [external] when it is usable — but never mid-flight. A process that
     * already has packs on internal storage keeps reading them from there until
     * the copy has landed, so the mic never reports "not installed" for models
     * that are on the device.
     */
    fun choose(external: File?, internal: File): File {
        if (external == null) return internal
        if (hasPacks(external)) return external
        if (hasPacks(internal)) return internal
        return external
    }

    /**
     * Copies pack directories from [from] to [to], skipping any that are already
     * there, and returns how many arrived.
     *
     * Pack by pack, each through its own staging directory renamed into place.
     * That granularity is what makes an interrupted migration useful: the packs
     * that made it are usable immediately and the rest are picked up next time,
     * instead of a gigabyte of work being thrown away. Nothing is deleted here —
     * the caller is still reading the originals.
     */
    fun copyPacks(from: File, to: File, pid: Int, onError: (String, Throwable) -> Unit): Int {
        var moved = 0
        for (packDir in from.listFiles()?.filter { it.isDirectory }.orEmpty()) {
            if (packDir.name.startsWith(".")) continue
            val target = File(to, packDir.name)
            if (target.exists()) continue
            val staging = File(to, "$STAGING_PREFIX$pid-${packDir.name}")
            staging.deleteRecursively()
            try {
                if (!packDir.copyRecursively(staging, overwrite = true)) {
                    throw IOException("copy did not complete")
                }
                if (!staging.renameTo(target)) throw IOException("cannot activate the copy")
                moved++
            } catch (e: Throwable) {
                onError(packDir.name, e)
                staging.deleteRecursively()
            }
        }
        return moved
    }

    /** Bytes that [copyPacks] would have to write to move everything under [root]. */
    fun sizeOf(root: File): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
