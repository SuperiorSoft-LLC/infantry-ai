package com.superiorsoftllc.infantry.daemon.api.board

import com.superiorsoftllc.infantry.daemon.api.LocalFile
import com.superiorsoftllc.infantry.daemon.api.LocalFiles
import java.security.MessageDigest

/**
 * RUNTIME-LOAD-4 (BOARD_API_VERSION 1.3.0): the rules of a ticket's images (IMG-AGENT-1), shared by `infantry ticket create|update
 * --image` (CLI) and Infantry_Board's ticket tools. Pure: a file is a [LocalFile] (what the caller's machine holds), so the same
 * check runs where the file is read and where it is judged. Stored images live under the project's [TICKET_UPLOADS], named by
 * their sha256; a path already there is passed through, never uploaded again.
 */
object TicketImages {
    /** Where a ticket's images are stored, relative to its project directory. */
    const val TICKET_UPLOADS = ".infantry/uploads/ticket/"

    /** At most this many bytes per image (the daemon's `image_upload` limit). */
    const val MAX_BYTES: Long = LocalFiles.MAX_BYTES

    /** True when [path] already names a stored ticket image (relPath or absolute), so it is not uploaded again. */
    fun isStored(path: String): Boolean {
        val slashed = path.trim().replace('\\', '/')
        return slashed.startsWith(TICKET_UPLOADS) || slashed.contains("/$TICKET_UPLOADS")
    }

    /** Why [files] cannot be attached, or null when every local file among them is an acceptable image. */
    fun check(files: List<LocalFile>): String? {
        for (file in files) {
            val path = file.path.trim()
            if (path.isEmpty()) return "an image path is blank"
            if (isStored(path)) continue
            val size = file.size ?: return "image '${path.take(200)}' does not exist (no such file)"
            if (size == 0L) return "image '${path.take(200)}' is empty"
            if (size > MAX_BYTES) return "image '${path.take(200)}' is over the 10 MB limit ($size bytes, at most $MAX_BYTES)"
            if (!isImage(file.bytes ?: ByteArray(0))) {
                return "image '${path.take(200)}' is not a png, jpeg, gif or webp image (its first bytes match none of them)"
            }
        }
        return null
    }

    /**
     * True when [given] names [stored] (a relPath): the relPath itself, its absolute path, its file name, or a local file with the
     * same bytes (stored images are named by their sha256).
     */
    fun matches(stored: String, given: LocalFile): Boolean {
        val want = given.path.trim().replace('\\', '/')
        val name = stored.substringAfterLast('/')
        if (want == stored || want.endsWith("/$stored") || want == name) return true
        val bytes = given.bytes ?: return false
        return !isStored(want) && (given.size ?: Long.MAX_VALUE) <= MAX_BYTES && name.substringBeforeLast('.') == sha256(bytes)
    }

    /** png, jpeg, gif or webp by their magic bytes (the daemon's own test). */
    fun isImage(bytes: ByteArray): Boolean {
        fun at(offset: Int, vararg expected: Int): Boolean =
            bytes.size >= offset + expected.size && expected.indices.all { (bytes[offset + it].toInt() and 0xFF) == expected[it] }
        fun ascii(offset: Int, text: String): Boolean = at(offset, *text.map { it.code }.toIntArray())
        return at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) || at(0, 0xFF, 0xD8, 0xFF) ||
            ascii(0, "GIF87a") || ascii(0, "GIF89a") || (ascii(0, "RIFF") && ascii(8, "WEBP"))
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * RUNTIME-LOAD-4 (BOARD_API_VERSION 1.3.0): the one rule for a context limit value (CONTEXT-LIMIT-1, docs/CONTEXT-LIMIT.md), moved
 * here from the daemon so the CLI, the daemon and a board plugin's tools read and print a limit the same way. A limit is a token
 * count; null = none (the harness's own window).
 */
object ContextLimits {
    /** Below this no agent can hold even its own instructions; a typo (`200` for `200k`) is refused, not run. */
    const val MIN = 10_000

    /** Above any model window on offer today (1M); a larger value is a typo, not a choice. */
    const val MAX = 10_000_000

    /** On `ticket_save`, this value removes a ticket's context limit (absent or null keeps it). */
    const val CLEAR = 0

    /** Null when [limit] is usable (or null), else why not. */
    fun problem(limit: Int?): String? = when {
        limit == null -> null
        limit < MIN || limit > MAX -> "context limit $limit is out of range: $MIN..$MAX tokens (e.g. 200000 or 200k)"
        else -> null
    }

    /**
     * Reads a user-typed limit: `200000`, `200k`, `1m`, `1.5M` (case-insensitive, `_`/`,` ignored). Blank, `0`, `off`,
     * `none` = no limit (null, [Parsed.cleared] true). Anything else is [Parsed.error].
     */
    fun parse(raw: String?): Parsed {
        val text = raw?.trim()?.lowercase()?.replace("_", "")?.replace(",", "").orEmpty()
        if (text.isEmpty() || text == "0" || text == "off" || text == "none") return Parsed(null, cleared = true)
        val m = Regex("""(\d+(?:\.\d+)?)([km]?)""").matchEntire(text)
            ?: return Parsed(null, error = "context limit '${raw!!.take(20)}' is not a token count (e.g. 200000, 200k, 1m, or off)")
        val n = m.groupValues[1].toDouble() * when (m.groupValues[2]) { "k" -> 1_000.0; "m" -> 1_000_000.0; else -> 1.0 }
        val value = if (n > Int.MAX_VALUE) Int.MAX_VALUE else n.toInt()
        return problem(value)?.let { Parsed(null, error = it) } ?: Parsed(value)
    }

    data class Parsed(val value: Int?, val cleared: Boolean = false, val error: String? = null)

    /** `200k`, `1m`, `1.5m`, or the plain number when it is not round. */
    fun label(limit: Int): String = when {
        limit % 1_000_000 == 0 -> "${limit / 1_000_000}m"
        limit % 100_000 == 0 && limit > 1_000_000 -> "${limit / 1_000_000.0}m"
        limit % 1_000 == 0 -> "${limit / 1_000}k"
        else -> limit.toString()
    }
}

/**
 * EFFORT-1 (BOARD_API_VERSION 1.4.0): the shared rule for a reasoning-effort level (docs/EFFORT.md), the same list the daemon keeps
 * (`session.EffortLevels`), so a board plugin's tools read a level the same way. Null = the harness's own default (the off switch).
 */
object EffortLevels {
    /** Lowest to highest. */
    val ids: List<String> = listOf("low", "medium", "high", "xhigh", "max")

    /** Null when [effort] is unset or a known level, else why not. */
    fun problem(effort: String?): String? =
        if (effort == null || effort in ids) null else "unknown effort '${effort.take(40)}': one of $ids"

    /** A user-typed level: blank, `off`, `none`, `default` = unset ([Parsed.cleared]); a level in any case; else [Parsed.error]. */
    fun parse(raw: String?): Parsed {
        val text = raw?.trim()?.lowercase().orEmpty()
        if (text.isEmpty() || text == "off" || text == "none" || text == "default") return Parsed(null, cleared = true)
        return if (text in ids) Parsed(text) else Parsed(null, error = "unknown effort '${text.take(40)}': one of $ids (or off)")
    }

    data class Parsed(val value: String?, val cleared: Boolean = false, val error: String? = null)
}
