package com.harithkavish.store.catalog

/**
 * Ported from Jarvis's own in-app updater (my_chatgpt/mobile/App.tsx,
 * normalizeSemver/isVersionDifferent) so Store compares versions the same way
 * every app in the ecosystem already does.
 */
object SemVer {

    data class Triple(val major: Int, val minor: Int, val patch: Int)

    fun normalize(version: String?): Triple {
        val cleaned = (version ?: "").trim().removePrefix("v").removePrefix("V")
        val parts = cleaned.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patchRaw = parts.getOrNull(2) ?: "0"
        val patch = Regex("^\\d+").find(patchRaw)?.value?.toIntOrNull() ?: 0
        return Triple(major, minor, patch)
    }

    /** True when [latest] and [current] name different versions, in either direction. */
    fun isDifferent(latest: String?, current: String?): Boolean = normalize(latest) != normalize(current)

    /** True when [latest] is newer than [current]. */
    fun isNewer(latest: String?, current: String?): Boolean {
        val l = normalize(latest)
        val c = normalize(current)
        if (l.major != c.major) return l.major > c.major
        if (l.minor != c.minor) return l.minor > c.minor
        return l.patch > c.patch
    }
}
