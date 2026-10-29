package com.superiorsoftllc.agenthq.daemon

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Ticket 9bf81983 (demo-build): at most [perClient] visitor turns per client IP in a rolling [windowMillis], so the demo
 * notice's "You have 10 turns" is true. A turn is a prompt / resume / mail_send a client sends that reaches an agent;
 * the daemon's own start-up warm-up never passes here. Every turn's time is kept in [file] (one `ip<TAB>epochMillis`
 * line each), so the count survives a restart. Both numbers come from the deployment's env ([fromEnvironment]).
 */
class DemoTurnLimit(
    val perClient: Int,
    val windowMillis: Long,
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    /** POLICY-1: the refusal code (the preset's `turnLimitCode`), declared a notice in `ready`. */
    val code: String = PREFIX,
) {
    private val turns = HashMap<String, ArrayDeque<Long>>()

    init {
        load()
    }

    /** Null = [client] may take another turn; else the refusal (prefixed [PREFIX], which the UI shows as the demo notice). */
    @Synchronized
    fun refusal(client: String): String? {
        val now = clock()
        val used = turns[client]?.apply { removeAll { now - it >= windowMillis } }?.size ?: 0
        return if (used >= perClient) "$code ${message()}" else null
    }

    /** One turn of [client] reached an agent. */
    @Synchronized
    fun record(client: String) {
        turns.getOrPut(client) { ArrayDeque() }.addLast(clock())
        save()
    }

    fun message(): String {
        val hours = windowMillis / HOUR_MILLIS
        val per = if (hours == 24L) "day" else "$hours hours"
        return "Demo limit reached: $perClient turns per visitor per $per"
    }

    private fun load() {
        val source = file?.takeIf { it.isFile } ?: return
        val now = clock()
        runCatching {
            source.readLines().forEach { line ->
                val ip = line.substringBefore('\t').trim()
                val at = line.substringAfter('\t', "").trim().toLongOrNull()
                if (ip.isNotEmpty() && at != null && now - at < windowMillis) turns.getOrPut(ip) { ArrayDeque() }.addLast(at)
            }
        }.onFailure { System.err.println("[agentHq] DEMO LOCK turn counts in $source unreadable, starting empty: ${it.message}") }
    }

    /** Written whole, then moved over the old file, so a crash mid-write never leaves half a file. */
    private fun save() {
        val target = file ?: return
        val now = clock()
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(buildString {
                turns.forEach { (ip, times) -> times.filter { now - it < windowMillis }.forEach { append(ip).append('\t').append(it).append('\n') } }
            })
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { System.err.println("[agentHq] DEMO LOCK could not save turn counts to $target: ${it.message}") }
    }

    companion object {
        const val PREFIX = "demo_limit"
        const val TURNS_ENV = "AGENTHQ_DEMO_TURNS_PER_IP"
        const val WINDOW_ENV = "AGENTHQ_DEMO_TURN_WINDOW_HOURS"
        /** Comma-separated peers whose `X-Forwarded-For` is believed (the host's reverse proxy). Default: loopback. */
        const val TRUSTED_PROXIES_ENV = "AGENTHQ_DEMO_TRUSTED_PROXIES"
        const val FILE_NAME = "demo-turns.tsv"
        private const val HOUR_MILLIS = 3_600_000L
        private val LOOPBACK = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")

        /** Null (no limit) unless both numbers are set and positive: the deployment's seed env supplies them. */
        fun fromEnvironment(dir: File, getenv: (String) -> String? = System::getenv): DemoTurnLimit? {
            val turns = getenv(TURNS_ENV)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val hours = getenv(WINDOW_ENV)?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            if (turns == null || hours == null) {
                System.err.println("[agentHq] DEMO LOCK: no turn limit ($TURNS_ENV and $WINDOW_ENV must both be positive)")
                return null
            }
            return DemoTurnLimit(turns, hours * HOUR_MILLIS, File(dir, FILE_NAME))
        }

        fun trustedProxies(getenv: (String) -> String? = System::getenv): Set<String> =
            getenv(TRUSTED_PROXIES_ENV)?.split(',')?.map { normalize(it) }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() }
                ?: LOOPBACK

        /**
         * The client a turn is charged to: the socket peer, except when the peer is a [trusted] proxy, whose
         * `X-Forwarded-For` is used. Its LAST entry is the address the proxy itself saw; entries before it came from the
         * client and are never believed. A header from any other peer is ignored.
         */
        fun clientIp(peer: String, forwardedFor: String?, trusted: Set<String>): String {
            val socket = normalize(peer)
            if (socket !in trusted) return socket
            return forwardedFor?.split(',')?.map { normalize(it) }?.lastOrNull { it.isNotEmpty() } ?: socket
        }

        private fun normalize(address: String): String = address.trim().removePrefix("[").removeSuffix("]").removePrefix("::ffff:")
    }
}
