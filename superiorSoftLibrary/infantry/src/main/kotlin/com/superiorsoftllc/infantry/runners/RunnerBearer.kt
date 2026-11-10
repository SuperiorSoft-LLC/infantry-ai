// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * RUNNER-PAIR-1 (owner's 2-step requirement): the runner's long-lived pairing BEARER, made once on the runner (the image's first
 * `docker run`) and valid until rotated (`infantry runner bearer --rotate` there). Step 1 prints it next to the runner's endpoint;
 * step 2 pastes both into the app's Add runner. No code timing: unlike a [PairCodes] code it does not expire and may pair again
 * (the same orchestrator re-adding it gets a fresh link credential; another orchestrator is refused while this one is paired).
 *
 * The bearer TEXT the owner copies is `irb1.<runner name>.<certificate sha-256 hex | plain>.<secret>` ([Token]): the name and the
 * certificate pin travel inside it, so the app needs only the endpoint and this one string and still pins TLS before the secret
 * is sent. Only the secret is a secret; it is 160 random bits, so guessing it is not a concern, and the compare is constant time.
 *
 * Stored in [file] (`<state>/runner-link/bearer`, owner-only permissions where the file system has them): `name` then `secret`.
 */
class RunnerBearer(private val file: File, private val random: SecureRandom = SecureRandom()) {
    /** The runner name and secret this runner pairs with, or null when no bearer was made (the runner then pairs by code only). */
    data class Value(val name: String, val secret: String) {
        override fun toString(): String = "RunnerBearer.Value(name=$name, secret=redacted)"
    }

    fun exists(): Boolean = read() != null

    fun read(): Value? = runCatching {
        if (!file.isFile) return null
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val name = lines.getOrNull(0) ?: return null
        val secret = lines.getOrNull(1) ?: return null
        if (!RunnerLinkStore.NAME.matches(name) || !SECRET.matches(secret)) return null
        Value(name, secret)
    }.getOrNull()

    /**
     * The bearer, made now when there is none. [rotate] replaces the secret (every earlier bearer is refused at once) and [name], when
     * given, renames the runner (a rename replaces the secret too, since the old bearer text names the old runner).
     */
    @Synchronized
    fun ensure(name: String?, rotate: Boolean = false): Value {
        val current = read()
        val wanted = name?.takeIf { it.isNotBlank() } ?: current?.name ?: DEFAULT_NAME
        require(RunnerLinkStore.NAME.matches(wanted)) { "runner name '$wanted' must be 1-32 of a-z, 0-9 and '-' (starting with a letter or digit)" }
        if (current != null && !rotate && current.name == wanted) return current
        val fresh = Value(wanted, (0 until SECRET_SIZE).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString(""))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText("${fresh.name}\n${fresh.secret}\n")
        ownerOnly(tmp)
        Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        return fresh
    }

    /** True when [presented] is this runner's live secret (constant-time compare); false with no bearer. */
    fun accepts(presented: String?): Boolean {
        val live = read() ?: return false
        val p = presented?.trim().orEmpty()
        if (p.isEmpty()) return false
        return MessageDigest.isEqual(digest(p), digest(live.secret))
    }

    private fun ownerOnly(f: File) {
        runCatching { Files.setPosixFilePermissions(f.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
    }

    private fun digest(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    /**
     * The one bearer string the owner copies: `irb1.<name>.<fp|plain>.<secret>`. [parse] errors are fixed text that never echoes
     * the input; [toString] hides the secret.
     */
    class Token(val name: String, val fingerprint: String?, val secret: String) {
        val plain: Boolean get() = fingerprint == null

        fun text(): String = "$PREFIX.$name.${fingerprint?.let(RunnerPairLink::normalizeFingerprint) ?: PLAIN}.$secret"

        override fun toString(): String = "RunnerBearer.Token(name=$name, fingerprint=${fingerprint ?: "plain"}, secret=redacted)"

        sealed interface Parsed {
            class Ok(val token: Token) : Parsed
            class Bad(val reason: String) : Parsed
        }

        companion object {
            const val PREFIX = "irb1"
            const val PLAIN = "plain"
            private val HEX = Regex("""^[0-9a-f]{64}$""")

            fun parse(text: String): Parsed {
                val clean = text.trim().removeSurrounding("'").removeSurrounding("\"").trim()
                val parts = clean.split('.')
                if (parts.size != 4 || parts[0] != PREFIX) {
                    return Parsed.Bad("That is not a runner bearer: it starts with $PREFIX. and is printed by the runner as 'bearer:' when it starts.")
                }
                val (_, name, fp, secret) = parts
                if (!RunnerLinkStore.NAME.matches(name)) return Parsed.Bad("The runner name inside the bearer is malformed; copy the whole bearer line again.")
                val fingerprint = if (fp == PLAIN) null else fp.lowercase().takeIf { HEX.matches(it) }
                    ?: return Parsed.Bad("The certificate fingerprint inside the bearer is malformed; copy the whole bearer line again.")
                if (!SECRET.matches(secret)) return Parsed.Bad("The bearer is cut short or malformed; copy the whole bearer line again.")
                return Parsed.Ok(Token(name, fingerprint, secret))
            }
        }
    }

    /**
     * RUNNER-PAIR-1: the endpoint the owner pastes beside the bearer: `wss://host:port`, `ws://host:port` (a plain runner),
     * `host:port` or a bare `host` ([defaultPort] then). Returns (host, port) or the fixed-text reason it is not one.
     */
    object Endpoint {
        data class Parsed(val host: String?, val port: Int, val reason: String?)

        fun parse(text: String, defaultPort: Int): Parsed {
            var t = text.trim().removeSurrounding("'").removeSurrounding("\"").trim()
            val scheme = t.substringBefore("://", "")
            if (scheme.isNotEmpty()) {
                if (scheme.lowercase() !in setOf("wss", "ws", "https", "http")) return Parsed(null, 0, "The endpoint must be wss://<host>:<port>, as the runner printed it.")
                t = t.substringAfter("://")
            }
            t = t.substringBefore('/').trim()
            if (t.isEmpty()) return Parsed(null, 0, "The endpoint has no host; paste the 'endpoint:' line the runner printed.")
            val host: String
            val portText: String?
            if (t.startsWith("[")) {
                host = t.substringBefore(']') + "]"
                portText = t.substringAfter("]", "").removePrefix(":").takeIf { it.isNotEmpty() }
            } else if (t.count { it == ':' } == 1) {
                host = t.substringBefore(':')
                portText = t.substringAfter(':')
            } else {
                host = t
                portText = null
            }
            if (!com.superiorsoftllc.infantry.settings.RunnerLinkSettings.HOST.matches(host)) return Parsed(null, 0, "The endpoint's host is not a host name or address.")
            val port = if (portText == null) defaultPort else portText.toIntOrNull()?.takeIf { it in 1..LAST_PORT }
                ?: return Parsed(null, 0, "The endpoint's port is not a port number.")
            return Parsed(RunnerPairLink.bracketed(host), port, null)
        }

        private const val LAST_PORT = 65535
    }

    companion object {
        const val FILE = "bearer"
        /** The runner's name when nothing names it (`INFANTRY_RUNNER_NAME` / `--name` do). */
        const val DEFAULT_NAME = "runner"
        /** Crockford-style base32 without I, L, O, U: 32 symbols = 160 bits. */
        private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"
        private const val SECRET_SIZE = 32
        private val SECRET = Regex("""^[0-9a-hjkmnp-tv-z]{32}$""")

        fun of(stateDir: File): RunnerBearer = RunnerBearer(File(RunnerLinkStore.dir(stateDir), FILE))
    }
}
