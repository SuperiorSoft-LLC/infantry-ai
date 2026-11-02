package com.superiorsoftllc.agenthq.harness

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HARNESS H6c: runs a model-listing command (a descriptor harness's `modelsCommand`) bounded in time and size, with the
 * daemon's `user.home` as the child's home. Moved out of the pi catalog when pi became a plugin; the pi plugin keeps its own copy
 * (a harness plugin sees only the harness API).
 */
object BoundedCommand {
    const val NOTES_GROUP = "notes"
    const val DEFAULT_TTL_MS = 30_000L
    const val DEFAULT_TIMEOUT_MS = 20_000L
    const val DEFAULT_MAX_OUTPUT_BYTES = 1 shl 20 // 1 MiB

    /**
     * The home variables the probe child needs so it resolves [userHome] as its home: USERPROFILE on Windows,
     * HOME when it was inherited (Windows) or always (elsewhere). Only values that differ are returned, so a
     * production daemon, whose `user.home` is the real home, passes its environment through unchanged.
     */
    internal fun homeOverrides(inherited: Map<String, String>, userHome: String?, windows: Boolean): Map<String, String> {
        val home = userHome?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val names = if (windows) listOfNotNull("USERPROFILE", "HOME".takeIf { it in inherited }) else listOf("HOME")
        return names.filter { inherited[it] != home }.associateWith { home }
    }

    /**
     * Runs [argv] as a child process (an argv list, never a shell), stdin closed, stderr discarded, and returns
     * its stdout. Refused, with the child (and its descendants) killed, when it outlives [timeoutMs], prints
     * more than [maxBytes], or the calling thread is interrupted. A non-zero exit is a failure.
     *
     * INSTALL-PIDCP-1: the child's home is the daemon's `user.home` (see [homeOverrides]). a harness CLI may load every installed
     * extension even for `--list-models`, and extensions write under `os.homedir()` (pi-dcp creates `~/.pi-dcp`),
     * so an isolated daemon must not hand the child the inherited real home.
     */
    fun runBounded(argv: List<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS, maxBytes: Int = DEFAULT_MAX_OUTPUT_BYTES): String {
        val builder = ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD)
        val env = builder.environment()
        env.putAll(homeOverrides(env, System.getProperty("user.home"), windows = File.separatorChar == '\\'))
        val process = builder.start()
        fun kill() {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        try {
            process.outputStream.close()
            val buffer = ByteArrayOutputStream()
            val overflow = java.util.concurrent.atomic.AtomicBoolean(false)
            val reader = Thread {
                try {
                    val chunk = ByteArray(8192)
                    val input = process.inputStream
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        if (buffer.size() + n > maxBytes) { overflow.set(true); kill(); break }
                        buffer.write(chunk, 0, n)
                    }
                } catch (_: Exception) {
                    // Stream closed by kill(); the verdict comes from the flags below.
                }
            }.apply { isDaemon = true; name = "bounded-command-reader"; start() }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                kill()
                throw IllegalStateException("timed out after ${timeoutMs}ms")
            }
            reader.join(2_000)
            if (overflow.get()) throw IllegalStateException("output exceeded $maxBytes bytes")
            val code = process.exitValue()
            if (code != 0) throw IllegalStateException("exited with code $code")
            return buffer.toString(Charsets.UTF_8)
        } catch (e: InterruptedException) {
            kill()
            Thread.currentThread().interrupt()
            throw IllegalStateException("cancelled")
        } finally {
            if (process.isAlive) kill()
        }
    }
}
