// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.downloads

import com.superiorsoftllc.infantry.plugin.api.ProgressHandle
import java.io.Closeable
import java.io.File
import java.io.InputStream

/**
 * CORE-PROGRESS-DOWNLOAD-1 (DaemonApi 1.54.0, docs/wiki/Progress.md): one file download through the core downloader.
 *
 * - [headers] are sent as given; [secretHeaders] (an `Authorization` token, read with `DaemonApi.secret`) are sent too but
 *   never logged, never put in an error, and dropped when a redirect leaves [url]'s host.
 * - [sha256] (hex, any case), when set, is checked before the file is moved into place; a mismatch fails with both hashes.
 * - [maxBytes] caps the size (0 = the setting `limits.downloads.maxMb`).
 * - The bytes go to a temp file beside [dest] (`<name>.part`) and are moved over [dest] atomically at the end.
 * - [progress]: report into this task (the caller's own, with its steps; the caller ends it). Null = the downloader starts a
 *   task titled [title] (blank = the file name) and ends it itself, with a Retry that downloads again.
 */
data class DownloadRequest(
    val url: String,
    val dest: File,
    val title: String = "",
    val headers: Map<String, String> = emptyMap(),
    val secretHeaders: Map<String, String> = emptyMap(),
    val sha256: String? = null,
    val maxBytes: Long = 0,
    val progress: ProgressHandle? = null,
    /** The id of the task the downloader starts (blank = assigned); ignored with [progress]. */
    val progressId: String = "",
    val link: String? = null,
)

/**
 * CORE-PROGRESS-DOWNLOAD-1: how a download ended. [ok] = [file] holds [bytes] bytes hashing to [sha256]. Otherwise
 * [error] is the clear reason (no secret in it) and [retryable] says a Retry may help (offline, a timeout, a 5xx, a hash
 * mismatch); [cancelled] = it was cancelled and its temp file removed. [attempts] counts the tries made.
 */
data class DownloadResult(
    val ok: Boolean,
    val file: File? = null,
    val bytes: Long = 0,
    val sha256: String? = null,
    val attempts: Int = 0,
    val error: String? = null,
    val retryable: Boolean = false,
    val cancelled: Boolean = false,
    /** The HTTP status of the last answer (0 = none, e.g. offline or a local file). */
    val status: Int = 0,
)

/**
 * CORE-PROGRESS-DOWNLOAD-1: one opened body. [status] is the HTTP status (200 whole, 206 partial from the asked offset;
 * a local file answers 200). [length] is this body's size (-1 = unknown). Close it to release the connection.
 */
class DownloadResponse(
    val status: Int,
    val length: Long,
    val body: InputStream,
    /** A `Retry-After` or similar hint in milliseconds (-1 = none). */
    val retryAfterMs: Long = -1,
) : Closeable {
    override fun close() = body.close()
}

/**
 * CORE-PROGRESS-DOWNLOAD-1: the replaceable backend of the downloader: it only opens bytes (a mirror, a cache, a CDN
 * plugin). Retries, resume, the size cap, the hash check, the temp file and the progress stay in core around it. The core
 * transport is HTTP(S) with the proxy settings, plus `file:` URLs and plain paths. Throw [java.io.IOException] for a
 * failure worth a retry.
 */
fun interface DownloadTransport {
    /** Opens [url] from byte [from] (0 = the whole body). [secretHeaders] must never be logged nor sent to another host. */
    fun open(url: String, headers: Map<String, String>, secretHeaders: Map<String, String>, from: Long): DownloadResponse
}

/**
 * CORE-PROGRESS-DOWNLOAD-1 (docs/wiki/Progress.md): the ONE download service. File downloads go here (the scripting
 * `http.fetch` stays for API calls); it reports through the core progress system on its own.
 */
interface Downloads {
    /** Downloads [request]; blocks the caller's thread until it ends. */
    fun download(request: DownloadRequest): DownloadResult

    /**
     * Registers [transport] as [transportId]'s (use the module name). It is used when the setting `downloads.transport`
     * names it; blank = the core transport. A transport that throws something other than an IOException is this module's
     * fault and the download falls back to the core transport. Close to remove.
     */
    fun transport(transportId: String, transport: DownloadTransport): AutoCloseable = AutoCloseable {}
}

/** CORE-PROGRESS-DOWNLOAD-1: the downloads of a daemon that has none (an older host, a test double): every call fails. */
object NoDownloads : Downloads {
    override fun download(request: DownloadRequest) = DownloadResult(ok = false, error = "this daemon has no download service")
}
