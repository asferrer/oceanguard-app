package com.oceanguard.ai.inference.species

import java.io.File
import java.security.MessageDigest

/**
 * Decisions of the species pack update (a newer `version` in the remote manifest), kept apart from
 * the downloader so they can be tested on the JVM. New files are downloaded next to the installed
 * ones with the [STAGE_SUFFIX] and only replace them once all of them are verified.
 */
object SpeciesPackUpdate {
    const val STAGE_SUFFIX = ".new"

    /** True when both versions are known and the remote one is newer than the installed one. */
    fun isNewer(installedVersion: Int?, remoteVersion: Int?): Boolean =
        installedVersion != null && remoteVersion != null && remoteVersion > installedVersion

    /**
     * Whether the installed file [name] can stay as it is for the pack described by [config].
     * With a `sha256` for the file in the manifest it must match; without one only the files that
     * do not change between pack versions (the encoder and the geographic files) are reused, the
     * index and the catalogue are downloaded again.
     */
    fun canReuse(
        name: String,
        config: SpeciesPackConfig,
        exists: Boolean,
        length: Long,
        minBytes: Long,
        sha256: () -> String?,
    ): Boolean {
        if (!exists || length < minBytes) return false
        val expected = config.sha256?.get(name) ?: return name == config.encoderFile ||
            SpeciesPackAssets.GEO.any { it.first == name }
        return sha256()?.equals(expected, ignoreCase = true) == true
    }

    /** True when [expected] is null (nothing to check) or the SHA-256 of [file] equals it. */
    fun matches(file: File, expected: String?): Boolean =
        expected == null || sha256(file).equals(expected, ignoreCase = true)

    /** Lower-case hex SHA-256 of [file]. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Approximate bytes an update from [installed] to [remote] downloads: the full pack size minus the installed files
     * it keeps (the encoder when its file name does not change, and the geographic files). [localLength] gives the size
     * of an installed file (0 when missing). Used for the size shown before updating, not for any check.
     */
    fun approxUpdateBytes(
        installed: SpeciesPackConfig,
        remote: SpeciesPackConfig,
        fullBytes: Long,
        localLength: (String) -> Long,
    ): Long {
        val kept = buildSet {
            if (remote.encoderFile == installed.encoderFile) add(remote.encoderFile)
            addAll(SpeciesPackAssets.GEO.map { it.first })
        }
        return (fullBytes - kept.sumOf(localLength)).coerceAtLeast(0L)
    }

    /** Files of the [old] pack that the [new] one no longer uses (safe to delete after the swap). */
    fun orphans(old: SpeciesPackConfig, new: SpeciesPackConfig): List<String> {
        val keep = (new.requiredFiles() + new.optionalFiles()).toSet()
        return (old.requiredFiles() + old.optionalFiles()).filter { it !in keep }
    }
}
