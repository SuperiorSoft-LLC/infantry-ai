package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.session.InfantryEnv

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest

/** Shorter than this and the token is a password a machine on the network can guess. */
const val MIN_TOKEN_LENGTH = 32

/** The one route answered without a token: liveness and discovery. It exposes counts, by design. */
const val OPEN_PATH = "/health"

/** Thrown when a configuration would expose the daemon unsafely. The message says what to change. */
class AccessConfigurationException(message: String) : IllegalStateException(message)

/** One refused request: the status to answer with and a reason that never contains the token. */
data class Refusal(val status: HttpStatusCode, val reason: String, val challengeBearer: Boolean = false)

/**
 * Who may talk to this daemon, decided once at startup from the bind host and the token.
 *
 * The configuration is chosen by what is set, never by the caller:
 *  - **token** (a token is configured, on any bind address): every route except [OPEN_PATH] requires
 *    `Authorization: Bearer <token>`. A NON-LOOPBACK bind MUST be in this mode; [of] refuses anything else.
 *  - **loopback** (no token, loopback bind): only processes on this machine can reach the socket, so no
 *    credential is required. The Host header must still name the loopback address, because a web page
 *    can make its own hostname resolve to 127.0.0.1 and then talk to this socket (DNS rebinding).
 *
 * In BOTH configurations a request carrying an Origin header is refused. Browsers attach Origin to
 * cross-site requests; CLIs and agents never send it, so this closes cross-site WebSocket hijacking.
 *
 * This is NOT transport security. The token and every frame cross the wire in cleartext, and the daemon
 * does not terminate TLS. See README, "Remote daemon".
 */
