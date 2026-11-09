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
 *
 * RUNNER-GRANT-AT-PAIR-1: the console may issue a code that carries a GRANT authority ([issue]'s `grant`): redeeming it lets the
 * orchestrator add one project and some installed harnesses to this runner's `runner.projects` / `runner.harnesses`, once. The
 * authority lives and dies with the code ([Redeem.Ok.`authority`]) and is never read back from the link: only this record counts.
 */
class PairCodes(
    private val attemptsFile: File?,
    private val expiryMs: () -> Long,
    private val attemptsPerHour: () -> Long,
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * RUNNER-GRANT-AT-PAIR-1: what the console let the live code do. [harnessCeiling] is the runner console's `--grant-harnesses-max`
     * (empty = whatever is installed here); the orchestrator may never widen it.
     */
    data class Authority(val grant: Boolean = false, val harnessCeiling: List<String> = emptyList())

    /** The answer to one redeem: the authority of the spent code, or why it was refused (nothing was spent then). */
    sealed interface Redeem {
        data class Ok(val authority: Authority) : Redeem
        data class Refused(val reason: String) : Redeem
    }

    private data class Live(val digest: ByteArray, val name: String, val expiresAt: Long, val authority: Authority)

    @Volatile private var live: Live? = null
    private val attempts = ArrayDeque<Long>()

    init {
        attemptsFile?.takeIf { it.isFile }?.readLines()?.mapNotNull { it.trim().toLongOrNull() }?.forEach(attempts::addLast)
    }

    /** Issues a new code for the runner [name] (any live one is replaced); shown as `XXXX-XXXX-XXXX-XXXX`. */
    @Synchronized
    @JvmOverloads
    fun issue(name: String, now: Long, grant: Boolean = false, harnessCeiling: List<String> = emptyList()): String {
        val raw = (0 until CODE_SIZE).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        live = Live(digest(raw), name, now + expiryMs(), Authority(grant, harnessCeiling))
        return raw.chunked(GROUP).joinToString("-")
    }

    /** The runner name the live code was issued for, or null when none is live. */
    fun liveName(now: Long): String? = live?.takeIf { now < it.expiresAt }?.name

    /** Whether the live code carries the grant authority (the console's `--grant-on-pair`), so `runner pair` can print it. */
    fun liveAuthority(now: Long): Authority? = live?.takeIf { now < it.expiresAt }?.authority

    /** Why [code] is refused at [now], or [Redeem.Ok] with the spent code's authority. */
    @Synchronized
    fun redeem(code: String?, now: Long): Redeem {
        val hour = now - MS_PER_HOUR
        while (attempts.isNotEmpty() && attempts.first() <= hour) attempts.removeFirst()
        if (attempts.size >= attemptsPerHour()) {
            live = null
            return Redeem.Refused("too many pairing attempts (limits.runners.pairAttemptsPerHour = ${attemptsPerHour()}); the code is burned, run `infantry runner pair` again later")
        }
        attempts.addLast(now)
        persist()
        val current = live ?: return Redeem.Refused("no pairing code is live on this runner (run `infantry runner pair` there)")
        if (now >= current.expiresAt) {
            live = null
            return Redeem.Refused("the pairing code expired (limits.runners.pairCodeMin); run `infantry runner pair` again")
        }
        if (!MessageDigest.isEqual(digest(normalize(code.orEmpty())), current.digest)) return Redeem.Refused("wrong pairing code")
        live = null // single use
        return Redeem.Ok(current.authority)
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
