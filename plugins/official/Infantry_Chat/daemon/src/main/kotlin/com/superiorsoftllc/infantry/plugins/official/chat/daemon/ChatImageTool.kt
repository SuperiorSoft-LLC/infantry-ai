// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chat.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolInvoker
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.files.ProjectFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

/**
 * CHAT-IMAGE-1: `infantry_chat_image`: an agent or script puts an image into a session's chat. [path] is a file INSIDE the project
 * directory (no escape, symlinks resolved) or the id (sha256 hex, optional extension) of an image already uploaded; the bytes must be
 * png/jpeg/gif/webp by magic bytes and within [ImageUpload.MAX_BYTES]. It is stored like a chat upload (purpose `chat`, so
 * [ChatImageRetention] covers it) and posted by `infantry_chat_note` as a timeline item whose meta carries the stored `image` path
 * (and, for small images, `imageData` base64, so a remote client without the daemon's disk shows the same picture).
 */
class ChatImageTool(
    private val projectOf: (String) -> String?,
    private val directoryOf: (String) -> String?,
    private val filesOf: (String) -> ProjectFiles,
    private val post: (JsonObject, ToolInvoker) -> ToolResult,
    private val inlineMaxBytes: Int = INLINE_MAX_BYTES,
) {
    val tool: DaemonTool = object : DaemonTool {
        override val name = NAME
        override val description = "Show an image inline in a session's chat (desktop, web, Android), with a caption. path: a file inside the project directory " +
            "or an uploaded image id. Only png/jpeg/gif/webp (checked by content), at most 10 MB. Stored under the project's uploads like chat images. " +
            "Script: ih.chat_image{path=..., caption=..., sessionId=...}."
        override val inputSchema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"sessionId":{"type":"string","description":"target session (default: the caller's own)"},"path":{"type":"string","description":"a file inside the project, or an uploaded image id"},"caption":{"type":"string","description":"text shown with the image"},"level":{"type":"string","description":"info (default) | ok | fail"}},"required":["path"]}""",
        ).jsonObject
        override fun call(call: ToolCall): ToolResult = handle(call)
    }

    fun handle(call: ToolCall): ToolResult {
        val a = call.arguments
        fun s(k: String) = (a[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val path = s("path") ?: return refuse("needs path")
        val target = s("sessionId") ?: call.clientSessionId ?: return refuse("needs sessionId")
        val project = projectOf(target) ?: return refuse("unknown session $target")
        val callerProject = call.clientProjectId ?: call.clientSessionId?.let(projectOf)
        if (callerProject != null && callerProject != project) return refuse("session $target is in another project")
        val files = try { filesOf(project) } catch (e: Exception) { return refuse("the project has no directory") }
        val bytes = try { read(project, files, path) } catch (e: IllegalArgumentException) { return refuse(e.message ?: "invalid image") }
        val stored = try {
            ImageUploads.store(files, ImageUpload(project, ImageUpload.PURPOSE_CHAT, path.substringAfterLast('/').substringAfterLast("\\"), "", Base64.getEncoder().encodeToString(bytes)))
        } catch (e: IllegalArgumentException) { return refuse(e.message ?: "invalid image") }
        val args = buildJsonObject {
            put("sessionId", target)
            put("text", s("caption") ?: stored.relPath.substringAfterLast('/'))
            s("level")?.let { put("level", it) }
            put("storedImage", stored.path)
            if (bytes.size <= inlineMaxBytes) put("imageData", Base64.getEncoder().encodeToString(bytes))
        }
        return post(args, ToolInvoker(call.caller, call.clientSessionId, call.clientRole, call.clientProjectId, "chat_image"))
    }

    /** The bytes of [path] after every check, or IllegalArgumentException with the reason. Nothing is stored here. */
    private fun read(project: String, files: ProjectFiles, path: String): ByteArray {
        val file: File = if (ID.matches(path)) {
            val id = path.lowercase()
            val hit = listOf("chat", "ticket").flatMap { purpose ->
                files.list("${ImageUploads.UPLOADS}/$purpose").filter { it.name.substringBefore('.') == id.substringBefore('.') }.map { "${ImageUploads.UPLOADS}/$purpose/${it.name}" }
            }.firstOrNull() ?: throw IllegalArgumentException("no uploaded image '$path' in this project")
            File(files.absolutePath(hit))
        } else {
            val dir = directoryOf(project)?.let { File(it) } ?: throw IllegalArgumentException("the project has no directory")
            val root = dir.canonicalFile
            val f = (File(path).takeIf { it.isAbsolute } ?: File(root, path)).canonicalFile
            require(f.path == root.path || f.path.startsWith(root.path + File.separator)) { "path is outside the project directory" }
            f
        }
        require(file.isFile) { "no such file in the project: ${path.take(120)}" }
        require(file.length() <= ImageUpload.MAX_BYTES) { "the file is over the ${ImageUpload.MAX_BYTES / (1024 * 1024)} MB limit" }
        val bytes = file.readBytes()
        ImageType.sniff(bytes) ?: throw IllegalArgumentException("not a png, jpeg, gif or webp image (its first bytes match none of them)")
        return bytes
    }

    private fun refuse(why: String) = ToolResult("$NAME refused: $why", isError = true)

    companion object {
        const val NAME = "infantry_chat_image"
        /** Inline copy for remote clients: 1.5 MB decoded. */
        const val INLINE_MAX_BYTES = 1_500_000
        private val ID = Regex("^[0-9a-fA-F]{64}(\\.[a-zA-Z0-9]{1,5})?$")
    }
}
