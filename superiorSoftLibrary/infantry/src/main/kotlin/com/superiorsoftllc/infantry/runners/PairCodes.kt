// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * RUNNER-POOL-5 (docs/RUNNER-POOL.md section 7): the runner's one-time pairing code. One code is live at a time; it is single use,
 * expires [expiryMs] after it was issued (`limits.runners.pairCodeMin`), and every redeem attempt, right or wrong, counts against
 * [attemptsPerHour] (`limits.runners.pairAttemptsPerHour`) over a rolling hour. Past the limit every attempt is refused AND the
 * live code is burned, so guessing ends the pairing rather than getting more tries. Attempts are kept in [attemptsFile], so a
 * second `runner pair` does not reset the count.
 */
class PairCodes(
    private val attemptsFile: File?,
    private val expiryMs: () -> Long,
    private val attemptsPerHour: () -> Long,
    private val random: SecureRandom = SecureRandom(),
) {
    private data class Live(val digest: ByteArray, val name: String, val expiresAt: Long)

    @Volatile private var live: Live? = null
    private val attempts = ArrayDeque<Long>()

    init {
        attemptsFile?.takeIf { it.isFile }?.readLines()?.mapNotNull { it.trim().toLongOrNull() }?.forEach(attempts::addLast)
    }

    /** Issues a new code for the runner [name] (any live one is replaced); shown as `XXXX-XXXX-XXXX-XXXX`. */
    @Synchronized
    fun issue(name: String, now: Long): String {
        val raw = (0 until CODE_SIZE).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        live = Live(digest(raw), name, now + expiryMs())
        return raw.chunked(GROUP).joinToString("-")
    }

    /** The runner name the live code was issued for, or null when none is live. */
    fun liveName(now: Long): String? = live?.takeIf { now < it.expiresAt }?.name

    /** Why [code] is refused at [now], or null: it was the live code, which is now used up. */
    @Synchronized
    fun redeem(code: String?, now: Long): String? {
        val hour = now - MS_PER_HOUR
        while (attempts.isNotEmpty() && attempts.first() <= hour) attempts.removeFirst()
        if (attempts.size >= attemptsPerHour()) {
            live = null
            return "too many pairing attempts (limits.runners.pairAttemptsPerHour = ${attemptsPerHour()}); the code is burned, run `infantry runner pair` again later"
        }
        attempts.addLast(now)
        persist()
        val current = live ?: return "no pairing code is live on this runner (run `infantry runner pair` there)"
        if (now >= current.expiresAt) {
            live = null
            return "the pairing code expired (limits.runners.pairCodeMin); run `infantry runner pair` again"
        }
        if (!MessageDigest.isEqual(digest(normalize(code.orEmpty())), current.digest)) return "wrong pairing code"
        live = null // single use
        return null
    }

    private fun persist() {
        val file = attemptsFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(attempts.joinToString("\n"))
        }
    }

    private fun normalize(code: String) = code.uppercase().filter { it in ALPHABET }

    private fun digest(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    companion object {
        const val ATTEMPTS_FILE = "pair-attempts.txt"
        /** Crockford-style base32 without the look-alikes I, L, O, U: 16 symbols = 80 bits. */
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val CODE_SIZE = 16
        private const val GROUP = 4
        private const val MS_PER_HOUR = 3_600_000L
    }
}
