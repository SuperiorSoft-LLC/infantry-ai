package com.superiorsoftllc.agenthq.daemon

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * SEC-FIX-1 (contract 1.136.0): the OWNER credential, separate from the access token every agent receives as
 * `AGENTHQ_TOKEN`. A WebSocket connection is an owner connection when it presents it, as the [HEADER] header or (a
 * browser, which cannot set headers) the `agenthq.owner.<base64url>` subprotocol. Only an owner connection may change
 * saved commands, change secrets, or start, stop, attach to, resize or type into a run ([FrameGate.OWNER_ONLY]).
 *
 * The token is generated ONCE into `<stateDir>/owner.token` and reused. It is never put in a spawned agent's
 * environment. CAVEAT: an agent runs as the same OS user as the daemon, so a determined agent can still read this
 * file (and the keystore's key) from disk; the credential stops a frame an agent builds from what it was handed,
 * not an agent that reads the owner's files. Run agents as another OS user to close that too.
 */
class OwnerCredential(token: String) {
    init { require(token.length >= MIN_TOKEN_LENGTH) { "the owner token is shorter than $MIN_TOKEN_LENGTH characters" } }

    private val expected: ByteArray = digest(token)

    /** True when [header] (the [HEADER] value) or [protocols] (`Sec-WebSocket-Protocol`) carries this credential. */
    fun presentedBy(header: String?, protocols: String?): Boolean {
        val presented = header?.trim()?.takeIf { it.isNotEmpty() } ?: fromProtocols(protocols) ?: return false
        // Fixed-length digests compared in constant time, as the access token is.
        return MessageDigest.isEqual(digest(presented), expected)
    }

    companion object {
        const val FILE = "owner.token"
        const val HEADER = "X-AgentHQ-Owner"
        const val PROTOCOL_PREFIX = "agenthq.owner."
        private const val TOKEN_BYTES = 33 // 44 base64url characters, as remote.token

        fun file(dir: File): File = File(dir, FILE)

        /** The daemon's credential; refused when [ownerToken] is the access token, which every agent is handed. */
        fun forDaemon(ownerToken: String, accessToken: String?): OwnerCredential {
            require(ownerToken != accessToken) { "the owner token must differ from the access token every agent receives" }
            return OwnerCredential(ownerToken)
        }

        /** The stored owner token, generating and storing one first if there is none (or it is unreadable / short). */
        fun ensure(dir: File, random: SecureRandom = SecureRandom()): String {
            val f = file(dir)
            runCatching { f.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim() }.getOrNull()
                ?.takeIf { it.length >= MIN_TOKEN_LENGTH }?.let { return it }
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
            dir.mkdirs()
            f.writeText(token, Charsets.UTF_8)
            // Owner-only where the file system supports POSIX permissions; Windows files inherit the user profile ACL.
            runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
            return token
        }

        /** The stored owner token, or null when there is none; a client on this machine reads it to connect as the owner. */
        fun stored(dir: File): String? =
            runCatching { file(dir).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim() }.getOrNull()?.takeIf { it.length >= MIN_TOKEN_LENGTH }

        /** The subprotocol a browser offers to carry [token]. */
        fun protocol(token: String): String =
            PROTOCOL_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(token.toByteArray(Charsets.UTF_8))

        private fun fromProtocols(protocols: String?): String? {
            val encoded = protocols?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith(PROTOCOL_PREFIX) }
                ?.removePrefix(PROTOCOL_PREFIX) ?: return null
            return runCatching { String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
        }

        private fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    }
}