class AccessPolicy private constructor(
    val bindHost: String,
    private val tokenDigest: ByteArray?,
) {
    /** True when a bearer token is required on every route except [OPEN_PATH]. */
    val tokenRequired: Boolean get() = tokenDigest != null

    /** True when the bind host is loopback, so only this machine can reach the socket at all. */
    val loopbackBind: Boolean = isLoopbackHost(bindHost)

    /**
     * Decide one request: null means it may proceed; otherwise, the refusal to send.
     *
     * The order is fixed: Origin first (every configuration); then, with no token, the Host header; then,
     * with a token, the bearer credential (exempting [OPEN_PATH]).
     */
    fun check(path: String, host: String?, origin: String?, authorization: String?): Refusal? {
        if (origin != null) {
            return Refusal(
                HttpStatusCode.Forbidden,
                "requests carrying an Origin header are refused: this daemon is not a browser API",
            )
        }
        if (tokenDigest == null) {
            return if (hostNamesLoopback(host)) {
                null
            } else {
                Refusal(
                    HttpStatusCode.Forbidden,
                    "the Host header must name the loopback address (localhost, 127.0.0.1 or [::1])",
                )
            }
        }
        if (path == OPEN_PATH || isHookPath(path)) return null
        return if (bearerMatches(authorization)) {
            null
        } else {
            Refusal(HttpStatusCode.Unauthorized, "a bearer token is required", challengeBearer = true)
        }
    }

    /** One line for the startup banner. It says what admits a caller, never what the token is. */
    fun describe(): String = if (tokenRequired) {
        "bearer token required on every route except $OPEN_PATH and /hooks/{id} (signature-admitted)"
    } else {
        "loopback only, no token: only processes on this machine can connect"
    }

    private fun bearerMatches(authorization: String?): Boolean {
        val expected = tokenDigest ?: return true
        val header = authorization?.trim().orEmpty()
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return false
        val presented = header.substring(BEARER_PREFIX.length).trim()
        if (presented.isEmpty()) return false
        // Fixed-length digests compared in constant time: neither the token's length nor how many of its
        // leading bytes match can be learned from how long the comparison takes.
        return MessageDigest.isEqual(digest(presented), expected)
    }

    companion object {
        /** The variable a token is read from, and the one a spawned agent receives it in. */
        const val TOKEN_ENV = "AGENTHQ_TOKEN"

        /** A file whose contents are the token. Preferred on Windows: the secret stays out of the environment. */
        const val TOKEN_FILE_ENV = "AGENTHQ_TOKEN_FILE"

        /**
         * The daemon's agent-facing HTTP mail base (`MailHttp.kt`), handed to every spawned agent so a
         * running agent can INITIATE a handoff to a colleague instead of only answering a delivered one.
         * Same variable the daemon reads its own base from (`DaemonMain.mailBaseUrl`).
         */
        const val MAIL_BASE_ENV = "AGENTHQ_MAIL_BASE"

        /**
         * The session id a spawned agent runs as. It is the `from` of any handoff the agent sends, so it
         * is how the mailbox (and the recipient) name the sender. Added per run by `SessionManager`.
         */
        const val SESSION_ID_ENV = "AGENTHQ_SESSION_ID"

        /**
         * The project id a spawned agent belongs to, so the agent can address colleagues in that project
         * (e.g. `agenthq agents --live` without `--project` rosters the project it was spawned for).
         * Added per run by `SessionManager` when the session has a project.
         */
        const val PROJECT_ID_ENV = "AGENTHQ_PROJECT_ID"

        /**
         * BUG-808ce596: the agent profile a spawned agent runs as, when one is bound (a profile spawn, a
         * ticket start). Added per run by `SessionManager`; absent for a role-only or anonymous session.
         */
        const val AGENT_ID_ENV = "AGENTHQ_AGENT_ID"

        /**
         * BUG-808ce596: the role a spawned agent runs as -- its profile's role, or the role a role-only spawn
         * (the New agent modal) named. Added per run by `SessionManager` when the session has one.
         */
        const val ROLE_ENV = "AGENTHQ_ROLE"

        private const val BEARER_PREFIX = "Bearer "

        /**
         * P9.6 (Owner decision 2026-10-18): `POST /hooks/{id}` is admitted without the daemon bearer; the route
         * itself demands a valid per-hook HMAC signature. The match is deliberately STRICT -- exactly one
         * segment of letters, digits, `_` and `-` -- so no `..`, encoded slash, dot-segment or nested path can
         * borrow the exemption to reach another route. Every other route keeps the bearer requirement.
         */
        private val HOOK_PATH = Regex("/hooks/[A-Za-z0-9_-]{1,64}")

        fun isHookPath(path: String): Boolean = HOOK_PATH.matches(path)

        /** Validate a bind host and a token together, and build the policy. Throws [AccessConfigurationException]. */
        fun of(bindHost: String, token: String?): AccessPolicy {
            if (token == null) {
                if (!isLoopbackHost(bindHost)) {
                    throw AccessConfigurationException(
                        "refusing to listen on '$bindHost' without a bearer token: anything that can reach that " +
                            "address could drive your agents. Set $TOKEN_FILE_ENV (or $TOKEN_ENV), or bind 127.0.0.1.",
                    )
                }
                return AccessPolicy(bindHost, null)
            }
            if (token.length < MIN_TOKEN_LENGTH) {
                throw AccessConfigurationException(
                    "the bearer token must be at least $MIN_TOKEN_LENGTH characters; see README, \"Remote daemon\"",
                )
            }
            if (token.any { it.isWhitespace() || it.isISOControl() }) {
                throw AccessConfigurationException(
                    "the bearer token must not contain whitespace or control characters",
                )
            }
            return AccessPolicy(bindHost, digest(token))
        }

        /**
         * The token from the environment: [TOKEN_FILE_ENV] (its trimmed contents), else [TOKEN_ENV].
         * Both at once is a configuration error, because which one a reader should trust is unclear.
         */
        fun tokenFromEnvironment(getenv: (String) -> String? = InfantryEnv::get): String? {
            val file = getenv(TOKEN_FILE_ENV)?.takeIf { it.isNotBlank() }
            val direct = getenv(TOKEN_ENV)?.takeIf { it.isNotBlank() }
            return when {
                file != null && direct != null ->
                    throw AccessConfigurationException("set $TOKEN_FILE_ENV or $TOKEN_ENV, not both")
                file != null -> readTokenFile(File(file))
                else -> direct?.trim()
            }
        }

        /**
         * Read a token file: UTF-8, with an optional byte-order mark (Windows PowerShell writes one), trimmed.
         * Errors name the file and never its contents.
         */
        fun readTokenFile(file: File): String {
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (e: IOException) {
                throw AccessConfigurationException("cannot read the token file ${file.path} (${e.javaClass.simpleName})")
            }
            return text.removePrefix(Char(0xFEFF).toString()).trim().ifEmpty {
                throw AccessConfigurationException("the token file ${file.path} is empty")
            }
        }

        /**
         * Whether a host is loopback. A BLANK host is not: most network stacks bind the wildcard address for
         * an empty host, so treating it as loopback would expose the daemon while claiming it is local.
         */
        fun isLoopbackHost(host: String): Boolean {
            val bare = host.trim().removePrefix("[").removeSuffix("]")
            if (bare.isEmpty()) return false
            if (bare.equals("localhost", ignoreCase = true)) return true
            return runCatching { InetAddress.getByName(bare).isLoopbackAddress }.getOrDefault(false)
        }

        /** The address an agent on this machine should dial for a daemon bound to [bindHost]. */
        fun agentReachableHost(bindHost: String): String {
            val bare = bindHost.trim().removePrefix("[").removeSuffix("]")
            return when {
                bare.isEmpty() || bare == "0.0.0.0" || bare == "::" -> "127.0.0.1"
                bare.contains(':') -> "[$bare]"
                else -> bare
            }
        }

        private fun hostNamesLoopback(hostHeader: String?): Boolean {
            val value = hostHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val name = when {
                value.startsWith("[") -> value.substringAfter('[').substringBefore(']')
                value.count { it == ':' } > 1 -> value // a bare IPv6 literal carries no port
                else -> value.substringBefore(':')
            }
            return name.equals("localhost", ignoreCase = true) || name == "127.0.0.1" || name == "::1"
        }

        private fun digest(token: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Install [policy] in front of every route. A refused request is answered here and the pipeline is
 * finished, so no handler runs: a refused `POST /mail/send` must never reach the mailbox.
 */
fun Application.installAccessPolicy(policy: AccessPolicy) {
    intercept(ApplicationCallPipeline.Plugins) {
        val refusal = policy.check(
            path = call.request.path(),
            host = call.request.headers[HttpHeaders.Host],
            origin = call.request.headers[HttpHeaders.Origin],
            authorization = call.request.headers[HttpHeaders.Authorization],
        ) ?: return@intercept
        if (refusal.challengeBearer) call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"agenthq\"")
        call.respond(refusal.status, refusal.reason)
        finish()
    }
}
