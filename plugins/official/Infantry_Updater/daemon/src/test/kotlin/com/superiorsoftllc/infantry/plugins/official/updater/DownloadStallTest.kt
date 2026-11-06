// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * UPDATE-UX-1: the owner's "Downloading and verifying… never finished". A connection that stops sending is aborted after
 * the stall timeout instead of hanging forever, and a slow but moving download is never cut off: it reports its bytes
 * as they arrive and finishes, even when it runs longer than the stall timeout.
 */
class DownloadStallTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { executor = java.util.concurrent.Executors.newCachedThreadPool() }
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val release = CountDownLatch(1)

    @AfterTest
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Serves [bytes] as `InfantryAI-1.0.4.msi`: [chunk] bytes every [pauseMs]; [stallAfter] = stop sending after that many bytes. */
    private fun update(bytes: ByteArray, chunk: Int, pauseMs: Long, stallAfter: Int? = null): Pair<Updater, AvailableUpdate> {
        server.createContext("/dl/InfantryAI-1.0.4.msi") { ex ->
            ex.sendResponseHeaders(200, bytes.size.toLong())
            runCatching {
                ex.responseBody.use { out ->
                    var sent = 0
                    while (sent < bytes.size) {
                        if (stallAfter != null && sent >= stallAfter) { out.flush(); release.await(30, TimeUnit.SECONDS); return@use }
                        val n = minOf(chunk, bytes.size - sent)
                        out.write(bytes, sent, n); out.flush()
                        sent += n
                        Thread.sleep(pauseMs)
                    }
                }
            }
        }
        server.createContext("/dl/SHA256SUMS") { ex ->
            val body = "${sha(bytes)}  InfantryAI-1.0.4.msi\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.start()
        val installer = ReleaseAsset("InfantryAI-1.0.4.msi", bytes.size.toLong(), browserDownloadUrl = "$base/dl/InfantryAI-1.0.4.msi")
        val sums = ReleaseAsset("SHA256SUMS", 100, browserDownloadUrl = "$base/dl/SHA256SUMS")
        val v = SemVer.parse("1.0.3")!!
        val upd = AvailableUpdate(v, SemVer.parse("1.0.4")!!, GitHubRelease("v1.0.4", assets = listOf(installer, sums)), installer, sums)
        return Updater(UpdateConfig("superiorsoft/agenthq", v, base), stallTimeout = Duration.ofMillis(800)) to upd
    }

    private class Recorder : DownloadProgress {
        val bytes = mutableListOf<Long>()
        val notes = mutableListOf<String>()
        override fun bytes(done: Long, total: Long) { bytes += done }
        override fun note(text: String) { notes += text }
    }

    @Test
    fun `a connection that stops sending is aborted with a stalled message, not left hanging`() {
        val (updater, upd) = update(ByteArray(64 * 1024) { it.toByte() }, chunk = 4096, pauseMs = 0, stallAfter = 8192)
        val rec = Recorder()
        val started = System.nanoTime()
        val got = updater.download(upd, Files.createTempDirectory("stall").toFile(), rec)
        val took = Duration.ofNanos(System.nanoTime() - started)
        val failed = assertIs<Fetched.Failed>(got)
        assertTrue("download stalled" in failed.failure.message, failed.failure.message)
        assertTrue(took < Duration.ofSeconds(10), "aborted after $took")
        assertTrue(rec.bytes.isNotEmpty() && rec.bytes.last() >= 8192, "bytes reported before the stall: ${rec.bytes}")
        // The interrupt the watch used is cleared: the calling thread can keep working.
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun `a slow download that keeps moving runs past the stall timeout, reports progress and verifies`() {
        val bytes = ByteArray(40 * 1024) { (it * 7).toByte() }
        // 20 chunks, 150 ms apart: ~3 s in all, far past the 800 ms stall timeout and the request's header timeout.
        val (updater, upd) = update(bytes, chunk = 2048, pauseMs = 150)
        val rec = Recorder()
        val dir = Files.createTempDirectory("slow").toFile()
        val ok = assertIs<Fetched.Ok<VerifiedInstaller>>(updater.download(upd, dir, rec)).value
        assertTrue(ok.checksumVerified)
        assertEquals(bytes.size.toLong(), rec.bytes.last())
        assertTrue(rec.bytes.size > 5, "progress reported as the bytes arrived: ${rec.bytes}")
        assertTrue(rec.notes.any { "HTTP 200" in it }, "steps noted: ${rec.notes}")
        dir.deleteRecursively()
    }
}
