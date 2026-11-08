// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.downloads

import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadRequest
import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadResponse
import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadTransport
import com.superiorsoftllc.infantry.plugin.api.ProgressState
import com.superiorsoftllc.infantry.plugin.api.ProgressTask
import com.superiorsoftllc.infantry.plugin.host.ProgressCenter
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** CORE-PROGRESS-DOWNLOAD-1: the core downloader against a scripted transport, through the real progress system. */
class CoreDownloadsTest {
    private val dir: File = createTempDirectory("core-downloads").toFile()
    private val center = ProgressCenter(clock = System::currentTimeMillis, minIntervalMs = { 0 })
    private val seen = CopyOnWriteArrayList<ProgressTask>()
    private val watch = center.observe { seen += it }
    private val payload = ByteArray(200_000) { (it % 251).toByte() }
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    @AfterTest fun cleanup() { watch.close(); dir.deleteRecursively() }

    private fun limits(overrides: Map<LimitDef, Long> = emptyMap()): (LimitDef) -> Long = { overrides[it] ?: if (it == LimitRegistry.DOWNLOAD_STALL_SECONDS) 0 else it.default }

    private fun downloads(transport: DownloadTransport, limit: (LimitDef) -> Long = limits()) =
        CoreDownloads(progress = { center.forSource(it) }, limit = limit, coreTransport = transport, sleep = {})

    private fun whole(from: Long = 0) = DownloadResponse(
        if (from > 0) 206 else 200, payload.size - from,
        ByteArrayInputStream(payload, from.toInt(), payload.size - from.toInt()),
    )

    @Test fun `a plugin download shows its steps and amounts, then done`() {
        val dest = File(dir, "plugin.jar")
        val r = downloads({ _, _, _, from -> whole(from) }).download("plugins", DownloadRequest("https://example.test/plugin.jar", dest, title = "Installing plugin.jar", sha256 = payloadSha))

        assertTrue(r.ok, r.error)
        assertContentEquals(payload, dest.readBytes())
        assertEquals(payloadSha, r.sha256)
        assertFalse(File(dir, "plugin.jar.part").exists(), "the temp file is moved into place")
        val steps = seen.map { it.step }.filter { it.isNotEmpty() }.distinct()
        assertEquals(listOf(CoreDownloads.STEP_DOWNLOAD, CoreDownloads.STEP_VERIFY), steps)
        assertTrue(seen.any { it.state == ProgressState.RUNNING && it.done > 0 && it.total == payload.size.toLong() }, "bytes are reported against the total")
        val last = seen.last()
        assertEquals(ProgressState.DONE, last.state)
        assertEquals("plugins", last.source)
        assertEquals("Installing plugin.jar", last.title)
    }

    @Test fun `PROGRESS-STUCK-1 a finished catalog download is closed and no longer reads Verifying`() {
        val dest = File(dir, "infantry-community.json.part")
        val r = downloads({ _, _, _, from -> whole(from) }).download("plugins", DownloadRequest("https://example.test/c.json", dest, title = "Downloading ${dest.name}"))

        assertTrue(r.ok, r.error)
        val task = center.tasks().single()
        assertEquals(ProgressState.DONE, task.state)
        assertTrue(task.finished, "a finished download is not a live task")
        assertTrue(task.step != CoreDownloads.STEP_VERIFY, "a DONE row must not keep the stale step text, was '${task.step}'")
    }

    @Test fun `a sha256 mismatch fails with a clear reason and offers Retry`() {
        val opened = CountDownLatch(2)
        val bothFailed = CountDownLatch(2)
        val w = center.observe { if (it.state == ProgressState.FAILED) bothFailed.countDown() }
        val dest = File(dir, "bad.jar")
        val r = downloads({ _, _, _, from -> opened.countDown(); whole(from) }).download("plugins", DownloadRequest("https://example.test/bad.jar?token=s3cret", dest, sha256 = "00".repeat(32)))

        assertFalse(r.ok)
        val error = assertNotNull(r.error)
        assertTrue(error.startsWith("sha256 mismatch for bad.jar: expected ${"00".repeat(32)}, got $payloadSha"), error)
        assertTrue(r.retryable)
        assertFalse(dest.exists())
        assertFalse(File(dir, "bad.jar.part").exists(), "a file that failed its hash is not kept")
        val failed = center.tasks().single()
        assertEquals(ProgressState.FAILED, failed.state)
        assertEquals(error, failed.error)
        assertTrue(failed.retryable, "the failed task offers Retry")

        assertTrue(center.retry(failed.id), "Retry runs the download again")
        assertTrue(opened.await(10, TimeUnit.SECONDS), "the retry opened the URL again")
        assertTrue(bothFailed.await(10, TimeUnit.SECONDS), "the retried download ended (same bad file, same reason)")
        w.close()
    }

