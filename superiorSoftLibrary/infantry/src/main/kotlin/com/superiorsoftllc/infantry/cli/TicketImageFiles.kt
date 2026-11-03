// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.api.LocalFile
import com.superiorsoftllc.infantry.daemon.api.board.TicketImages
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.ImageStored
import com.superiorsoftllc.infantry.protocol.ImageUpload
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

/**
 * IMG-AGENT-1: the agent side of ticket images, shared by `ticket create|update --image` and the MCP ticket tools.
 *
 * An agent names an image by the FILE it has: each local file is uploaded with `image_upload` (purpose `ticket`, the
 * same frame `infantry image upload` sends, so identical bytes land at the same `.infantry/uploads/ticket/` path) and
 * the ticket stores the relPath the daemon answers. A path already under `.infantry/uploads/ticket/` (a stored image,
 * as `ticket show` prints it or `image upload` answered it) is passed through untouched; where it points is the
 * daemon's `ticket_save` check.
 *
 * NOTHING IS SAVED ON A REFUSAL: every local file is checked here (exists, at most [ImageUpload.MAX_BYTES], png /
 * jpeg / gif / webp by its magic bytes, the daemon's own test) before the first upload is sent, so a bad file in the
 * list refuses the whole call with no upload written and no ticket saved.
 */
internal object TicketImageFiles {
    /** Where a ticket's images are stored, relative to its project directory. */
    const val TICKET_UPLOADS = TicketImages.TICKET_UPLOADS

    private val json = Json { ignoreUnknownKeys = true }

    /** True when [path] already names a stored ticket image (relPath or absolute), so it is not uploaded again. */
    fun isStored(path: String): Boolean = TicketImages.isStored(path)

    /** RUNTIME-LOAD-4: [path] as this machine has it, for the shared rule ([TicketImages]) the board plugin's tools apply too. */
    fun local(path: String): LocalFile {
        val file = File(path.trim())
        if (!file.isFile) return LocalFile(path)
        val size = file.length()
        return LocalFile(path, size, if (size <= TicketImages.MAX_BYTES) file.readBytes() else null)
    }

    /** Why [paths] cannot be attached, or null when every local file among them is an acceptable image. */
    fun check(paths: List<String>): String? = TicketImages.check(paths.map { p -> if (p.trim().isEmpty() || isStored(p.trim())) LocalFile(p) else local(p.trim()) })

    /**
     * [paths] as the relPaths a ticket of [projectId] stores: each local file uploaded, each stored path kept, in
     * order, duplicates dropped. Throws [IllegalArgumentException] with the refusal when [check] refuses or there is
     * no project; nothing is uploaded then.
     */
    suspend fun upload(stream: FrameStream, projectId: String?, paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        check(paths)?.let { throw IllegalArgumentException(it) }
        require(!projectId.isNullOrBlank()) { "images need the ticket's project: pass a project id" }
        return paths.map { raw ->
            val path = raw.trim()
            if (isStored(path)) path else store(stream, projectId, File(path)).relPath
        }.distinct()
    }

    /**
     * True when [given] names [stored] (a relPath): the relPath itself, its absolute path, its file name, or a local
     * file with the same bytes (stored images are named by their sha256).
     */
    fun matches(stored: String, given: String): Boolean = TicketImages.matches(stored, if (isStored(given)) LocalFile(given) else local(given))

    private suspend fun store(stream: FrameStream, projectId: String, file: File): ImageStored {
        val payload = json.encodeToString(
            ImageUpload.serializer(),
            ImageUpload(
                projectId = projectId,
                purpose = ImageUpload.PURPOSE_TICKET,
                fileName = file.name,
                // Blank: the daemon takes the type from the bytes, which [check] has already matched.
                base64 = Base64.getEncoder().encodeToString(file.readBytes()),
            ),
        )
        // Content-addressed: the same bytes land at the same path, so a re-send after a dropped link is harmless.
        val answer = stream.request(Envelope(Envelope.IMAGE_UPLOAD, payload), Envelope.IMAGE, "image_upload", idempotent = true)
        return try {
            json.decodeFromString(ImageStored.serializer(), answer.data)
        } catch (e: Exception) {
            throw IllegalStateException("malformed image answer from the daemon: ${e.message}")
        }
    }
}
