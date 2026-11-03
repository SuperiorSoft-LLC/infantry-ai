package com.superiorsoftllc.infantry.ui.pair

/**
 * PAIR-1: what a QR code (or a pasted link) carries so one client can connect to the server another is connected to:
 * `infantry://connect?url=<ws(s)://host:port/ws>&token=<bearer token>&name=<server name>`.
 * The token is a credential: [toString] never prints it, and no error message echoes the input.
 */
class PairingLink(val url: String, val token: String?, val name: String? = null) {
    /** The `infantry://connect?...` text (contains the token: show it only behind an explicit action). */
    fun toUri(): String = buildString {
        append(PREFIX).append("url=").append(encode(url))
        token?.let { append("&token=").append(encode(it)) }
        name?.let { append("&name=").append(encode(it)) }
    }

    override fun toString(): String = "PairingLink(url=$url, token=${if (token == null) "none" else "set"}, name=$name)"
    override fun equals(other: Any?): Boolean = other is PairingLink && url == other.url && token == other.token && name == other.name
    override fun hashCode(): Int = listOf(url, token, name).hashCode()

    sealed interface Parsed {
        class Ok(val link: PairingLink) : Parsed

        /** [reason] is fixed text, never part of the input. */
        class Bad(val reason: String) : Parsed
    }

    sealed interface Result {
        class Ok(val link: PairingLink) : Result
        class Refused(val reason: String) : Result
    }

    companion object {
        const val PREFIX = "infantry://connect?"

        fun parse(text: String): Parsed {
            val clean = text.trim()
            if (!clean.startsWith(PREFIX, ignoreCase = true)) return Parsed.Bad("Not an Infantry AI pairing link.")
            val params = HashMap<String, String>()
            for (part in clean.substring(PREFIX.length).substringBefore('#').split('&')) {
                if (part.isEmpty()) continue
                val key = part.substringBefore('=')
                val value = decode(part.substringAfter('=', "")) ?: return Parsed.Bad("The pairing link is malformed.")
                if (key !in params) params[key] = value
            }
            val url = params["url"]?.trim().orEmpty()
            validateUrl(url)?.let { return Parsed.Bad(it) }
            return Parsed.Ok(
                PairingLink(url, params["token"]?.trim()?.takeIf { it.isNotEmpty() }, params["name"]?.trim()?.takeIf { it.isNotEmpty() }),
            )
        }

        /** Null when [url] is a usable `ws://` or `wss://` address, else a fixed reason. */
        fun validateUrl(url: String): String? {
            val rest = when {
                url.startsWith("ws://", ignoreCase = true) -> url.substring(5)
                url.startsWith("wss://", ignoreCase = true) -> url.substring(6)
                else -> return "The server address must start with ws:// or wss://."
            }
            if (rest.any { it.isWhitespace() }) return "The server address has spaces in it."
            return if (hostOf(url).isNullOrEmpty()) "The server address has no host." else null
        }

        /** The host of a ws(s) [url] without brackets (`::1`, not `[::1]`), or null. */
        fun hostOf(url: String): String? {
            val rest = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringAfterLast('@')
            if (rest.isEmpty()) return null
            return if (rest.startsWith("[")) {
                if (rest.contains(']')) rest.substringAfter('[').substringBefore(']') else null
            } else {
                rest.substringBefore(':')
            }
        }

        /** `localhost`, 127.x.x.x, ::1 and the wildcard addresses: places no other device can reach. */
        fun isLoopbackHost(host: String): Boolean {
            val h = host.lowercase()
            return h == "localhost" || h.endsWith(".localhost") || h == "::1" || h == "::" || h == "0.0.0.0" ||
                h == "0:0:0:0:0:0:0:1" || h.startsWith("127.") || h == "::ffff:127.0.0.1"
        }

        fun isLoopbackUrl(url: String): Boolean = hostOf(url)?.let(::isLoopbackHost) ?: true

        /** The link to SHOW: refuses a loopback address (no scanning device could reach it) and a missing token. */
        fun forShow(url: String, token: String?, name: String?): Result {
            val u = url.trim()
            validateUrl(u)?.let { return Result.Refused(it) }
            if (isLoopbackUrl(u)) {
                return Result.Refused("127.0.0.1 and localhost only work on this machine. Use an address other devices can reach.")
            }
            if (token.isNullOrBlank()) return Result.Refused("This connection has no token to share.")
            return Result.Ok(PairingLink(u, token.trim(), name?.trim()?.takeIf { it.isNotEmpty() }))
        }

        private const val HEX = "0123456789ABCDEF"
        private const val SAFE = "-._~"

        private fun encode(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (c < 128 && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in SAFE)) {
                    append(ch)
                } else {
                    append('%').append(HEX[c shr 4]).append(HEX[c and 15])
                }
            }
        }

        private fun decode(s: String): String? {
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    val hi = s.getOrNull(i + 1)?.digitToIntOrNull(16) ?: return null
                    val lo = s.getOrNull(i + 2)?.digitToIntOrNull(16) ?: return null
                    out.add(((hi shl 4) or lo).toByte())
                    i += 3
                } else {
                    // A surrogate pair is two chars: take both so the UTF-8 bytes are the character's.
                    val n = if (c.isHighSurrogate() && i + 1 < s.length) 2 else 1
                    out.addAll(s.substring(i, i + n).encodeToByteArray().toList())
                    i += n
                }
            }
            return out.toByteArray().decodeToString()
        }
    }
}
