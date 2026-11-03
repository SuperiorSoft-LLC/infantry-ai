// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import java.io.File

/**
 * INSTALL-CWD-1: where a session or ticket worker of a project runs when the daemon has no `INFANTRY_REPO` and its
 * own cwd means nothing (the installed app: a Start-menu launch's cwd is not a project). It is the project's OWN
 * directory (`Project.directory`), never the daemon's cwd.
 *
 * Only the installed profile turns this on ([ENABLED_SETTING], pinned by `InstallProfile`); the dev daemon keeps
 * running in its own directory exactly as before.
 *
 * @param directoryOf the stored directory of a project id (blank / unknown = null).
 * @param defaultProjectId the project a spawn that names none belongs to.
 */
class ProjectFolders(
    private val directoryOf: (projectId: String) -> String?,
    private val defaultProjectId: () -> String? = { null },
) {
    /** The folder [projectId] (null = the default project) runs in, or null when it has none. */
    fun find(projectId: String?): File? {
        val id = projectId?.takeIf { it.isNotBlank() } ?: defaultProjectId() ?: return null
        return directoryOf(id)?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile }
    }

    /** [find], or an [IllegalArgumentException] naming why: a session never falls back to the daemon's cwd. */
    fun require(projectId: String?): File {
        val id = projectId?.takeIf { it.isNotBlank() } ?: defaultProjectId()
            ?: throw IllegalArgumentException("no project to run in: create a project and set its directory first")
        val dir = find(id)
            ?: throw IllegalArgumentException("project $id has no directory: set the project's directory before starting work in it")
        require(dir.isDirectory) { "project $id directory ${dir.path} does not exist" }
        return dir
    }

    companion object {
        /** `true` = sessions and ticket starts run in their project's directory (the installed app). */
        const val ENABLED_SETTING = "INFANTRY_PROJECT_DIRS"

        fun enabledFromSettings(read: (String) -> String? = { infantrySetting(it) }): Boolean =
            read(ENABLED_SETTING)?.trim()?.equals("true", ignoreCase = true) == true
    }
}
