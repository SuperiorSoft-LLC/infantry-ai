// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.cards.daemon

import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadRequest
import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadResult
import com.superiorsoftllc.infantry.plugin.api.ProgressHandle
import com.superiorsoftllc.infantry.plugin.api.ProgressState
import com.superiorsoftllc.infantry.plugin.api.ProgressTask
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * CARD-IMG-1 (docs/CARD-REGISTRY.md 2.5): the safe card image proxy. A card renderer never fetches a URL itself; it asks the
 * daemon (`card_image {type, url}`) and this class decides and fetches:
 *
 * - refused unless `cards.images.enabled` and the card [type] is one a loaded module declared;
 * - `https` only, port 443, no user info, a host NAME (no IP literal) that matches the card type's declared `images.hosts`
 *   or the owner's `cards.images.allow` (`host` exact, `*.example.com` any subdomain of it);
 * - every address the host resolves to must be public (no loopback, private, link-local, CGNAT, unique-local, multicast);
 *   each redirect hop is checked the same way (DownloadRequest.allowRedirect), so an allowed host cannot bounce the fetch to
 *   an internal or undeclared one;
 * - fetched through the core Downloads seam with no cookies, no referrer, no credentials, a fixed user agent, a byte cap;
 * - the bytes must sniff as png, jpeg, gif or webp (the server's content type is not trusted);
 * - cached in the plugin's state dir keyed by the URL's sha256, with a TTL and a total size cap (oldest first out).
 *
 * The answer is data for the card to draw, never an error dialog: a refusal or failure becomes the card's own badge.
 */
class CardImageProxy(
    private val cacheDir: File,
    private val setting: (String) -> String?,
    /** The image hosts card [type] declared, or null when no loaded module declares that type. */
    private val hostsOf: (type: String) -> List<String>?,
    private val download: (DownloadRequest) -> DownloadResult,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Outcome {
        class Image(val bytes: ByteArray, val mime: String) : Outcome
        /** The proxy will not fetch it (policy). */
        data class Refused(val reason: String) : Outcome
        /** It was allowed but could not be had (offline, 404, too big). */
        data class Failed(val reason: String) : Outcome
    }

    private val locks = ConcurrentHashMap<String, Any>()
    @Volatile private var gate: Pair<Int, Semaphore>? = null

    private fun int(key: String, default: Int, min: Int, max: Int) = setting(key)?.trim()?.toDoubleOrNull()?.toInt()?.coerceIn(min, max) ?: default

    private fun maxBytes() = int(IMAGE_BYTES_KEY, DEFAULT_IMAGE_BYTES, MIN_IMAGE_BYTES, MAX_IMAGE_BYTES)
    private fun ttlMs() = int(TTL_HOURS_KEY, DEFAULT_TTL_HOURS, 0, MAX_TTL_HOURS) * HOUR_MS
    private fun cacheCap() = int(CACHE_MB_KEY, DEFAULT_CACHE_MB, 0, MAX_CACHE_MB).toLong() * MB

    private fun semaphore(): Semaphore {
        val n = int(FETCHES_KEY, DEFAULT_FETCHES, 1, MAX_FETCHES)
        gate?.takeIf { it.first == n }?.let { return it.second }
        return synchronized(this) { gate?.takeIf { it.first == n }?.second ?: Semaphore(n).also { gate = n to it } }
    }

    fun enabled(): Boolean = setting(ENABLED_KEY)?.trim()?.lowercase()?.let { it != "false" && it != "0" && it != "off" } ?: true

    /** The owner's extra hosts (`cards.images.allow`: comma, space or newline separated). */
    fun ownerHosts(): List<String> = setting(ALLOW_KEY).orEmpty().split(',', ' ', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }

    fun fetch(type: String?, url: String?): Outcome {
        if (!enabled()) return Outcome.Refused("card images are off (cards.images.enabled)")
        val t = type?.trim()?.removePrefix("card:").orEmpty()
        if (!CardsDaemonPlugin.TYPE.matches(t)) return Outcome.Refused("type is required")
        val declared = hostsOf(t) ?: return Outcome.Refused("no loaded plugin declares card type '${t.take(40)}'")
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return Outcome.Refused("url is required")
        val allowed = declared + ownerHosts()
        check(u, allowed)?.let { return Outcome.Refused(it) }
        val key = sha256(u.toByteArray(Charsets.UTF_8))
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            try {
                cached(key)?.let { return it }
                val permits = semaphore()
                if (!permits.tryAcquire(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS)) return Outcome.Failed("too many card images loading; try again")
                try {
                    return fetchNow(u, key, allowed)
                } finally {
                    permits.release()
                }
            } finally {
                locks.remove(key)
            }
        }
    }

    /** Null when [url] may be fetched for a card whose image hosts are [allowed]; else the reason it may not. */
    fun check(url: String, allowed: List<String>): String? {
        if (url.length > MAX_URL_CHARS) return "url is too long"
        val uri = runCatching { URI(url) }.getOrNull() ?: return "not a valid url"
        if (!uri.scheme.equals("https", ignoreCase = true)) return "only https images are fetched"
        if (uri.rawUserInfo != null) return "a url with credentials is refused"
        if (uri.port != -1 && uri.port != HTTPS_PORT) return "only the standard https port is fetched"
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return "the url names no host"
        if (isIpLiteral(host)) return "an image host must be a name, not an IP address"
        if (allowed.none { hostMatches(host, it) }) return "host $host is not an image host of this card type (cards.images.allow adds one)"
        val addresses = runCatching { resolve(host) }.getOrElse { return "host $host could not be resolved" }
        if (addresses.isEmpty()) return "host $host could not be resolved"
        addresses.firstOrNull { !isPublic(it) }?.let { return "host $host resolves to a non-public address" }
        return null
    }

    private fun fetchNow(url: String, key: String, allowed: List<String>): Outcome {
        val tmpDir = File(cacheDir, TMP_DIR).apply { mkdirs() }
        val dest = File(tmpDir, "$key.dl")
        val cap = maxBytes()
        val result = runCatching {
            download(
                DownloadRequest(
                    url = url, dest = dest, title = "card image",
                    // No Cookie, Referer or Authorization: the core transport sends only these.
                    headers = mapOf("User-Agent" to USER_AGENT, "Accept" to ACCEPT),
                    maxBytes = cap.toLong(),
                    progress = SilentProgress(key),
                    allowRedirect = { target -> check(target, allowed) == null },
                ),
            )
        }.getOrElse { DownloadResult(ok = false, error = it.message ?: it::class.simpleName) }
        if (!result.ok) {
            dest.delete()
            val why = result.error.orEmpty()
            return when {
                "redirect refused" in why -> Outcome.Refused("the image host redirected to a host that is not allowed")
                result.status in 400..599 -> Outcome.Failed("the image host answered HTTP ${result.status}")
                "byte limit" in why ->
                    Outcome.Failed("the image is larger than ${cap / 1024} KB")
                else -> Outcome.Failed("the image could not be fetched")
            }
        }
        val file = result.file ?: dest
        if (file.length() > cap) { file.delete(); return Outcome.Failed("the image is larger than ${cap / 1024} KB") }
        val bytes = file.readBytes()
        val kind = sniff(bytes)
        if (kind == null) { file.delete(); return Outcome.Refused("not an image (png, jpeg, gif or webp only)") }
        val target = File(cacheDir, "$key.${kind.ext}")
        if (cacheCap() > 0 && ttlMs() > 0) {
            runCatching { java.nio.file.Files.move(file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                .onFailure { file.delete() }
            evict()
        } else {
            file.delete()
        }
        return Outcome.Image(bytes, kind.mime)
    }

    private fun cached(key: String): Outcome? {
        val ttl = ttlMs()
        for (k in Kind.entries) {
            val f = File(cacheDir, "$key.${k.ext}")
            if (!f.isFile) continue
            if (ttl <= 0 || now() - f.lastModified() > ttl) { f.delete(); return null }
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
            return sniff(bytes)?.let { Outcome.Image(bytes, it.mime) } ?: run { f.delete(); null }
        }
        return null
    }

    /** Drops expired entries, then the oldest until the cache fits `limits.cards.imageCacheMb`. */
    fun evict() {
        val ttl = ttlMs()
        val cap = cacheCap()
        val files = cacheDir.listFiles { f -> f.isFile && Kind.entries.any { f.name.endsWith("." + it.ext) } }.orEmpty()
            .sortedBy { it.lastModified() }.toMutableList()
        files.removeAll { f -> (now() - f.lastModified() > ttl).also { if (it) f.delete() } }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= cap) break
            total -= f.length()
            f.delete()
        }
    }

    /** CARD-IMG-1: what the proxy serves, by its leading bytes. */
    enum class Kind(val ext: String, val mime: String) { PNG("png", "image/png"), JPEG("jpg", "image/jpeg"), GIF("gif", "image/gif"), WEBP("webp", "image/webp") }

    /** A downloader progress handle that shows nothing: a thumbnail is not a task the owner watches. */
    private class SilentProgress(override val id: String) : ProgressHandle {
        @Volatile private var state = ProgressState.RUNNING
        override val task: ProgressTask get() = ProgressTask(id = id, title = "card image", state = state)
        override val cancelRequested: Boolean get() = false
        override fun step(label: String, index: Int) {}
        override fun amount(done: Long, total: Long, unit: String) {}
        override fun eta(ms: Long) {}
        override fun done(step: String) { state = ProgressState.DONE }
        override fun fail(reason: String, retryable: Boolean) { state = ProgressState.FAILED }
        override fun cancelled() { state = ProgressState.CANCELLED }
        override fun onCancel(listener: () -> Unit): AutoCloseable = AutoCloseable {}
    }

    companion object {
        const val FRAME = "card_image"
        const val ENABLED_KEY = "cards.images.enabled"
        const val ALLOW_KEY = "cards.images.allow"
        const val IMAGE_BYTES_KEY = "limits.cards.imageBytes"
        const val CACHE_MB_KEY = "limits.cards.imageCacheMb"
        const val TTL_HOURS_KEY = "limits.cards.imageTtlHours"
        const val FETCHES_KEY = "limits.cards.imageFetches"
        const val DEFAULT_IMAGE_BYTES = 2 * 1024 * 1024
        private const val MIN_IMAGE_BYTES = 1024
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val DEFAULT_CACHE_MB = 64
        private const val MAX_CACHE_MB = 4096
        const val DEFAULT_TTL_HOURS = 24
        private const val MAX_TTL_HOURS = 24 * 30
        const val DEFAULT_FETCHES = 4
        private const val MAX_FETCHES = 32
        private const val QUEUE_WAIT_MS = 30_000L
        private const val MAX_URL_CHARS = 2048
        private const val HTTPS_PORT = 443
        private const val HOUR_MS = 3_600_000
        private const val MB = 1024L * 1024L
        private const val TMP_DIR = "tmp"
        const val USER_AGENT = "InfantryAI-CardImage/1"
        private const val ACCEPT = "image/png,image/jpeg,image/gif,image/webp"

        /** `example.com` matches only itself; `*.example.com` matches any subdomain (not the bare name). A pattern needs two labels. */
        fun hostMatches(host: String, pattern: String): Boolean {
            val p = pattern.trim().lowercase().trimEnd('.')
            // CARD-IMG-2: a bare `*` is any named public host (https, no IP literal, public address, size and image checks still apply).
            if (p == "*") return host.isNotEmpty()
            if (p.startsWith("*.")) {
                val base = p.removePrefix("*.")
                if (base.count { it == '.' } < 1 || '*' in base) return false
                return host.endsWith(".$base") && host.length > base.length + 1
            }
            return p.isNotEmpty() && '*' !in p && host == p
        }

        fun isIpLiteral(host: String): Boolean =
            host.startsWith("[") || ':' in host || Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) || Regex("""^(0x[0-9a-f]+|\d+)$""").matches(host)

        /** True for an address a public image host may have; false for loopback, private, link-local, CGNAT, ULA, multicast. */
        fun isPublic(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
            val b = a.address
            return when (a) {
                is Inet4Address -> {
                    val o0 = b[0].toInt() and 0xff
                    val o1 = b[1].toInt() and 0xff
                    !(o0 == 0 || (o0 == 100 && o1 in 64..127) || (o0 == 192 && o1 == 0 && (b[2].toInt() and 0xff) == 0) || o0 >= 240)
                }
                is Inet6Address -> {
                    val o0 = b[0].toInt() and 0xff
                    // fc00::/7 unique local; an IPv4-mapped address is judged as its IPv4 address.
                    if ((o0 and 0xfe) == 0xfc) false
                    else if (b.copyOfRange(0, 10).all { it.toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff)
                        isPublic(InetAddress.getByAddress(b.copyOfRange(12, 16)))
                    else true
                }
                else -> false
            }
        }

        fun sniff(b: ByteArray): Kind? = when {
            b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> Kind.PNG
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> Kind.JPEG
            b.size >= 6 && String(b, 0, 6, Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" } -> Kind.GIF
            b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> Kind.WEBP
            else -> null
        }

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
