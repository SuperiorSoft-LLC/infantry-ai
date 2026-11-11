// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.keystore

/**
 * HARNESS-GAP G18 (ticket 5a69ec0f): masks every known secret value in text the daemon shows or keeps (session output,
 * chat rows, the transcript, a terminal's screen). [values] is read on every call and must be cheap: the daemon passes
 * [Keystore.allValues], which returns the same set object until the keystore changes, so the masking targets are rebuilt
 * only then. Values shorter than [MIN_SECRET_LENGTH] are left alone: masking "1" or "yes" would wreck the output and hide
 * nothing. A value's JSON-escaped form is masked too, because a CLI's stream-json line carries it escaped.
 * A failing [values] masks nothing rather than breaking the output path.
 */
class SecretMask(private val values: () -> Collection<String>) {
    private class Targets(val source: Collection<String>?, val list: List<String>)

    @Volatile private var targets = Targets(null, emptyList())

    /** True when at least one value is long enough to be masked. */
    val active: Boolean get() = current().isNotEmpty()

    /** [text] with every known value replaced by [MASK]. */
    fun mask(text: String): String {
        if (text.isEmpty()) return text
        val list = current()
        if (list.isEmpty()) return text
        var out = text
        for (t in list) if (out.contains(t)) out = out.replace(t, MASK)
        return out
    }

    /**
     * A masker for a stream of chunks that can split a value (a terminal's output): the end of a chunk that could begin a
     * value is held back until the next chunk (or [Stream.flush]) says whether it does. Only such a prefix is held, so
     * ordinary output is never delayed. Not thread-safe: one per stream.
     */
    fun stream(): Stream = Stream()

    inner class Stream internal constructor() {
        private var held = ""

        fun chunk(text: String): String {
            val list = current()
            if (list.isEmpty()) return (held + text).also { held = "" }
            val data = mask(held + text)
            val keep = list.maxOf { heldPrefix(data, it) }
            held = data.substring(data.length - keep)
            return data.substring(0, data.length - keep)
        }

        /** What is still held back: the stream ended, so it cannot become a value any more. */
        fun flush(): String = held.also { held = "" }

        /** The length of the longest proper prefix of [t] that [data] ends with. */
        private fun heldPrefix(data: String, t: String): Int {
            for (n in minOf(t.length - 1, data.length) downTo 1) if (data.regionMatches(data.length - n, t, 0, n)) return n
            return 0
        }
    }

    private fun current(): List<String> {
        val source = runCatching(values).getOrNull() ?: return emptyList()
        val t = targets
        if (t.source === source) return t.list
        val list = source.filter { it.length >= MIN_SECRET_LENGTH }
            .flatMap { listOf(it, jsonEscaped(it)) }
            .distinct().sortedByDescending { it.length }
        targets = Targets(source, list)
        return list
    }

    override fun toString(): String = "SecretMask(${targets.list.size} targets)"

    companion object {
        /**
         * RUNNER-CRED-1: one value source over several ([parts]), for a mask that also covers values held outside the keystore (a
         * runner's lent credentials). The result keeps its identity while the union is unchanged, so the mask's target cache holds.
         */
        fun union(vararg parts: () -> Collection<String>): () -> Collection<String> {
            var last: Set<String> = emptySet()
            return {
                val now = parts.flatMapTo(LinkedHashSet()) { p -> runCatching(p).getOrDefault(emptyList()) }
                synchronized(this) { if (now != last) last = now; last }
            }
        }

        /** Shorter values are not masked (see the class doc). The same floor as the Run plugin's scrubber. */
        const val MIN_SECRET_LENGTH = 4
        const val MASK = "••••"

        /** NONE: the mask a daemon with no keystore uses. */
        val NONE = SecretMask { emptyList() }

        internal fun jsonEscaped(s: String): String {
            val b = StringBuilder(s.length + 8)
            for (c in s) when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c < ' ' -> b.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> b.append(c)
            }
            return b.toString()
        }
    }
}
