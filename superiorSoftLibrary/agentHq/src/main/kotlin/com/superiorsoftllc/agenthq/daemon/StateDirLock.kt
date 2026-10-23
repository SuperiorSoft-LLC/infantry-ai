package com.superiorsoftllc.agenthq.daemon

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * BUG-PORT-1-STATEDIR: one daemon per state dir.
 *
 * Since PORT-1 a taken port falls back to a free one, so a second daemon on the same `AGENTHQ_STATE_DIR` came up
 * and both wrote `agenthq.db`, `sessions.json` and `mailbox.json`. The daemon now takes [FILE_NAME] in the state
 * dir BEFORE anything opens the database; a second daemon finds it held and refuses with one line naming the
 * holder's pid and port ([StateDirInUseException]).
 *
 * The exclusion is an OS file lock, not the file's existence: the operating system drops it when the holder dies,
 * however it dies, so a stale lock (dead pid, kill -9, crash) is taken over with no cleanup step. The file's text
 * (`pid=…` / `port=…`) is only the description a refused daemon prints.
 *
 * The lock covers one byte far past the end of the file, never the text itself: Windows locks are mandatory, and
 * locking the text would stop the refused daemon from reading who holds it.
 *
 * Same rule for every provider and harness: nothing here knows which CLI the daemon runs.
 */
object StateDirLock {
    const val FILE_NAME = "daemon.lock"

    /** The locked byte: past any text this file will ever hold. */
    private const val LOCK_POSITION = Long.MAX_VALUE - 1

    /** What a lock file says about its holder. Either part may be unknown (null). */
    data class Holder(val pid: Long?, val port: Int?) {
        fun describe(): String = "pid ${pid ?: "unknown"}, port ${port ?: "not bound yet"}"
    }

    /** A held state-dir lock. [recordPort] once the port is bound; [release] on clean shutdown (idempotent). */
    class Held internal constructor(
        val file: File,
        private val channel: FileChannel,
        private val lock: FileLock,
        val pid: Long,
    ) {
        /** Record the port this daemon bound, so a refused daemon can name it. */
        fun recordPort(port: Int) = writeHolder(Holder(pid, port))

        internal fun writeHolder(holder: Holder) = synchronized(this) {
            if (!channel.isOpen) return@synchronized
            val text = buildString {
                append("pid=").append(holder.pid ?: "").append('\n')
                holder.port?.let { append("port=").append(it).append('\n') }
            }
            channel.truncate(0)
            channel.write(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), 0)
            channel.force(false)
        }

        /** Drop the lock. The file stays: without the OS lock its text is just a stale description. */
        fun release() = synchronized(this) {
            runCatching { if (lock.isValid) lock.release() }
            runCatching { channel.close() }
            Unit
        }
    }

    /**
     * Take the lock on [stateDir], or throw [StateDirInUseException] naming the live holder.
     *
     * Call it before anything opens the database or any state file.
     */
    fun acquire(stateDir: File, pid: Long = ProcessHandle.current().pid()): Held {
        stateDir.mkdirs()
        val file = File(stateDir, FILE_NAME)
        val channel = RandomAccessFile(file, "rw").channel
        val lock = try {
            channel.tryLock(LOCK_POSITION, 1, false)
        } catch (_: OverlappingFileLockException) {
            null // already held by this JVM: same answer as another process holding it
        } catch (failed: Exception) {
            channel.close()
            throw failed
        }
        if (lock == null) {
            channel.close()
            throw StateDirInUseException(stateDir, read(file))
        }
        return Held(file, channel, lock, pid).also { it.writeHolder(Holder(pid, null)) }
    }

    /** The holder [file] describes; unknown parts are null. Never throws. */
    fun read(file: File): Holder {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        fun value(key: String) = lines.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()
        return Holder(
            pid = value("pid")?.toLongOrNull(),
            port = value("port")?.toIntOrNull()?.takeIf { it in 1..65535 },
        )
    }
}

/** BUG-PORT-1-STATEDIR: another live daemon holds this state dir's lock. [message] is the one line to print. */
class StateDirInUseException(stateDir: File, val holder: StateDirLock.Holder) : IllegalStateException(
    "state dir ${stateDir.absolutePath} is in use by another daemon (${holder.describe()}). " +
        "Stop that daemon, or set AGENTHQ_STATE_DIR to a different directory.",
)
