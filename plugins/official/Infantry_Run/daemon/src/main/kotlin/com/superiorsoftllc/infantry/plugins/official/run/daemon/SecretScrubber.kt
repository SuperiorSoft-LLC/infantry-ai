package com.superiorsoftllc.infantry.plugins.official.run.daemon

/**
 * SECRETS-2: masks the secret values a run was given (as environment variables) in what the run prints, before any of it
 * reaches a frame, the scrollback or a log. Values shorter than [MIN_LENGTH] are left alone: masking "1" or "yes" would
 * wreck the output and hide nothing.
 *
 * [bytes] is for a byte stream: a value split across two chunks is still masked, because the trailing bytes of a chunk
 * that could start a value are held back until the next chunk (or [flush]) says whether they do. Only such a prefix is
 * held, so ordinary output is never delayed. [line] is for whole lines (the piped runner's).
 */
class SecretScrubber(values: Collection<String>) {
    private val targets: List<ByteArray> = values.filter { it.length >= MIN_LENGTH }.distinct()
        .sortedByDescending { it.length }.map { it.toByteArray() }
    private val strings: List<String> = targets.map { String(it) }
    private var held = ByteArray(0)

    val active: Boolean get() = targets.isNotEmpty()

    fun line(text: String): String {
        var out = text
        for (s in strings) out = out.replace(s, MASK)
        return out
    }

    /** The chunk with every value masked; bytes that may begin a value are kept for the next call. */
    fun bytes(chunk: ByteArray): ByteArray {
        if (!active) return chunk
        val data = held + chunk
        val out = java.io.ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            val hit = targets.firstOrNull { matchesAt(data, i, it) }
            if (hit != null) {
                out.write(MASK_BYTES)
                i += hit.size
                continue
            }
            if (targets.any { isPrefixAt(data, i, it) }) break
            out.write(data[i].toInt())
            i++
        }
        held = data.copyOfRange(i, data.size)
        return out.toByteArray()
    }

    /** What is still held back: the run is over, so it cannot become a value any more. */
    fun flush(): ByteArray = held.also { held = ByteArray(0) }

    private fun matchesAt(data: ByteArray, at: Int, t: ByteArray): Boolean {
        if (at + t.size > data.size) return false
        for (k in t.indices) if (data[at + k] != t[k]) return false
        return true
    }

    /** The bytes from [at] to the end are a proper prefix of [t]. */
    private fun isPrefixAt(data: ByteArray, at: Int, t: ByteArray): Boolean {
        val n = data.size - at
        if (n >= t.size) return false
        for (k in 0 until n) if (data[at + k] != t[k]) return false
        return true
    }

    override fun toString(): String = "SecretScrubber(${targets.size} values)"

    companion object {
        const val MIN_LENGTH = 4
        const val MASK = "••••"
        private val MASK_BYTES = MASK.toByteArray()
    }
}
