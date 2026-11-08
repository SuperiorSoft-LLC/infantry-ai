// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.downloads

import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadRequest
import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadResponse
import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadResult
import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadTransport
import com.superiorsoftllc.infantry.daemon.api.downloads.Downloads
import com.superiorsoftllc.infantry.plugin.api.Progress
import com.superiorsoftllc.infantry.plugin.api.ProgressHandle
import com.superiorsoftllc.infantry.plugin.api.ProgressSpec
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CORE-PROGRESS-DOWNLOAD-1 (docs/wiki/Progress.md): the core downloader, the one implementation of [Downloads].
 *
 * One download: open the body through the active [DownloadTransport] (the setting [transportSetting] names a plugin's,
 * blank = [coreTransport]); stream it into `<dest>.part`, reporting bytes to the progress task; on an IOException, a cut
 * connection, HTTP 429 or a 5xx, wait ([LimitRegistry.DOWNLOAD_BACKOFF_MS], doubling, a server's Retry-After wins) and try
 * again from the bytes already there (`Range`), up to [LimitRegistry.DOWNLOAD_RETRIES] times; check the size cap and the
 * sha256; then move the temp file over the target atomically, trying again while Windows holds the target locked. A
 * cancel (the task's Cancel, or [ProgressHandle] cancel) closes the connection and deletes the temp file.
 *
 * Secret headers never reach a log line or an error text; the URL in an error is cut before its query (which may carry a
 * token).
 */
class CoreDownloads(
    /** The progress system as module [source] sees it (a task the downloader starts carries that source). */
    private val progress: (source: String) -> Progress,
    private val limit: (LimitDef) -> Long = { it.default },
    private val transportSetting: () -> String = { "" },
    private val coreTransport: DownloadTransport = HttpDownloadTransport(limit),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /** A plugin transport threw something other than an IOException (its fault; the core transport is used instead). */
    private val onFault: (transportId: String, error: Throwable) -> Unit = { _, _ -> },
) {
    private val transports = CopyOnWriteArrayList<Pair<String, DownloadTransport>>()

    /** Module [source]'s view: its tasks carry its id, its transports are registered under it. */
    fun forSource(source: String): Downloads = object : Downloads {
        override fun download(request: DownloadRequest) = this@CoreDownloads.download(source, request)
        override fun transport(transportId: String, transport: DownloadTransport) = this@CoreDownloads.transport(transportId.ifBlank { source }, transport)
    }

    fun transport(transportId: String, transport: DownloadTransport): AutoCloseable {
        transports.removeIf { it.first == transportId }
        val entry = transportId to transport
        transports += entry
        return AutoCloseable { transports.remove(entry) }
    }

    /** The registered transport ids, in registration order. */
    fun transportIds(): List<String> = transports.map { it.first }

    fun download(source: String, request: DownloadRequest): DownloadResult {
        val own = request.progress == null
        val handle = request.progress ?: progress(source).start(
            ProgressSpec(
                title = request.title.ifBlank { "Downloading ${request.dest.name}" }, id = request.progressId,
                steps = listOf(STEP_DOWNLOAD, STEP_VERIFY), unit = UNIT_BYTES, cancellable = true, link = request.link,
                retry = { Thread({ download(source, request.copy(progress = null)) }, "download-retry").apply { isDaemon = true }.start() },
            ),
        )
        val result = runCatching { run(request, handle) }.getOrElse {
            DownloadResult(ok = false, error = "download of ${safe(request.url)} failed: ${it.message ?: it::class.simpleName}", retryable = true)
        }
        if (own) when {
            result.ok -> handle.done()
            result.cancelled -> handle.cancelled()
            else -> handle.fail(result.error ?: "download failed", result.retryable)
        }
        return result
    }

    // ---------------------------------------------------------------- one download

    private sealed interface Attempt {
        data object Done : Attempt
        data object Cancelled : Attempt
        data class Retry(val reason: String, val status: Int = 0, val retryAfterMs: Long = -1) : Attempt
        data class Fatal(val reason: String, val retryable: Boolean, val status: Int = 0) : Attempt
    }

    private fun run(request: DownloadRequest, h: ProgressHandle): DownloadResult {
        val capMb = limit(LimitRegistry.DOWNLOAD_MAX_MB)
        val max = request.maxBytes.takeIf { it > 0 } ?: if (capMb == 0L) Long.MAX_VALUE else capMb * MB
        val dest = request.dest.absoluteFile
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + PART_SUFFIX)
        val retries = limit(LimitRegistry.DOWNLOAD_RETRIES).toInt()
        var backoff = limit(LimitRegistry.DOWNLOAD_BACKOFF_MS)
        var attempts = 0
        h.step(STEP_DOWNLOAD)
        while (true) {
            if (h.cancelRequested) return cancelled(part, attempts)
            attempts++
            val outcome = attempt(request, part, max, h)
            when (outcome) {
                Attempt.Done -> break
                Attempt.Cancelled -> return cancelled(part, attempts)
                is Attempt.Fatal -> {
                    part.delete()
                    return DownloadResult(ok = false, attempts = attempts, error = outcome.reason, retryable = outcome.retryable, status = outcome.status)
                }
                is Attempt.Retry -> {
                    if (attempts > retries) {
                        return DownloadResult(ok = false, attempts = attempts, error = "${outcome.reason} (gave up after $attempts tries)", retryable = true, status = outcome.status)
                    }
                    h.step("$STEP_DOWNLOAD (retry $attempts of $retries: ${outcome.reason})")
                    if (!waitUnlessCancelled(maxOf(backoff, outcome.retryAfterMs), h)) return cancelled(part, attempts)
                    backoff = (backoff * 2).coerceAtMost(Long.MAX_VALUE / 4)
                }
            }
        }
        h.step(STEP_VERIFY)
        val sha = sha256(part)
        val want = request.sha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (want != null && sha != want) {
            part.delete()
            return DownloadResult(ok = false, attempts = attempts, error = "sha256 mismatch for ${dest.name}: expected $want, got $sha", retryable = true)
        }
        moveIntoPlace(part, dest, h)?.let { return DownloadResult(ok = false, attempts = attempts, error = it, retryable = true) }
        return DownloadResult(ok = true, file = dest, bytes = dest.length(), sha256 = sha, attempts = attempts, status = 200)
    }

    private fun attempt(request: DownloadRequest, part: File, max: Long, h: ProgressHandle): Attempt {
        val from = if (part.isFile) part.length() else 0L
        val response = try { open(request, from) } catch (io: IOException) {
            return if (h.cancelRequested) Attempt.Cancelled else Attempt.Retry(io.message ?: io::class.simpleName.orEmpty())
        }
        val stopOnCancel = h.onCancel { runCatching { response.close() } }
        // A body that sends nothing for limits.downloads.stallSeconds is closed, so the blocked read throws and is retried.
        val stallMs = limit(LimitRegistry.DOWNLOAD_STALL_SECONDS) * 1000L
        val lastByte = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
        val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val watch = if (stallMs <= 0) null else stallWatcher.scheduleWithFixedDelay({
            if (java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastByte.get()) >= stallMs && stalled.compareAndSet(false, true)) runCatching { response.close() }
        }, stallMs / STALL_CHECKS, stallMs / STALL_CHECKS, java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
            response.use { r ->
                val append = r.status == HTTP_PARTIAL && from > 0
                when {
                    r.status == HTTP_PARTIAL || r.status in HTTP_OK_RANGE -> {}
                    r.status == HTTP_RANGE_REFUSED && from > 0 -> { part.delete(); return Attempt.Retry("the server refused to resume (HTTP 416); starting over", r.status) }
                    r.status == HTTP_TOO_MANY || r.status >= HTTP_SERVER_ERROR -> return Attempt.Retry("HTTP ${r.status} from ${host(request.url)}", r.status, r.retryAfterMs)
                    r.status == HTTP_REQUEST_EXPIRED -> return Attempt.Retry("HTTP ${r.status} (request timeout)", r.status)
                    r.status == HTTP_NOT_FOUND -> return Attempt.Fatal("not found (HTTP 404): ${safe(request.url)}", retryable = false, status = r.status)
                    else -> return Attempt.Fatal("HTTP ${r.status} for ${safe(request.url)}", retryable = r.status == HTTP_UNAUTHORIZED || r.status == HTTP_FORBIDDEN, status = r.status)
                }
                var done = if (append) from else 0L
                val total = if (r.length >= 0) done + r.length else -1L
                if (total > max) return Attempt.Fatal("${request.dest.name} is $total bytes, over the $max byte limit", retryable = false)
                h.amount(done, total, UNIT_BYTES)
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        if (h.cancelRequested) return Attempt.Cancelled
                        val n = try { r.body.read(buf) } catch (io: IOException) {
                            return when {
                                h.cancelRequested -> Attempt.Cancelled
                                stalled.get() -> Attempt.Retry("no data for ${stallMs / 1000L} s at $done bytes")
                                else -> Attempt.Retry("the connection broke at $done bytes (${io.message ?: io::class.simpleName})")
                            }
                        }
                        if (stalled.get()) return if (h.cancelRequested) Attempt.Cancelled else Attempt.Retry("no data for ${stallMs / 1000L} s at $done bytes")
                        lastByte.set(System.nanoTime())
                        if (n < 0) break
                        done += n
                        if (done > max) return Attempt.Fatal("${request.dest.name} is over the $max byte limit", retryable = false)
                        out.write(buf, 0, n)
                        h.amount(done, total, UNIT_BYTES)
                    }
                }
                if (total >= 0 && done < total) return Attempt.Retry("the connection closed at $done of $total bytes")
                h.amount(done, if (total >= 0) total else done, UNIT_BYTES)
                return Attempt.Done
            }
        } finally {
            watch?.cancel(false)
            stopOnCancel.close()
        }
    }

    /** Opens through the chosen transport; a plugin transport's non-IO fault falls back to the core one. */
    private fun open(request: DownloadRequest, from: Long): DownloadResponse {
        val wanted = transportSetting().trim()
        val chosen = if (wanted.isEmpty()) null else transports.lastOrNull { it.first == wanted }
        if (chosen != null) {
            try {
                return chosen.second.open(request.url, request.headers, request.secretHeaders, from)
            } catch (io: IOException) {
                throw io
            } catch (e: Throwable) {
                onFault(chosen.first, e)
            }
        }
        return coreTransport.open(request.url, request.headers, request.secretHeaders, from)
    }

    private fun cancelled(part: File, attempts: Int): DownloadResult {
        part.delete()
        return DownloadResult(ok = false, attempts = attempts, error = "cancelled", cancelled = true)
    }

    /** Waits [ms]; false when a cancel came first. */
    private fun waitUnlessCancelled(ms: Long, h: ProgressHandle): Boolean {
        var left = ms
        while (left > 0) {
            if (h.cancelRequested) return false
            val slice = minOf(left, WAIT_SLICE)
            sleep(slice)
            left -= slice
        }
        return !h.cancelRequested
    }

    /** Moves [part] over [dest] (atomic where the disk allows); null = done, else why not (another program holds [dest]). */
    private fun moveIntoPlace(part: File, dest: File, h: ProgressHandle): String? {
        val tries = limit(LimitRegistry.DOWNLOAD_MOVE_RETRIES).toInt()
        var wait = limit(LimitRegistry.DOWNLOAD_BACKOFF_MS)
        var attempt = 0
        while (true) {
            try {
                try {
                    Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                return null
            } catch (e: FileSystemException) {
                if (++attempt > tries) return "${dest.path} is in use by another program (${e.reason ?: e::class.simpleName}); close it and Retry"
                if (!waitUnlessCancelled(wait, h)) return "cancelled"
                wait = (wait * 2).coerceAtMost(Long.MAX_VALUE / 4)
            }
        }
    }

    companion object {
        const val STEP_DOWNLOAD = "Downloading"
        const val STEP_VERIFY = "Verifying"
        const val UNIT_BYTES = "bytes"
        const val PART_SUFFIX = ".part"
        private const val MB = 1024L * 1024L
        private const val BUFFER = 64 * 1024
        private const val WAIT_SLICE = 100L
        private const val STALL_CHECKS = 4L
        /** One shared daemon thread checks every running download for a stall. */
        private val stallWatcher: java.util.concurrent.ScheduledExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "download-stall-watch").apply { isDaemon = true } }
        }
        private const val HTTP_PARTIAL = 206
        private val HTTP_OK_RANGE = 200..299
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_REQUEST_EXPIRED = 408
        private const val HTTP_RANGE_REFUSED = 416
        private const val HTTP_TOO_MANY = 429
        private const val HTTP_SERVER_ERROR = 500

        /** [url] with its query and credentials cut off (they may carry a token): safe for an error text or a log. */
        fun safe(url: String): String = url.substringBefore('?').substringBefore('#').replace(Regex("//[^/@]*@"), "//")

        fun host(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: "the server"

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(BUFFER)
                while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
