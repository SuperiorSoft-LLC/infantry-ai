package com.superiorsoftllc.agenthq.daemon

import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * PORT-1: the port the daemon listens on, chosen BEFORE anything uses it.
 *
 * The port is read early in [AgentHqDaemon.build]: the mail base URL rendered into delivered prompts and the
 * `AGENTHQ_URL` every spawned session receives both carry it. So the choice is made first, in one place, and
 * everything after it uses the port the daemon will actually bind.
 *
 * A taken port no longer kills the launch with a raw `BindException`. The daemon tries the next
 * [FALLBACK_SCAN] ports, then an OS-assigned one, and logs ONE line saying which. `AGENTHQ_PORT_STRICT=true`
 * keeps the old fail-fast behaviour, with a readable [PortInUseException] instead of the stack trace.
 *
 * The same rule holds for every provider and harness: nothing here knows which CLI the daemon runs.
 *
 * Fixed ports per use (QUICKSTART.md, "Ports"): 8787 the owner's daemon, 8788 the owner's UI preview, 0
 * (ephemeral) for every test, gate, QA scenario and probe. `TestPortGuardTest` enforces the last one.
 */
object DaemonPort {
    const val PORT_ENV = "AGENTHQ_PORT"
    const val STRICT_ENV = "AGENTHQ_PORT_STRICT"
    const val DEFAULT = 8787

    /** The file in the state dir that holds the port this daemon bound; removed on clean shutdown. */
    const val FILE_NAME = "daemon.port"

    /** How many ports after the requested one are tried before asking the OS for any free port. */
    const val FALLBACK_SCAN = 10

    /** The port the daemon will bind. [note] is the one line to log when it is not the one asked for. */
    data class Choice(val requested: Int, val port: Int, val note: String?)

    /**
     * BUG-FLAKE-1: a [Choice] whose port stays bound until [close]. A probe that let go at once lost a race under
     * load: on Windows bind(0) hands ports out in sequence, so a just-released fallback was the next port any socket
     * on the host got, and the daemon's own bind failed. The daemon's server closes it on ApplicationStarted, one step
     * before its engine binds, on every start path (BUG-PORT-0b); CIO cannot adopt a bound socket, and SO_REUSEADDR
     * would let any socket share the port on Windows.
     */
    class Reservation internal constructor(val choice: Choice, private val socket: ServerSocket?) : Closeable {
        /** Give the port back. Idempotent. */
        override fun close() {
            socket?.close()
        }
    }

    fun strictFromEnv(env: (String) -> String? = System::getenv): Boolean =
        env(STRICT_ENV)?.trim()?.equals("true", ignoreCase = true) == true

    /**
     * Choose the port for [host]. 0 resolves to an OS-assigned port (BUG-PORT-0). The port is free when
     * chosen but not held; the daemon itself uses [reserve].
     *
     * @throws PortInUseException when [requested] is taken and [strict] is set.
     */
    fun choose(requested: Int, host: String, strict: Boolean, pidOf: (Int) -> Long? = ::listenerPid): Choice =
        reserve(requested, host, strict, pidOf).use { it.choice }

    /**
     * BUG-FLAKE-1: [choose], with the chosen port still bound when this returns; the caller closes the
     * [Reservation] right before its server binds. 0 holds an OS-assigned port, like a fallback (BUG-PORT-0).
     *
     * @throws PortInUseException when [requested] is taken and [strict] is set.
     */
    fun reserve(requested: Int, host: String, strict: Boolean, pidOf: (Int) -> Long? = ::listenerPid): Reservation {
        // BUG-PORT-0: 0 asks the OS for a port NOW and holds it, so the mail base and every session's AGENTHQ_URL
        // carry the port the daemon will bind, never 0.
        if (requested == 0) return bind(host, 0).let { Reservation(Choice(requested, it.localPort, null), it) }
        hold(host, requested)?.let { return Reservation(Choice(requested, requested, null), it) }
        val holder = pidOf(requested)?.let { "pid $it" } ?: "pid unknown"
        if (strict) {
            throw PortInUseException(
                "port $requested in use ($holder) and $STRICT_ENV=true forbids a fallback. " +
                    "Stop whatever holds it, or unset $STRICT_ENV to let the daemon pick a free port.",
            )
        }
        val held = ((requested + 1)..(requested + FALLBACK_SCAN)).filter { it <= 65535 }.firstNotNullOfOrNull { hold(host, it) }
            ?: bind(host, 0)
        val fallback = held.localPort
        return Reservation(Choice(requested, fallback, "port $requested in use ($holder); listening on $fallback instead"), held)
    }

    /** [host]:[port] bound and held (0 = any free port), reuseAddress off; throws when it cannot be bound. */
    private fun bind(host: String, port: Int): ServerSocket {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(host, port))
        } catch (failure: Throwable) {
            socket.close()
            throw failure
        }
        return socket
    }

    /** [bind], or null when something already listens on [host]:[port]. */
    private fun hold(host: String, port: Int): ServerSocket? = runCatching { bind(host, port) }.getOrNull()

    /** Record [port] in [file], replacing what is there. */
    fun write(file: File, port: Int) {
        file.parentFile?.mkdirs()
        file.writeText("$port\n")
    }

    /** The port recorded in [file], or null when it is absent or unreadable. */
    fun read(file: File): Int? = runCatching { file.readText().trim().toInt() }.getOrNull()?.takeIf { it in 1..65535 }

    /**
     * Remove [file], but only while it still names [port]: a second daemon on the same state dir may have
     * written its own port since, and its file is not ours to delete.
     */
    fun release(file: File, port: Int) {
        if (read(file) == port) file.delete()
    }

    /** Best effort: the PID listening on [port] on this machine, or null when it cannot be told quickly. */
    fun listenerPid(port: Int): Long? = runCatching {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val command = if (windows) listOf("netstat", "-ano", "-p", "TCP") else listOf("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN", "-t")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (windows) {
            out.lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { it.size >= 5 && it[1].endsWith(":$port") && it[3].equals("LISTENING", ignoreCase = true) }
                ?.get(4)?.toLongOrNull()
        } else {
            out.lineSequence().firstNotNullOfOrNull { it.trim().toLongOrNull() }
        }
    }.getOrNull()
}

/** PORT-1: the configured port is taken and `AGENTHQ_PORT_STRICT=true` forbids a fallback. */
class PortInUseException(message: String) : IllegalStateException(message)
