// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * RUNNER-LINK-1 (docs/wiki/RunnerPairing-design.md, step 1): the ONE string `infantry runner pair` prints, carrying everything a
 * client needs to pair with this runner:
 *
 * `infantry://runner?v=1&name=<runner name>&host=<h1>&host=<h2>&port=<link port>&code=<XXXX-XXXX-XXXX-XXXX>&fp=<sha-256 hex>[&plain=1]`
 *
 * * `host` repeats, in the order the client tries them; an IPv6 address is in brackets.
 * * `fp` is the link certificate's SHA-256, lower-case hex without colons; required unless `plain=1` (`runners.allowPlainTailnet`).
 * * `v` is the format version: a link of a newer version is refused with a text that says to update.
 *
 * The prefix differs from PAIR-1's `infantry://connect?` on purpose (that one is a client-to-daemon link with a bearer token; this
 * one is a runner pairing link with a one-time code). Same rules: [toString] never prints the code, and parse errors are fixed
 * text that never echoes the input. JVM only: the UI never parses it, it sends the pasted text to the daemon.
 */
class RunnerPairLink(
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val code: String,
    /** Normalized ([normalizeFingerprint]); null only with [plain]. */
    val fingerprint: String?,
    val plain: Boolean = false,
) {
    /** The `infantry://runner?...` text (contains the one-time code: a secret until it is used or expires). */
    fun toUri(): String = buildString {
        append(PREFIX).append("v=").append(VERSION)
        append("&name=").append(encode(name))
        hosts.forEach { append("&host=").append(encode(bracketed(it))) }
        append("&port=").append(port)
        append("&code=").append(encode(code))
        fingerprint?.let { append("&fp=").append(encode(normalizeFingerprint(it))) }
        if (plain) append("&plain=1")
    }

    /** True when [other] is the same certificate fingerprint as this link's (case and colons ignored, as `LinkTls.normalized`). */
    fun fingerprintMatches(other: String?): Boolean =
        fingerprint != null && other != null && normalizeFingerprint(fingerprint) == normalizeFingerprint(other)

    override fun toString(): String =
        "RunnerPairLink(name=$name, hosts=$hosts, port=$port, code=redacted, fingerprint=${fingerprint ?: "none"}, plain=$plain)"

    override fun equals(other: Any?): Boolean = other is RunnerPairLink && name == other.name && hosts == other.hosts && port == other.port &&
        code == other.code && fingerprint == other.fingerprint && plain == other.plain

    override fun hashCode(): Int = listOf(name, hosts, port, code, fingerprint, plain).hashCode()

    sealed interface Parsed {
        class Ok(val link: RunnerPairLink) : Parsed

        /** [reason] is fixed text, never part of the input. */
        class Bad(val reason: String) : Parsed
    }

    companion object {
        const val PREFIX = "infantry://runner?"
        const val VERSION = 1
        const val NEWER = "This pairing link was made by a newer runner; update this app, then paste it again."

        private val CODE = Regex("""^[A-Za-z0-9]{4}(-[A-Za-z0-9]{4}){3}$""")
        private val HEX = Regex("""^[0-9a-f]{64}$""")
        private const val LAST_PORT = 65535

        fun parse(text: String): Parsed {
            val clean = text.trim().removeSurrounding("'").removeSurrounding("\"").trim()
            if (!clean.startsWith(PREFIX, ignoreCase = true)) return Parsed.Bad("Not an Infantry AI runner pairing link (it starts with $PREFIX).")
            val single = HashMap<String, String>()
            val hosts = ArrayList<String>()
            for (part in clean.substring(PREFIX.length).substringBefore('#').split('&')) {
                if (part.isEmpty()) continue
                val key = part.substringBefore('=').lowercase()
                val value = decode(part.substringAfter('=', ""))?.trim() ?: return Parsed.Bad("The runner pairing link is malformed.")
                if (key == "host") { if (value.isNotEmpty()) hosts += value } else if (key !in single) single[key] = value
            }
            val v = single["v"] ?: return Parsed.Bad("The runner pairing link has no version (v).")
            val version = v.toIntOrNull() ?: return Parsed.Bad("The runner pairing link has a bad version (v).")
            if (version > VERSION) return Parsed.Bad(NEWER)
            if (version < VERSION) return Parsed.Bad("The runner pairing link has an unknown version (v).")
            val name = single["name"].orEmpty()
            if (name.isEmpty()) return Parsed.Bad("The runner pairing link has no runner name.")
            if (!RunnerLinkStore.NAME.matches(name)) return Parsed.Bad("The runner name in the link must be 1-32 of a-z, 0-9 and '-'.")
            if (hosts.isEmpty()) return Parsed.Bad("The runner pairing link has no host.")
            if (hosts.any { !RunnerLinkSettings.HOST.matches(it) }) return Parsed.Bad("A host in the runner pairing link is not a host name or address.")
            val portText = single["port"].orEmpty()
            if (portText.isEmpty()) return Parsed.Bad("The runner pairing link has no port.")
            val port = portText.toIntOrNull()?.takeIf { it in 1..LAST_PORT } ?: return Parsed.Bad("The port in the runner pairing link is not a port number.")
            val code = single["code"].orEmpty()
            if (code.isEmpty()) return Parsed.Bad("The runner pairing link has no pairing code.")
            if (!CODE.matches(code)) return Parsed.Bad("The pairing code in the link is malformed.")
            val plain = single["plain"] == "1"
            val fp = single["fp"]?.let(::normalizeFingerprint)?.takeIf { it.isNotEmpty() }
            if (fp == null && !plain) return Parsed.Bad("The runner pairing link has no certificate fingerprint (fp).")
            if (fp != null && !HEX.matches(fp)) return Parsed.Bad("The certificate fingerprint in the link is not a SHA-256.")
            return Parsed.Ok(RunnerPairLink(name, hosts.map(::bracketed).distinct(), port, code.uppercase(), fp, plain))
        }

        /** Lower-case hex without colons or spaces: the `LinkTls.normalized` form, so a compare ignores case and colons. */
        fun normalizeFingerprint(fp: String): String = fp.filter { it.isLetterOrDigit() }.lowercase()

        /** An IPv6 address in brackets (`[::1]`); any other host as given. */
        fun bracketed(host: String): String = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

        /**
         * The link's host candidates, in order: every [flags] host (each may be a comma list), then [advertised]
         * (`runners.advertiseHosts`); only when both are empty, [detected] (see [detectHosts]). At most [max], duplicates dropped.
         */
        fun candidates(flags: List<String>, advertised: List<String>, max: Int, detected: () -> List<String> = ::detectHosts): List<String> {
            fun clean(list: List<String>) = list.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
            val given = clean(flags) + clean(advertised)
            val all = given.ifEmpty { detected() }
            return all.map(::bracketed).distinct().take(max.coerceAtLeast(1))
        }

        /**
         * The non-loopback addresses of the up interfaces, ordered: tailnet (100.64.0.0/10) first, then private (RFC 1918, IPv6
         * ULA), then the rest. Skips loopback, link-local, docker and veth interfaces.
         */
        fun detectHosts(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtualBridge() }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .let(::ordered)
        }.getOrDefault(emptyList())

        /** [addresses] without loopback / link-local / wildcard ones, as host text, tailnet first, then private, then the rest. */
        fun ordered(addresses: List<InetAddress>): List<String> = addresses
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress && !it.isMulticastAddress }
            .sortedBy { rank(it) }
            .map { a -> a.hostAddress.substringBefore('%').let { if (a is Inet6Address) "[$it]" else it } }

        private fun NetworkInterface.isVirtualBridge(): Boolean = name.lowercase().let { it.startsWith("docker") || it.startsWith("veth") || it.startsWith("br-") }

        private const val RANK_TAILNET = 0
        private const val RANK_PRIVATE = 1
        private const val RANK_OTHER = 2

        private fun rank(a: InetAddress): Int {
            val b = a.address.map { it.toInt() and 0xFF }
            return when {
                a is Inet4Address && b[0] == 100 && b[1] in 64..127 -> RANK_TAILNET
                a is Inet4Address && a.isSiteLocalAddress -> RANK_PRIVATE
                a is Inet6Address && (b[0] and 0xFE) == 0xFC -> RANK_PRIVATE
                else -> RANK_OTHER
            }
        }

        private const val HEXDIGITS = "0123456789ABCDEF"
        private const val SAFE = "-._~:"

        private fun encode(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (c < 128 && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in SAFE)) append(ch)
                else append('%').append(HEXDIGITS[c shr 4]).append(HEXDIGITS[c and 15])
            }
        }

        private fun decode(s: String): String? {
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    val hi = s.getOrNull(i + 1)?.digitToIntOrNull(16) ?: return null
                    val lo = s.getOrNull(i + 2)?.digitToIntOrNull(16) ?: return null
                    out.write((hi shl 4) or lo)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray())
                    i++
                }
            }
            return out.toString(Charsets.UTF_8)
        }
    }
}
