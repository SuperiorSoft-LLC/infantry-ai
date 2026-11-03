// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.api.files.ProjectFiles
import com.superiorsoftllc.infantry.persistence.TicketRow
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * IMG-3: a ticket's images. Each is stored as the relPath `image_upload` (purpose `ticket`) answered,
 * `.infantry/uploads/ticket/<sha256>.<ext>`, relative to the ticket's project directory, which [directoryOf] resolves.
 *
 * An image lives as long as a ticket references it: when a delete or an update drops one, its file is deleted unless
 * another remaining ticket still references the same file (uploads are deduped by sha256, so two tickets can share one).
 */
class TicketImages(
    /** The directory of project `id`, or null when there is no such project or it has none. */
    private val directoryOf: (projectId: String) -> File? = { null },
) {
    /**
     * [requested] as stored relPaths, duplicates dropped, for a ticket of [projectId]. Throws [IllegalArgumentException]
     * naming the first path that is not an existing file directly under the project's `.infantry/uploads/ticket/`.
     */
    fun normalize(projectId: String?, requested: List<String>): List<String> {
        if (requested.isEmpty()) return emptyList()
        val dir = projectId?.let(directoryOf)
            ?: throw IllegalArgumentException(
                "images need the ticket's project directory (the ticket ${if (projectId == null) "has no project" else "project '${projectId.take(80)}' has no directory"})",
            )
        val uploads = File(dir, TICKET_UPLOADS).canonicalFile
        return requested.map { raw ->
            val path = raw.trim()
            require(path.isNotEmpty()) { "an image path is blank" }
            val given = File(path)
            val file = (if (given.isAbsolute) given else File(dir, path)).canonicalFile
            require(file.parentFile == uploads) {
                "image '${path.take(200)}' is not under the project's $TICKET_UPLOADS/ (upload it with image_upload purpose ticket)"
            }
            require(file.isFile) { "image '${path.take(200)}' does not exist under $TICKET_UPLOADS/" }
            "$TICKET_UPLOADS/${file.name}"
        }.distinct()
    }

    /** [relPath] of a ticket of [projectId] as an absolute path, or null when the project has no directory. */
    fun absolute(projectId: String?, relPath: String): File? =
        projectId?.let(directoryOf)?.let { File(it, relPath).absoluteFile.normalize() }

    /**
     * Delete each of [dropped] (relPaths of a ticket of [projectId]) that no ticket in [remaining] still references.
     * Best effort, after the commit: a file that cannot be deleted stays, and nothing about the board changes.
     */
    fun release(projectId: String?, dropped: Collection<String>, remaining: Collection<TicketRow>) {
        if (dropped.isEmpty()) return
        val referenced = remaining.flatMap { row -> row.images.mapNotNull { key(row.projectId, it) } }.toSet()
        for (relPath in dropped.distinct()) {
            val file = key(projectId, relPath) ?: continue
            if (file in referenced) continue
            // Only ever a file directly in a ticket uploads folder: a stored relPath was validated, but stay defensive.
            if (file.parentFile?.name != TICKET_PURPOSE || file.parentFile?.parentFile?.name != "uploads") continue
            try {
                Files.deleteIfExists(file.toPath())
            } catch (_: IOException) {
                // A locked or vanished file is not the board's failure; it stays.
            }
        }
    }

    /** The canonical file [relPath] names for a ticket of [projectId]: the identity two references share. */
    private fun key(projectId: String?, relPath: String): File? = absolute(projectId, relPath)?.let {
        try {
            it.canonicalFile
        } catch (_: IOException) {
            it
        }
    }

    companion object {
        const val TICKET_PURPOSE = "ticket"
        const val TICKET_UPLOADS = "${ProjectFiles.UPLOADS_REL}/$TICKET_PURPOSE"
        val NONE = TicketImages()
    }
}
