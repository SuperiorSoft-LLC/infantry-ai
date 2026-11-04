// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chat.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * RUNTIME-LOAD-8: Infantry_Chat's daemon half, the COMMS mail tools (`infantry_inbox`, `infantry_read`, `infantry_message`) that
 * `infantry mcp serve` used to serve itself (MAIL-MCP-1). Each is the same frame the CLI sends (`mail_inbox`, `mail_read`,
 * `mail_send`) on the caller's own connection, so the daemon stays the single owner of the mailbox and every policy gate still
 * applies. WHO I AM is the MCP process's session (`ToolCall.clientSessionId`), else the human handle `user`. Same names, schemas
 * and answers. The plugin removed: the tools are absent, nothing else changes.
 */
class ChatDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        MailTools.InboxTool.settings = api.settings
        api.tools.register(MailTools.InboxTool)
        api.tools.register(MailTools.ReadTool)
        api.tools.register(MailTools.MessageTool)
        api.tools.register(MailTools.ProjectsTool(api))
        ImageFrames.install(api)
        api.tools.register(ChatImageTool(
            { api.sessions.get(it)?.projectId }, { id -> api.projects.list().firstOrNull { it.id == id }?.directory }, api::files,
            { args, who -> api.tools.invoke("infantry_chat_note", args, who) },
        ).tool)
    }

    override fun stop() = ImageFrames.stop()
}

/**
 * DP-10a: the `image_upload` frame and the chat image retention sweep, over `api.files(projectId)`. The frame's wire and its refusal
 * texts are the ones core answered before: `image` on success, `error` = `image_upload refused: <why>` / `image_upload failed: <why>`.
 */
object ImageFrames {
    const val IMAGE_UPLOAD = "image_upload"
    const val IMAGE = "image"
    /** CHAT-IMAGE-REMOTE-1: a client asks for an uploaded image's bytes by id; answered by [IMAGE_BYTES] (or `error`). */
    const val IMAGE_FETCH = "image_fetch"
    const val IMAGE_BYTES = "image_bytes"
    const val ERROR = "error"
    @Volatile private var sweeping: AutoCloseable? = null

    fun install(api: DaemonApi, clock: () -> Long = System::currentTimeMillis) {
        api.frames.handle(IMAGE_UPLOAD) { frame, _ -> answer(api, frame, clock) }
        api.frames.handle(IMAGE_FETCH) { frame, _ -> fetch({ api.projects.list().map { it.id } }, api::files, frame) }
        val retention = ChatImageRetention(
            projectIds = { api.projects.list().map { it.id } },
            filesOf = api::files,
            retentionDays = { ChatImageRetention.daysOf(api.settings.stored(ChatImageRetention.SETTING)) },
            clock = clock,
        )
        // IMG-5: one sweep at start, then every 6 hours.
        runCatching { retention.sweep() }
        sweeping = api.schedule("chat-image-retention", ChatImageRetention.DEFAULT_INTERVAL_MILLIS) { runCatching { retention.sweep() } }
    }

    fun stop() {
        sweeping?.let { runCatching { it.close() } }
        sweeping = null
    }

    internal fun answer(api: DaemonApi, frame: com.superiorsoftllc.infantry.daemon.api.Frame, clock: () -> Long): com.superiorsoftllc.infantry.daemon.api.Frame = try {
        val upload = ImageUpload.decode(frame.data)
        val stored = ImageUploads.store(api.files(upload.projectId), upload, clock())
        com.superiorsoftllc.infantry.daemon.api.Frame(IMAGE, stored.toJson())
    } catch (e: IllegalArgumentException) {
        com.superiorsoftllc.infantry.daemon.api.Frame(ERROR, "$IMAGE_UPLOAD refused: ${e.message ?: "invalid request"}")
    } catch (e: Exception) {
        com.superiorsoftllc.infantry.daemon.api.Frame(ERROR, "$IMAGE_UPLOAD failed: ${e.message ?: e::class.simpleName}")
    }
}

/**
 * CHAT-IMAGE-REMOTE-1: the `image_fetch` answer. A client on another host (the phone over the tailnet) cannot read the daemon's
 * file path, so it asks by upload id (`<sha256>.<ext>`, the stored file's name): `{"id":..., "projectId"?:...}`. The answer is
 * `image_bytes` `{"id", "mimeType", "base64"}` when found, `{"id", "missing": true}` when no project holds it (retention swept it),
 * or `error` `image_fetch refused: <why>` for a malformed request. Only stored upload names are accepted, so no path escapes.
 */