    @Test fun `a broken connection is retried and resumed from the bytes already there`() {
        val cut = 70_000
        val froms = CopyOnWriteArrayList<Long>()
        val transport = DownloadTransport { _, _, _, from ->
            froms += from
            if (froms.size == 1) DownloadResponse(200, payload.size.toLong(), Breaking(payload.copyOf(cut))) else whole(from)
        }
        val dest = File(dir, "resume.bin")
        val r = downloads(transport).download("plugins", DownloadRequest("https://example.test/resume.bin", dest, sha256 = payloadSha))

        assertTrue(r.ok, r.error)
        assertEquals(2, r.attempts)
        assertEquals(listOf(0L, cut.toLong()), froms, "the second try asks for the rest only (Range)")
        assertContentEquals(payload, dest.readBytes())
        assertTrue(seen.any { it.step.startsWith("${CoreDownloads.STEP_DOWNLOAD} (retry 1 of") }, "the retry shows as a step")
        assertEquals(ProgressState.DONE, seen.last().state)
    }

    @Test fun `retries stop at the named limit`() {
        var opens = 0
        val r = downloads({ _, _, _, _ -> opens++; throw IOException("connection refused") }, limits(mapOf(LimitRegistry.DOWNLOAD_RETRIES to 2)))
            .download("plugins", DownloadRequest("https://example.test/x", File(dir, "x")))
        assertFalse(r.ok)
        assertEquals(3, opens)
        assertTrue(r.error!!.contains("gave up after 3 tries"), r.error)
        assertEquals(ProgressState.FAILED, center.tasks().single().state)
    }

    @Test fun `cancel stops the download and deletes the temp file`() {
        val dest = File(dir, "big.bin")
        val transport = DownloadTransport { _, _, _, _ ->
            DownloadResponse(200, payload.size.toLong(), object : InputStream() {
                var sent = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (sent > 0) center.tasks().first { !it.finished }.let { center.cancel(it.id) }
                    val n = minOf(len, 1000)
                    sent += n
                    return n
                }
            })
        }
        val r = downloads(transport).download("plugins", DownloadRequest("https://example.test/big.bin", dest))

        assertTrue(r.cancelled)
        assertFalse(r.ok)
        assertFalse(File(dir, "big.bin.part").exists(), "the temp file is cleaned up")
        assertFalse(dest.exists())
        assertEquals(ProgressState.CANCELLED, center.tasks().single().state)
    }

    @Test fun `a plugin transport named by the setting is used, and one that throws falls back to the core transport`() {
        val faults = CopyOnWriteArrayList<String>()
        var setting = "mirror"
        val d = CoreDownloads(progress = { center.forSource(it) }, limit = limits(), transportSetting = { setting }, coreTransport = { _, _, _, from -> whole(from) }, sleep = {}, onFault = { id, _ -> faults += id })
        var mirrored = 0
        d.forSource("mirror").transport("", { _, _, _, from -> mirrored++; whole(from) })
        assertTrue(d.download("plugins", DownloadRequest("https://example.test/a", File(dir, "a"))).ok)
        assertEquals(1, mirrored)

        setting = "broken"
        d.transport("broken", { _, _, _, _ -> throw IllegalStateException("plugin bug") })
        val r = d.download("plugins", DownloadRequest("https://example.test/b", File(dir, "b")))
        assertTrue(r.ok, r.error)
        assertEquals(listOf("broken"), faults)
    }

    @Test fun `secret headers never reach the error text`() {
        val r = downloads({ _, _, _, _ -> DownloadResponse(403, 0, ByteArrayInputStream(ByteArray(0))) })
            .download("plugins", DownloadRequest("https://user:pw@example.test/x?token=abc", File(dir, "x"), secretHeaders = mapOf("Authorization" to "Bearer TOPSECRET")))
        val text = r.error!!
        assertFalse("TOPSECRET" in text || "token=abc" in text || "pw@" in text, text)
    }

    /** A body that sends [bytes] and then breaks like a dropped connection. */
    private class Breaking(bytes: ByteArray) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        override fun read(): Int = inner.read().takeIf { it >= 0 } ?: throw IOException("connection reset")
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).takeIf { it >= 0 } ?: throw IOException("connection reset")
    }
}
