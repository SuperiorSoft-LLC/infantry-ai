// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.NetworkInterface
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/*
 * Shared plumbing for the Phase 7 remote tests: running the REAL [Cli] on a worker thread, writing token
 * files, and finding a LAN address. Kept in one place so the daemon-side and client-side suites drive the
 * same code the same way.
 */

/** An [Appendable] that a worker thread writes while the test thread reads it. */
internal class SyncText : Appendable {
    private val text = StringBuilder()

    override fun append(csq: CharSequence?): Appendable = synchronized(text) {
        text.append(csq)
        this
    }

    override fun append(csq: CharSequence?, start: Int, end: Int): Appendable = synchronized(text) {
        text.append(csq, start, end)
        this
    }

    override fun append(c: Char): Appendable = synchronized(text) {
        text.append(c)
        this
    }

    fun value(): String = synchronized(text) { text.toString() }
}

/** The result of one CLI invocation. */
internal class CliRun(val exit: Int, val out: String, val err: String)

/** One `infantry` invocation on the calling thread. */
internal fun runCli(vararg args: String): CliRun {
    val out = StringBuilder()
    val err = StringBuilder()
    val exit = Cli.run(arrayOf(*args), out, err, CliTestEnv.getenv)
    return CliRun(exit, out.toString(), err.toString())
}

/**
 * One `infantry` invocation on its own thread, so a test can keep it running (a `watch` on a pod that does
 * not end on its own) while it drives the daemon some other way.
 */
internal class BackgroundCli(vararg args: String) {
    val out = SyncText()
    val err = SyncText()
    private val exitCode = AtomicInteger(-1)
    private val worker = Thread({ exitCode.set(Cli.run(arrayOf(*args), out, err, CliTestEnv.getenv)) }, "infantry-cli-under-test")
        .apply {
            isDaemon = true
            start()
        }

    /** Block until [needle] appears on stdout or stderr. Fails, showing what was seen, if it never does. */
    fun awaitText(needle: String, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (out.value().contains(needle) || err.value().contains(needle)) return
            if (!worker.isAlive) break
            Thread.sleep(20)
        }
        error("'$needle' never appeared; out=${out.value()} err=${err.value()}")
    }

    /** Block until the command ends, and return its exit code. */
    fun finish(timeoutMs: Long = 30_000): Int {
        worker.join(timeoutMs)
        check(!worker.isAlive) { "the command did not end within ${timeoutMs}ms; out=${out.value()}" }
        return exitCode.get()
    }
}

/** A token that clears the minimum length, contains no whitespace, and differs on every call. */
internal fun freshToken(): String =
    "infantry-test-" + UUID.randomUUID().toString() + UUID.randomUUID().toString().replace("-", "")

/** A token file. [bom] writes the UTF-8 byte-order mark that Windows PowerShell adds. */
internal fun writeTokenFile(token: String, bom: Boolean = false): File {
    val file = Files.createTempFile("infantry-token", ".txt").toFile()
    val body = token.toByteArray(Charsets.UTF_8)
    file.writeBytes(if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body else body)
    return file
}

/**
 * A non-loopback IPv4 address of this machine, preferring a private (LAN) one, or null when there is none.
 * A caller that gets null skips its LAN case and says so; it never invents an address.
 */
internal fun lanIpv4(): String? {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
    return Collections.list(interfaces)
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .flatMap { Collections.list(it.inetAddresses) }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
        .sortedByDescending { it.isSiteLocalAddress }
        .firstOrNull()?.hostAddress
}

/**
 * Whether a socket on this machine can reach a listener bound to [address]. A host firewall that drops traffic to
 * a LAN address turns a connect into a silent hang, so the LAN tests probe first and say why they cannot run.
 */
internal fun lanPathAnswers(address: String): Boolean = runCatching {
    ServerSocket(0, 1, InetAddress.getByName(address)).use { server ->
        Socket().use { client ->
            client.connect(InetSocketAddress(address, server.localPort), 3_000)
            true
        }
    }
}.getOrDefault(false)
