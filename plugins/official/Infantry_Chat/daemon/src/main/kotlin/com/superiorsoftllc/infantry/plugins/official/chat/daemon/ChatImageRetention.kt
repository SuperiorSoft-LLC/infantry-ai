// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chat.daemon

import com.superiorsoftllc.infantry.daemon.api.files.ProjectFiles
import java.util.concurrent.TimeUnit

/**
 * IMG-5: chat uploads are disposable. A sweep deletes every file under `uploads/chat/` (and, since ATTACH-INLINE-1, `uploads/file/`)
 * of every project whose last-modified time is older than the retention window; `uploads/ticket` (and every other purpose) is never
 * read. The mtime is "last used": [ImageUploads.store] stamps it on every upload, a dedupe re-upload included.
 *
 * DP-10a: it reaches the disk only through each project's [ProjectFiles]; the plugin runs [sweep] once at start and then every
 * [DEFAULT_INTERVAL_MILLIS] through `api.schedule`. [clock] decides what "now" is (a test injects one).
 */
class ChatImageRetention(
    /** Every project's id, read at every sweep (a project added later is swept too). */
    private val projectIds: () -> List<String>,
    /** The project's file surface; throws for a project without a usable directory (that project is skipped). */
    private val filesOf: (String) -> ProjectFiles,
    /** `images.chatImageRetentionDays`, read at every sweep; 0 = keep forever. */
    private val retentionDays: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One sweep over every project; answers the (project id, relative path) of the files it deleted. Never throws for one unreadable project. */
    @Synchronized
    fun sweep(): List<Pair<String, String>> {
        val days = retentionDays()
        if (days <= 0) return emptyList()
        val cutoff = clock() - TimeUnit.DAYS.toMillis(days.toLong())
        return projectIds().flatMap { id ->
            runCatching { sweepProject(id, filesOf(id), cutoff) }.getOrDefault(emptyList())
        }
    }

    private fun sweepProject(id: String, files: ProjectFiles, cutoff: Long): List<Pair<String, String>> =
        // ATTACH-INLINE-1: `file` uploads are chat attachments too, and as disposable.
        listOf(ImageUpload.PURPOSE_CHAT, ImageUpload.PURPOSE_FILE).flatMap { purpose ->
            val dir = "${ImageUploads.UPLOADS}/$purpose"
            // Only regular files directly in the folder; a symlinked or nested entry is not ours to follow.
            files.list(dir).filter { it.modifiedMillis in 1 until cutoff }
                .filter { files.delete("$dir/${it.name}") }
                .map { id to "$dir/${it.name}" }
        }

    companion object {
        val DEFAULT_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)
        const val SETTING = "images.chatImageRetentionDays"
        const val DEFAULT_DAYS = 14
        private val RANGE = 0..3650

        /** The stored setting's days (a JSON integer in 0..3650), else the default. */
        fun daysOf(stored: String?): Int = stored?.trim()?.toIntOrNull()?.takeIf { it in RANGE } ?: DEFAULT_DAYS
    }
}
