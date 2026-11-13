// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import java.io.File
import java.util.UUID

/**
 * BUG-REAP-OWNER: the identity of one daemon state dir. It is created once and then read back on every restart of the
 * same dir, so a kept process recorded by a previous run still names this dir's owner. Copying only `sessions.json`
 * (or the db) into another dir does not carry it: that dir mints its own id, and the copied entries stop matching.
 *
 * PROC-REAP-COPY-1: copying the WHOLE dir copied `instance.id` too, so the copy claimed the original's processes (and its
 * named Windows job objects). The id is therefore bound to the dir it was minted in: [PATH_FILE] holds that dir's canonical
 * path, and a dir whose path differs is a copy and mints a fresh id. A dir from before this rule (an id, no path file) keeps
 * its id and is stamped with its own path, so the owner's sessions and kept processes stay his.
 */
object InstanceId {
    const val FILE_NAME = "instance.id"
    const val PATH_FILE = "instance.path"

    /**
     * RESUME-COPY-1: the id and whether this start MINTED it (no id file, or a copy of another dir). A minted id with sessions
     * already on disk means those sessions were written by another daemon: they are a copy's, never this daemon's to resume.
     */
    data class Loaded(val id: String, val minted: Boolean)

    fun loadOrCreate(stateDir: File): String = load(stateDir).id

    fun load(stateDir: File): Loaded {
        val file = File(stateDir, FILE_NAME)
        val pathFile = File(stateDir, PATH_FILE)
        val here = where(stateDir)
        val existing = runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        val boundTo = runCatching { pathFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (existing != null && (boundTo == null || boundTo.equals(here, ignoreCase = isWindows))) {
            if (boundTo == null) runCatching { pathFile.writeText(here) }
            return Loaded(existing, minted = false)
        }
        if (existing != null) println("[infantry] state dir $here is a copy of $boundTo: minting its own instance id")
        val fresh = UUID.randomUUID().toString()
        stateDir.mkdirs()
        file.writeText(fresh)
        runCatching { pathFile.writeText(here) }
        return Loaded(fresh, minted = true)
    }

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun where(dir: File): String = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
}
