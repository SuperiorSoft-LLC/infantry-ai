package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.session.InfantryEnv

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
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
 * WEB-1 (1.122.0) makes two narrow exceptions: the browser bundle's files (no data, see [WebBundle]) and a SAME-ORIGIN
 * browser on `/ws` that also presents the bearer token, on an allowlisted Host. Same-origin never replaces the token.
 *
 * This is NOT transport security. The token and every frame cross the wire in cleartext, and the daemon
 * does not terminate TLS. See README, "Remote daemon".
 */
class AccessPolicy private constructor(
    val bindHost: String,
    private val tokenDigest: ByteArray?,
    /** WEB-1: lowercased host names a browser may reach this daemon by (see [browserHosts]). */
    private val browserHostNames: Set<String> = emptySet(),
    /** SANDBOX-1: the digest of the daemon's scoped agent token; null = no agent tier. See [AgentFrames]. */
    private val agentDigest: ByteArray? = null,
    /**
     * DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md section 4, `admission`): the digests of the GUEST tokens admitted NOW, read
     * per request (an official or core plugin's admission hook names the tokens; none = none). See [withGuestTokens].
     */
    private val guestDigests: () -> List<ByteArray> = { emptyList() },
) {
    /**
     * DAEMON-PLUGINS-1: also admit the guest tokens [current] names NOW (read per request, so a plugin opens or closes
     * admission with no restart). A guest can do only what the plugin's own gates allow it. A token that breaks
     * [guestDigestOf]'s rules is logged once and not admitted (a running daemon cannot refuse to start).
     */
    fun withGuestTokens(current: () -> List<String>): AccessPolicy {
        val cache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val refused = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        return AccessPolicy(bindHost, tokenDigest, browserHostNames, agentDigest) {
            current().mapNotNull { token ->
                cache[token] ?: if (token in refused) null else runCatching { guestDigestOf(token) }
                    .onFailure { refused += token; System.err.println("[infantry] guest token not admitted: ${it.message}") }
                    .getOrNull()?.also { cache[token] = it }
            }
        }
    }

    /**
     * A guest token's digest. Throws [AccessConfigurationException] for no owner token (a tokenless daemon), a short
     * token, or one equal to the owner or agent token; the daemon checks the tokens present at start with it.
     */
    fun guestDigestOf(token: String): ByteArray {
        val owner = tokenDigest ?: throw AccessConfigurationException("a guest token needs an owner token: set $TOKEN_FILE_ENV or $TOKEN_ENV")
        if (token.length < MIN_TOKEN_LENGTH || token.any { it.isWhitespace() || it.isISOControl() }) {
            throw AccessConfigurationException("a guest token must be at least $MIN_TOKEN_LENGTH characters, no whitespace")
        }
        val d = digest(token)
        if (MessageDigest.isEqual(d, owner) || (agentDigest?.let { MessageDigest.isEqual(d, it) } ?: false)) {
            throw AccessConfigurationException("a guest token must not be the owner token")
        }
        return d
    }

    /** True when a guest token is admitted now. */
    val hasGuestToken: Boolean get() = guestDigests().isNotEmpty()

    private fun guestMatches(d: ByteArray): Boolean = guestDigests().any { MessageDigest.isEqual(d, it) }

    /**
     * SANDBOX-1: the tier of a request [check] admitted: [AccessTier.AGENT] when it presented the scoped agent token, else
     * [AccessTier.OWNER] (the owner token, or a tokenless loopback daemon).
     */
    fun tierOf(authorization: String?, webSocketProtocols: String? = null): AccessTier {
        val agent = agentDigest ?: return AccessTier.OWNER
        val header = authorization?.trim().orEmpty()
        val presented = if (header.startsWith(BEARER_PREFIX, ignoreCase = true)) header.substring(BEARER_PREFIX.length).trim()
        else bearerFromProtocols(webSocketProtocols).orEmpty()
        if (presented.isEmpty()) return AccessTier.OWNER
        return if (MessageDigest.isEqual(digest(presented), agent)) AccessTier.AGENT else AccessTier.OWNER
    }

    /** WEB-1: the host names (no port) a browser page may name in its Host and Origin, lowercased and sorted. */
    val browserHosts: List<String> get() = browserHostNames.sorted()

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
    fun check(
        path: String,
        host: String?,
        origin: String?,
        authorization: String?,
        webSocketProtocols: String? = null,
        webAsset: Boolean = false,
        /** DAEMON-PLUGINS-1: an official plugin's declared public route (`daemon.publicRoutes`), checked like a bundle file. */
        publicRoute: Boolean = false,
    ): Refusal? {
        if (webAsset || publicRoute) return checkWebAsset(host, origin)
        if (origin != null && path == BROWSER_PATH) return checkBrowser(host, origin, authorization, webSocketProtocols)
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

    /**
     * WEB-1 (contract 1.122.0): a file of the browser bundle. It holds no data, so no token is asked; but a
     * loopback daemon keeps its Host check (DNS rebinding), a token daemon asks for an allowlisted Host, and a
     * request carrying an Origin must be same-origin.
     */
    private fun checkWebAsset(host: String?, origin: String?): Refusal? {
        if (tokenDigest == null && !hostNamesLoopback(host)) {
            return Refusal(HttpStatusCode.Forbidden, "the Host header must name the loopback address (localhost, 127.0.0.1 or [::1])")
        }
        if (tokenDigest != null && !browserHostAllowed(host)) return hostRefusal()
        if (origin != null && !sameOrigin(origin, host)) return crossOriginRefusal()
        return null
    }

    /**
     * WEB-1 (contract 1.122.0): a browser on the WebSocket. Same-origin admission NEVER replaces the token: the
     * daemon must have one (a tokenless loopback daemon refuses every browser), the Host must be allowlisted
     * (the Host header is attacker-controlled under DNS rebinding, so it is not trusted as it arrives), the Origin
     * must be that same allowlisted host, and the bearer must match -- from `Authorization` or, since a browser
     * WebSocket cannot set headers, from the `infantry.bearer.<base64url token>` subprotocol.
     */
    private fun checkBrowser(host: String?, origin: String, authorization: String?, protocols: String?): Refusal? {
        if (tokenDigest == null) {
            return Refusal(
                HttpStatusCode.Forbidden,
                "a browser can only connect to a daemon with a bearer token: set $TOKEN_FILE_ENV (or $TOKEN_ENV), or turn on remote access",
            )
        }
        if (!browserHostAllowed(host)) return hostRefusal()
        if (!sameOrigin(origin, host)) return crossOriginRefusal()
        val admitted = bearerMatches(authorization) ||
            (bearerFromProtocols(protocols)?.let { matchesToken(it) } ?: false)
        return if (admitted) null else Refusal(HttpStatusCode.Unauthorized, "a bearer token is required", challengeBearer = true)
    }

    private fun hostRefusal() = Refusal(
        HttpStatusCode.Forbidden,
        "this host name is not one the daemon answers browsers on; add it to $WEB_HOSTS_ENV",
    )

    private fun crossOriginRefusal() = Refusal(
        HttpStatusCode.Forbidden,
        "requests carrying a cross-site Origin header are refused: this daemon is not a browser API",
    )

    private fun browserHostAllowed(hostHeader: String?): Boolean =
        hostName(hostHeader)?.lowercase()?.let { it in browserHostNames } ?: false

    private fun matchesToken(presented: String): Boolean {
        val expected = tokenDigest ?: return false
        if (presented.isEmpty()) return false
        return MessageDigest.isEqual(digest(presented), expected) || (agentDigest?.let { MessageDigest.isEqual(digest(presented), it) } ?: false) ||
            guestMatches(digest(presented))
    }

    private fun bearerMatches(authorization: String?): Boolean {
        val expected = tokenDigest ?: return true
        val header = authorization?.trim().orEmpty()
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return false
        val presented = header.substring(BEARER_PREFIX.length).trim()
        if (presented.isEmpty()) return false
        // Fixed-length digests compared in constant time: neither the token's length nor how many of its
        // leading bytes match can be learned from how long the comparison takes. SANDBOX-1: the agent token is admitted
        // too; what it may do is decided by its tier ([tierOf], [AgentFrames]).
        val d = digest(presented)
        return MessageDigest.isEqual(d, expected) || (agentDigest?.let { MessageDigest.isEqual(d, it) } ?: false) ||
            guestMatches(d)
    }

    companion object {
        /** The variable a token is read from, and the one a spawned agent receives it in. */
        const val TOKEN_ENV = "INFANTRY_TOKEN"

        /** A file whose contents are the token. Preferred on Windows: the secret stays out of the environment. */
        const val TOKEN_FILE_ENV = "INFANTRY_TOKEN_FILE"

        /**
         * The daemon's agent-facing HTTP mail base (`MailHttp.kt`), handed to every spawned agent so a
         * running agent can INITIATE a handoff to a colleague instead of only answering a delivered one.
         * Same variable the daemon reads its own base from (`DaemonMain.mailBaseUrl`).
         */
        const val MAIL_BASE_ENV = "INFANTRY_MAIL_BASE"

        /**
         * The session id a spawned agent runs as. It is the `from` of any handoff the agent sends, so it
         * is how the mailbox (and the recipient) name the sender. Added per run by `SessionManager`.
         */
        const val SESSION_ID_ENV = "INFANTRY_SESSION_ID"

        /**
         * The project id a spawned agent belongs to, so the agent can address colleagues in that project
         * (e.g. `infantry agents --live` without `--project` rosters the project it was spawned for).
         * Added per run by `SessionManager` when the session has a project.
         */
        const val PROJECT_ID_ENV = "INFANTRY_PROJECT_ID"

        /**
         * BUG-808ce596: the agent profile a spawned agent runs as, when one is bound (a profile spawn, a
         * ticket start). Added per run by `SessionManager`; absent for a role-only or anonymous session.
         */
        const val AGENT_ID_ENV = "INFANTRY_AGENT_ID"

        /**
         * BUG-808ce596: the role a spawned agent runs as -- its profile's role, or the role a role-only spawn
         * (the New agent modal) named. Added per run by `SessionManager` when the session has one.
         */
        const val ROLE_ENV = "INFANTRY_ROLE"

        private const val BEARER_PREFIX = "Bearer "

        /** WEB-1: the one route a browser may call (besides the bundle files): the client WebSocket. */
        const val BROWSER_PATH = "/ws"

        /** WEB-1: the subprotocol the browser client offers, and the only one the daemon ever answers with. */
        const val WEB_PROTOCOL = "infantry"

        /** WEB-1: the subprotocol prefix that carries the bearer token, base64url (no padding) encoded. */
        const val BEARER_PROTOCOL_PREFIX = "infantry.bearer."

        /** WEB-1: extra host names (comma-separated) a browser may reach a token daemon by, e.g. a droplet's domain. */
        const val WEB_HOSTS_ENV = "INFANTRY_WEB_HOSTS"

        /** The token carried by a `Sec-WebSocket-Protocol` value, or null when none is offered or it does not decode. */
        fun bearerFromProtocols(protocols: String?): String? {
            val offered = protocols?.split(',')?.map { it.trim() } ?: return null
            val encoded = offered.firstOrNull { it.startsWith(BEARER_PROTOCOL_PREFIX) }?.removePrefix(BEARER_PROTOCOL_PREFIX)
                ?: return null
            return runCatching { String(java.util.Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
        }

        /** True when a `Sec-WebSocket-Protocol` value offers the plain [WEB_PROTOCOL] the daemon answers with. */
        fun offersWebProtocol(protocols: String?): Boolean =
            protocols?.split(',')?.any { it.trim() == WEB_PROTOCOL } ?: false

        /** The machine's own interface addresses, for a wildcard bind (a browser on the LAN names one of them). */
        fun localInterfaceAddresses(): Set<String> = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .map { it.hostAddress.substringBefore('%') }
                .toSet()
        }.getOrDefault(emptySet())

        /** The host names a browser may use: loopback, the bind host (or, bound to the wildcard, every local address), and [extra]. */
        fun browserHostNames(bindHost: String, extra: Collection<String>, localAddresses: () -> Set<String>): Set<String> {
            val bare = bindHost.trim().removePrefix("[").removeSuffix("]")
            val wildcard = bare.isEmpty() || bare == "0.0.0.0" || bare == "::"
            return buildSet {
                addAll(listOf("localhost", "127.0.0.1", "::1"))
                if (wildcard) addAll(localAddresses()) else add(bare)
                extra.map { it.trim().removePrefix("[").removeSuffix("]") }.filter { it.isNotEmpty() }.forEach { add(it) }
            }.map { it.lowercase() }.toSet()
        }

        /** The bare host name of a Host header value (no port, no IPv6 brackets), or null when it is blank. */
        private fun hostName(hostHeader: String?): String? {
            val value = hostHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                value.startsWith("[") -> value.substringAfter('[').substringBefore(']')
                value.count { it == ':' } > 1 -> value // a bare IPv6 literal carries no port
                else -> value.substringBefore(':')
            }
        }

        /**
         * Whether [origin] is exactly `http(s)://<host header>`: same host and port, compared without regard to case.
         * `null` (an opaque origin), a path, user info or any other scheme is not same-origin.
         */
        fun sameOrigin(origin: String, hostHeader: String?): Boolean {
            val host = hostHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val authority = when {
                origin.startsWith("http://", ignoreCase = true) -> origin.substring("http://".length)
                origin.startsWith("https://", ignoreCase = true) -> origin.substring("https://".length)
                else -> return false
            }
            if (authority.isEmpty() || authority.any { it == '/' || it == '@' || it == '?' || it == '#' || it.isWhitespace() }) return false
            return authority.equals(host, ignoreCase = true)
        }

        /**
         * P9.6 (Owner decision 2026-10-18): `POST /hooks/{id}` is admitted without the daemon bearer; the route
         * itself demands a valid per-hook HMAC signature. The match is deliberately STRICT -- exactly one
         * segment of letters, digits, `_` and `-` -- so no `..`, encoded slash, dot-segment or nested path can
         * borrow the exemption to reach another route. Every other route keeps the bearer requirement.
         */
        private val HOOK_PATH = Regex("/hooks/[A-Za-z0-9_-]{1,64}")

        fun isHookPath(path: String): Boolean = HOOK_PATH.matches(path)

        /** Validate a bind host and a token together, and build the policy. Throws [AccessConfigurationException]. */
        fun of(
            bindHost: String,
            token: String?,
            webHosts: Collection<String> = webHostsFromEnvironment(),
            /** SANDBOX-1: the scoped agent token (needs an owner token; a tokenless loopback daemon has no tiers). */
            agentToken: String? = null,
            localAddresses: () -> Set<String> = ::localInterfaceAddresses,
        ): AccessPolicy {
            if (agentToken != null && (agentToken == token || agentToken.length < MIN_TOKEN_LENGTH)) {
                throw AccessConfigurationException("the agent token must differ from the owner token and be at least $MIN_TOKEN_LENGTH characters")
            }
            if (token == null) {
                if (!isLoopbackHost(bindHost)) {
                    throw AccessConfigurationException(
                        "refusing to listen on '$bindHost' without a bearer token: anything that can reach that " +
                            "address could drive your agents. Set $TOKEN_FILE_ENV (or $TOKEN_ENV), or bind 127.0.0.1.",
                    )
                }
                return AccessPolicy(bindHost, null, browserHostNames(bindHost, webHosts, localAddresses))
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
            return AccessPolicy(bindHost, digest(token), browserHostNames(bindHost, webHosts, localAddresses), agentToken?.let(::digest))
        }

        /** WEB-1: [WEB_HOSTS_ENV], split on commas; empty when unset. */
        fun webHostsFromEnvironment(getenv: (String) -> String? = InfantryEnv::get): List<String> =
            getenv(WEB_HOSTS_ENV)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

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
            val name = hostName(hostHeader) ?: return false
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
/** SANDBOX-1: the [AccessTier] the access policy admitted a call with. */
val ACCESS_TIER = io.ktor.util.AttributeKey<AccessTier>("infantry.accessTier")

fun Application.installAccessPolicy(
    policy: AccessPolicy,
    isWebAsset: (String) -> Boolean = { false },
    /** DAEMON-PLUGINS-1: the plugins' HTTP gates (`httpGate`, fail closed): null = pass, else the 403 text. */
    httpGate: (method: String, path: String, webAsset: Boolean) -> String? = { _, _, _ -> null },
    /** DAEMON-PLUGINS-1: true for an official plugin's declared public route (answered without the bearer token). */
    publicRoute: (method: String, path: String) -> Boolean = { _, _ -> false },
) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        val method = call.request.httpMethod.value
        val protocols = call.request.headers.getAll(HttpHeaders.SecWebSocketProtocol)?.joinToString(",")
        val webAsset = call.request.httpMethod == io.ktor.http.HttpMethod.Get && isWebAsset(path)
        val refusal = policy.check(
            path = path,
            host = call.request.headers[HttpHeaders.Host],
            origin = call.request.headers[HttpHeaders.Origin],
            authorization = call.request.headers[HttpHeaders.Authorization],
            webSocketProtocols = protocols,
            webAsset = webAsset,
            publicRoute = publicRoute(method, path),
        )
        if (refusal == null) {
            // DAEMON-PLUGINS-1: a plugin's HTTP gate refuses for every token, the owner's included (a seated lockdown).
            httpGate(method, path, webAsset)?.let { reason ->
                call.respond(HttpStatusCode.Forbidden, reason)
                finish()
                return@intercept
            }
            // SANDBOX-1: the agent token reaches only the routes an agent needs; the WebSocket reads the tier attribute.
            val tier = policy.tierOf(call.request.headers[HttpHeaders.Authorization], protocols)
            if (tier == AccessTier.AGENT && !AgentFrames.allowsHttp(call.request.httpMethod.value, path)) {
                call.respond(HttpStatusCode.Forbidden, "sandbox: this route is not available to a sandboxed agent")
                finish()
                return@intercept
            }
            call.attributes.put(ACCESS_TIER, tier)
            // WEB-1: a browser that offered subprotocols fails the handshake unless one is selected. Answer with the
            // plain one only: the bearer subprotocol is never echoed.
            if (path == AccessPolicy.BROWSER_PATH && AccessPolicy.offersWebProtocol(protocols)) {
                call.response.header(HttpHeaders.SecWebSocketProtocol, AccessPolicy.WEB_PROTOCOL)
            }
            return@intercept
        }
        if (refusal.challengeBearer) call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"infantry\"")
        call.respond(refusal.status, refusal.reason)
        finish()
    }
}
