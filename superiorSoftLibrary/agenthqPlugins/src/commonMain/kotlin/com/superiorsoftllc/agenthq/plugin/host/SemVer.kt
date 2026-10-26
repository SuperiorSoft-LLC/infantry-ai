package com.superiorsoftllc.agenthq.plugin.host

/**
 * PLUGINS-2: a semantic version, `MAJOR.MINOR.PATCH[-pre][+build]`. Missing parts are 0 (`1.2` is 1.2.0); build
 * metadata is ignored; a pre-release sorts before its release (`1.0.0-rc.1 < 1.0.0`), its parts compared as numbers
 * when both are numbers, else as text.
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val pre: List<String> = emptyList()) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        if (pre.isEmpty() || other.pre.isEmpty()) return other.pre.size.coerceAtMost(1) - pre.size.coerceAtMost(1)
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]; val b = other.pre[i]
            val an = a.toIntOrNull(); val bn = b.toIntOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    override fun toString(): String = "$major.$minor.$patch" + if (pre.isEmpty()) "" else pre.joinToString(".", "-")

    companion object {
        private val FORM = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(text: String): SemVer = parseOrNull(text) ?: throw IllegalArgumentException("'$text' is not a version (MAJOR.MINOR.PATCH)")

        fun parseOrNull(text: String): SemVer? = partial(text)?.let { (v, _) -> v }

        /** The version and how many of its three numbers were written (1 for `1`, 2 for `1.2`, 3 for `1.2.3`). */
        internal fun partial(text: String): Pair<SemVer, Int>? {
            val m = FORM.matchEntire(text.trim()) ?: return null
            val nums = (1..3).map { m.groupValues[it] }
            val written = nums.count { it.isNotEmpty() }
            val (a, b, c) = nums.map { it.ifEmpty { "0" }.toIntOrNull() ?: return null }
            return SemVer(a, b, c, m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()) to written
        }
    }
}

/**
 * PLUGINS-2: a version range, the npm subset a module.json needs, parsed without a library:
 * - `*`, `x` or empty: any version;
 * - comparators `>=1.2`, `>1.2`, `<=2`, `<2`, `=1.2.3`, joined by spaces (all must hold): `>=1.2 <2`;
 * - a bare version: `1.2.3` exactly, `1.2` any 1.2.x, `1` any 1.x;
 * - `^1.2.3` (same major; same minor below 1.0), `~1.2.3` (same minor);
 * - alternatives joined by `||`: `^1.0 || ^2.0`.
 */
class VersionRange private constructor(private val text: String, private val sets: List<List<Pair<String, SemVer>>>) {
    fun contains(v: SemVer): Boolean = sets.any { set -> set.all { (op, bound) -> holds(op, v.compareTo(bound)) } }

    override fun toString(): String = text

    companion object {
        private val COMPARATOR = Regex("""^(>=|<=|>|<|=|\^|~)?\s*(.+)$""")

        val ANY: VersionRange = VersionRange("*", listOf(emptyList()))

        fun parse(text: String): VersionRange = parseOrNull(text) ?: throw IllegalArgumentException("'$text' is not a version range")

        fun parseOrNull(text: String): VersionRange? {
            val sets = text.split("||").map { alt ->
                val tokens = joinOperators(alt.trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
                tokens.flatMap { comparator(it) ?: return null }
            }
            return VersionRange(text.trim().ifEmpty { "*" }, sets)
        }

        /** `>= 1.2` written with a space is one comparator. */
        private fun joinOperators(tokens: List<String>): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < tokens.size) {
                val t = tokens[i]
                if (t in setOf(">=", "<=", ">", "<", "=", "^", "~") && i + 1 < tokens.size) { out += t + tokens[i + 1]; i += 2 } else { out += t; i++ }
            }
            return out
        }

        private fun comparator(token: String): List<Pair<String, SemVer>>? {
            if (token == "*" || token.equals("x", ignoreCase = true)) return emptyList()
            val m = COMPARATOR.matchEntire(token) ?: return null
            val op = m.groupValues[1]
            val (v, written) = SemVer.partial(m.groupValues[2]) ?: return null
            // The first version above everything the written parts match: 1 -> 2.0.0, 1.2 -> 1.3.0.
            val nextOfWritten = when (written) { 1 -> SemVer(v.major + 1, 0, 0); 2 -> SemVer(v.major, v.minor + 1, 0); else -> null }
            return when (op) {
                ">=", ">", "<" -> listOf(op to v).let { if (op == ">" && nextOfWritten != null) listOf(">=" to nextOfWritten) else it }
                "<=" -> if (nextOfWritten != null) listOf("<" to nextOfWritten) else listOf("<=" to v)
                "^" -> listOf(">=" to v, "<" to when {
                    v.major > 0 || written == 1 -> SemVer(v.major + 1, 0, 0)
                    v.minor > 0 || written == 2 -> SemVer(0, v.minor + 1, 0)
                    else -> SemVer(0, 0, v.patch + 1)
                })
                "~" -> listOf(">=" to v, "<" to (if (written == 1) SemVer(v.major + 1, 0, 0) else SemVer(v.major, v.minor + 1, 0)))
                else -> if (nextOfWritten != null) listOf(">=" to v, "<" to nextOfWritten) else listOf("=" to v)
            }
        }

        private fun holds(op: String, c: Int): Boolean = when (op) {
            ">=" -> c >= 0
            ">" -> c > 0
            "<=" -> c <= 0
            "<" -> c < 0
            else -> c == 0
        }
    }
}