internal fun ImageFrames.fetch(
    projectIds: () -> List<String>,
    filesOf: (String) -> com.superiorsoftllc.infantry.daemon.api.files.ProjectFiles,
    frame: com.superiorsoftllc.infantry.daemon.api.Frame,
): com.superiorsoftllc.infantry.daemon.api.Frame {
    fun refuse(why: String) = com.superiorsoftllc.infantry.daemon.api.Frame(ImageFrames.ERROR, "${ImageFrames.IMAGE_FETCH} refused: $why")
    val obj = runCatching { Json.parseToJsonElement(frame.data) as? JsonObject }.getOrNull() ?: return refuse("payload must be a JSON object")
    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase().orEmpty()
    if (!ImageUploads.isUploadId(id)) return refuse("'${id.take(80)}' is not an uploaded image id")
    val hint = (obj["projectId"] as? JsonPrimitive)?.contentOrNull
    val order = projectIds().let { ids -> if (hint != null && hint in ids) listOf(hint) + (ids - hint) else ids }
    val found = order.firstNotNullOfOrNull { p -> runCatching { ImageUploads.find(filesOf(p), id) }.getOrNull() }
    val body = buildJsonObject {
        put("id", id)
        if (found == null) put("missing", true) else {
            put("mimeType", found.second)
            put("base64", java.util.Base64.getEncoder().encodeToString(found.first))
        }
    }
    return com.superiorsoftllc.infantry.daemon.api.Frame(ImageFrames.IMAGE_BYTES, body.toString())
}

object MailTools {
    const val INBOX = "infantry_inbox"
    const val READ = "infantry_read"
    const val MESSAGE = "infantry_message"
    const val PROJECTS = "infantry_projects"

    private const val HUMAN = "user"
    private const val PREVIEW_CHARS = 120
    private const val SUBJECT_CHARS = 80
    private const val DEFAULT_ITEMS = 20
    private val json = Json { ignoreUnknownKeys = true }

