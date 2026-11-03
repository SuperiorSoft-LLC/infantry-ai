package com.superiorsoftllc.infantry.update

/**
 * UPDATE-1: a semantic version, as a release tag (`v1.0.3`, `1.0.3`, `1.1.0-rc.1`) or the running app's
 * `-PinfantryVersion` (MAJOR.MINOR.BUILD). Build metadata (`+...`) is ignored, as SemVer says.
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val preRelease: List<String> = emptyList()) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch).let { if (it != 0) return it }
        // A release outranks any pre-release of the same version (1.0.0 > 1.0.0-rc.1).
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) return other.preRelease.size.coerceAtMost(1) - preRelease.size.coerceAtMost(1)
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val a = preRelease[i]
            val b = other.preRelease[i]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers rank below alphanumeric ones
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String = "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d{1,9})\.(\d{1,9})\.(\d{1,9})(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        /** The version in [text], or null when it is not `[v]MAJOR.MINOR.PATCH[-pre][+build]`. */
        fun parse(text: String?): SemVer? {
            val m = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
            val pre = m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (pre.any { it.isEmpty() }) return null
            return SemVer(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), pre)
        }
    }
}
