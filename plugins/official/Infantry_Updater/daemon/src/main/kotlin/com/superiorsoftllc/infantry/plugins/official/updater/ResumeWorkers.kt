// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import java.io.File

/**
 * UPDATER-KILLS-WORKERS-1: the sessions that were working when an update restarted the app. The updater writes their
 * ids just before it exits; the next start takes the list (once) and resumes each session on its own ticket.
 */
object ResumeWorkers {
    const val FILE_NAME = "update-resume-workers.txt"

    fun save(stateDir: File, sessionIds: List<String>) {
        val file = File(stateDir, FILE_NAME)
        if (sessionIds.isEmpty()) { file.delete(); return }
        stateDir.mkdirs()
        file.writeText(sessionIds.joinToString("\n"))
    }

    /** The saved ids; the file is deleted so a crash loop never resumes twice. */
    fun take(stateDir: File): List<String> {
        val file = File(stateDir, FILE_NAME)
        if (!file.isFile) return emptyList()
        val ids = runCatching { file.readLines().map { it.trim() }.filter { it.isNotEmpty() } }.getOrDefault(emptyList())
        file.delete()
        return ids
    }

    const val RESUME_PROMPT = "The app was restarted by an update while you were working. Continue the task where you left off."
}