    private fun prop(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    /** The wire `Message`, read leniently: only what the tools print, with the computed flags rebuilt from the stored fields. */
    internal class Mail(private val o: JsonObject) {
        private fun s(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        private fun has(key: String) = o[key].let { it != null && it !is JsonNull }
        val id = s("id") ?: ""
        val threadId = s("threadId") ?: ""
        val from = s("from") ?: ""
        val to = s("to") ?: ""
        val subject = s("subject") ?: ""
        val body = s("body") ?: ""
        val replyTo = s("replyTo")
        val failureReason = s("failureReason")
        val sentAt: Long = (o["sentAt"] as? JsonPrimitive)?.longOrNull ?: 0L
        val state = s("state") ?: "QUEUED"
        val delivered get() = has("deliveredAt") || state in setOf("DELIVERED", "READ", "ANSWERED")
        val read get() = has("readAt") || state in setOf("READ", "ANSWERED")
        val failed get() = state == "UNDELIVERABLE"

        fun row() = buildJsonObject {
            put("id", id)
            put("threadId", threadId)
            put("from", from)
            put("to", to)
            put("subject", subject)
            put("sentAt", sentAt)
            put("state", state)
            put("read", read)
            put("preview", body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(PREVIEW_CHARS) ?: "")
        }
    }

    private fun session(call: ToolCall): String? = call.clientSessionId?.trim()?.takeIf { it.isNotEmpty() }

    /** Every message addressed to me, as the daemon holds it (every state, oldest first). */
    private fun fetchInbox(call: ToolCall): List<Mail> {
        val session = session(call)
        val reply = call.request("mail_inbox", session ?: HUMAN, session)
        if (reply.type == "error") error(reply.data.ifBlank { "mail_inbox failed" })
        return json.parseToJsonElement(reply.data).jsonArray.map { Mail(it.jsonObject) }
    }

    private fun ok(payload: JsonObject) = ToolResult(json.encodeToString(JsonObject.serializer(), payload))

    private fun refused(text: String) = ToolResult(text, isError = true)

    private inline fun guarded(name: String, block: () -> ToolResult): ToolResult = try {
        block()
    } catch (e: Exception) {
        refused(e.message ?: "$name failed")
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    object InboxTool : DaemonTool {
        override val name = INBOX
        override val description =
            "Your Infantry AI COMMS inbox (this session, or the human handle outside one): counts (unread, total) and one line per " +
                "item (id, from, subject, state), newest unread first, 20 by default (setting limits.tools.inboxLimit). No bodies. " +
                "Open one with $READ <id>. Params: limit, offset, unreadOnly, previews (true adds a one-line preview)."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("unreadOnly", buildJsonObject {
                    put("type", "boolean")
                    put("default", false)
                    put("description", "Only the messages not read yet.")
                })
                put("limit", prop("integer", "At most this many items (default: setting limits.tools.inboxLimit, 20)."))
                put("offset", prop("integer", "Skip this many items (paging; 0 = the newest)."))
                put("previews", prop("boolean", "Add a one-line preview of each body (default false)."))
            })
            put("additionalProperties", false)
        }
        override val annotations = buildJsonObject { put("readOnlyHint", true) }

        /** The host's settings, handed over at start; null reads as the registry default. */
        @Volatile var settings: com.superiorsoftllc.infantry.daemon.api.processes.PluginSettings? = null

        override fun call(call: ToolCall): ToolResult = guarded(INBOX) {
            val unreadOnly = (call.arguments["unreadOnly"] as? JsonPrimitive)?.booleanOrNull == true
            val previews = (call.arguments["previews"] as? JsonPrimitive)?.booleanOrNull == true
            val all = fetchInbox(call)
            val matching = if (unreadOnly) all.filterNot { it.read } else all
            val limit = (call.arguments["limit"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                ?: settings?.limit("limits.tools.inboxLimit")?.toInt()?.takeIf { it > 0 } ?: DEFAULT_ITEMS
            val offset = (call.arguments["offset"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0
            val page = matching.sortedWith(compareBy<Mail> { it.read }.thenByDescending { it.sentAt }).drop(offset).take(limit)
            ok(buildJsonObject {
                put("unread", all.count { !it.read })
                put("total", all.size)
                put("shown", page.size)
                put("items", buildJsonArray {
                    page.forEach { m ->
                        add(JsonPrimitive(
                            "${m.id} | ${m.from} | ${m.subject.take(SUBJECT_CHARS)} | ${m.state}" +
                                if (previews) " | " + (m.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(PREVIEW_CHARS) ?: "") else "",
                        ))
                    }
                })
                put("hint", "read one with $READ <id>" + if (offset + page.size < matching.size) "; more: offset=${offset + page.size}" else "")
            })
        }
    }

    object ReadTool : DaemonTool {
        override val name = READ
        override val description = "Read one message of your inbox in full (its body) and mark it read."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("messageId", prop("string", "Message id from $INBOX, or a unique prefix of one.")); put("bodyChars", prop("integer", "Cut the body to this many characters (default: the whole body).")) })
            put("required", buildJsonArray { add(JsonPrimitive("messageId")) })
            put("additionalProperties", false)
        }
        override val annotations = buildJsonObject {
            put("readOnlyHint", false)
            put("destructiveHint", false)
            put("idempotentHint", true)
            put("openWorldHint", false)
        }

        override fun call(call: ToolCall): ToolResult = guarded(READ) {
            val prefix = call.arguments.string("messageId")
                ?: return@guarded refused("$READ needs a messageId (the full id, or a unique prefix of one from $INBOX)")
            val matches = fetchInbox(call).filter { it.id.startsWith(prefix) }
            val found = when (matches.size) {
                1 -> matches.single()
                0 -> return@guarded refused("$READ: no such message '$prefix' in this inbox")
                else -> return@guarded refused("$READ: message id '$prefix' is ambiguous: ${matches.joinToString(", ") { it.id }}")
            }
            if (found.failed) {
                return@guarded refused("$READ: message '${found.id}' was never delivered: ${found.failureReason ?: "the delivery failed"}")
            }
            if (!found.delivered) {
                return@guarded refused("$READ: message '${found.id}' has not reached a console yet (state ${found.state}), so it cannot be marked read; it is delivered when your current turn ends (see it now with: infantry thread \"${found.threadId}\")")
            }
            val reply = call.request("mail_read", found.id, session(call))
            if (reply.type == "error") return@guarded refused(reply.data.ifBlank { "mail_read failed" })
            val updated = Mail(json.parseToJsonElement(reply.data).jsonObject)
            ok(buildJsonObject {
                updated.row().forEach { (key, value) -> put(key, value) }
                put("body", (call.arguments["bodyChars"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let { n -> updated.body.take(n) + if (updated.body.length > n) "...${updated.body.length - n} chars cut" else "" } ?: updated.body)
                updated.replyTo?.let { put("replyTo", it) }
            })
        }
    }

    /** XPROJ-1: the project directory, read only and on demand; nothing of it is ever put in a session's start brief. */
    class ProjectsTool(private val api: com.superiorsoftllc.infantry.daemon.api.DaemonApi) : DaemonTool {
        override val name = PROJECTS
        override val description =
            "Look up Infantry AI projects: id, name, one-line description and the roles with a live session. Pass query to find one by " +
                "name or id. Mail another project's role with $MESSAGE to=\"role@<id or name>\" and allowCrossProject=true."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("query", prop("string", "Only projects whose name, id or description contains this (case-insensitive).")) })
            put("additionalProperties", false)
        }
        override val annotations = buildJsonObject { put("readOnlyHint", true) }

        override fun call(call: ToolCall): ToolResult = guarded(PROJECTS) {
            val query = call.arguments.string("query")?.lowercase()
            val sessions = api.sessions.list()
            val rows = api.projects.list()
                .filter { query == null || query in it.name.lowercase() || query in it.id.lowercase() || query in it.description.lowercase() }
                .map { p ->
                    val roles = sessions.filter { it.projectId == p.id && it.role != null && !it.neverRun && it.state !in setOf("FAILED", "ORPHANED") }
                        .mapNotNull { it.role }.distinct().sorted()
                    buildJsonObject {
                        put("id", p.id); put("name", p.name); put("description", p.description)
                        put("liveRoles", buildJsonArray { roles.forEach { add(JsonPrimitive(it)) } })
                    }
                }
            ok(buildJsonObject {
                put("count", rows.size)
                put("projects", buildJsonArray { rows.forEach { add(it) } })
                put("hint", "mail a role there: $MESSAGE to=\"role@<id or name>\" allowCrossProject=true")
            })
        }
    }

