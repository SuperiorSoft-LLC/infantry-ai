package com.superiorsoftllc.agenthq.images

import com.superiorsoftllc.agenthq.protocol.ImageUpload
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * IMG-5: chat uploads are disposable. A sweep deletes every file under `<project>/.agenthq/uploads/chat/` (and, since
 * ATTACH-INLINE-1, `uploads/file/`) whose
 * last-modified time is older than the retention window; `uploads/ticket` (and every other purpose) is never read.
 * The mtime is "last used": [ImageUploads.store] stamps it on every upload, a dedupe re-upload included.
 *
 * It sweeps once at [start] and then every [intervalMillis] on [ticker]; [clock] decides what "now" is. Both are
 * injectable so a test drives a real daemon on a fake clock and fires the 6-hour tick itself.
 */
class ChatImageRetention(
    /** The project directories to sweep, read at every sweep (a project added later is swept too). */
    private val projectRoots: () -> List<File>,
    /** `images.chatImageRetentionDays`, read at every sweep; 0 = keep forever. */
    private val retentionDays: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    /** Runs the task every interval until the returned handle is closed. */
    private val ticker: (intervalMillis: Long, task: () -> Unit) -> AutoCloseable = Companion::scheduledTicker,
) : AutoCloseable {
    @Volatile private var tick: AutoCloseable? = null

    /** Sweep now, then every [intervalMillis]. Idempotent. */
    @Synchronized
    fun start() {
        if (tick != null) return
        runCatching { sweep() }
        tick = ticker(intervalMillis) { runCatching { sweep() } }
    }

    /** One sweep over every project; answers the files it deleted. Never throws for one unreadable project. */
    @Synchronized
    fun sweep(): List<File> {
        val days = retentionDays()
        if (days <= 0) return emptyList()
        val cutoff = clock() - TimeUnit.DAYS.toMillis(days.toLong())
        return projectRoots().flatMap { root -> runCatching { sweepProject(root, cutoff) }.getOrDefault(emptyList()) }
    }

    @Synchronized
    override fun close() {
        tick?.let { runCatching { it.close() } }
        tick = null
    }

    private fun sweepProject(root: File, cutoff: Long): List<File> {
        // ATTACH-INLINE-1: `file` uploads are chat attachments too, and as disposable.
        return listOf(ImageUpload.PURPOSE_CHAT, ImageUpload.PURPOSE_FILE).flatMap { purpose ->
            val dir = File(root, "${ImageUploads.UPLOADS_REL}/$purpose")
            // Only regular files directly in the folder; a symlinked or nested entry is not ours to follow.
            val expired = dir.listFiles()?.filter { it.isFile && it.lastModified() in 1 until cutoff }.orEmpty()
            expired.filter { it.delete() }
        }
    }

    companion object {
        val DEFAULT_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)

        /** The production ticker: a daemon thread running [task] every [intervalMillis] (first run one interval in). */
        fun scheduledTicker(intervalMillis: Long, task: () -> Unit): AutoCloseable {
            val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "agenthq-chat-image-retention").apply { isDaemon = true }
            }
            executor.scheduleWithFixedDelay(task, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS)
            return AutoCloseable { executor.shutdownNow() }
        }
    }
}