    object MessageTool : DaemonTool {
        override val name = MESSAGE
        override val description = "Send Infantry AI COMMS mail to an agent id, a role (of the sender's project), a session id, or `role@project` (project id or name) for another project's role; that needs allowCrossProject. Find projects with $PROJECTS."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("to", prop("string", "Recipient: agent id, role, session id, or role@<project id or name> (cross-project, with allowCrossProject)."))
                put("body", prop("string", "The message."))
                put("subject", prop("string", "Optional subject."))
                put("replyTo", prop("string", "Optional message id this answers."))
                put("allowCrossProject", prop("boolean", "Send even though the recipient is in another project (refused by default)."))
            })
            put("required", buildJsonArray { add(JsonPrimitive("to")); add(JsonPrimitive("body")) })
            put("additionalProperties", false)
        }
        override val annotations = buildJsonObject {
            put("readOnlyHint", false)
            put("destructiveHint", false)
            put("openWorldHint", false)
        }

        override fun call(call: ToolCall): ToolResult = guarded(MESSAGE) {
            val to = call.arguments.string("to") ?: return@guarded refused("$MESSAGE needs a recipient (to)")
            val body = call.arguments.string("body") ?: return@guarded refused("$MESSAGE needs a body")
            val session = session(call)
            val request = buildJsonObject {
                put("from", if (session == null) HUMAN else "")
                put("to", to)
                put("subject", call.arguments.string("subject") ?: "")
                put("body", body)
                call.arguments.string("replyTo")?.let { put("replyTo", it) }
                put("allowCrossProject", (call.arguments["allowCrossProject"] as? JsonPrimitive)?.booleanOrNull == true)
            }
            val reply = call.request("mail_send", json.encodeToString(JsonObject.serializer(), request), session)
            when (reply.type) {
                "mail_refused" -> {
                    val reason = runCatching { (json.parseToJsonElement(reply.data).jsonObject["reason"] as? JsonPrimitive)?.contentOrNull }.getOrNull()
                    refused("$MESSAGE refused: ${reason ?: reply.data}")
                }
                "error" -> refused("$MESSAGE refused: ${reply.data.ifBlank { "mail_send failed" }}")
                "mail" -> {
                    val message = Mail(json.parseToJsonElement(reply.data).jsonObject)
                    if (message.failed) refused("$MESSAGE: ${message.failureReason ?: "the message could not be delivered"}")
                    else ToolResult("ok ${message.id.take(8)} ${message.state}")
                }
                else -> refused("$MESSAGE: unexpected answer ${reply.type}")
            }
        }
    }
}
