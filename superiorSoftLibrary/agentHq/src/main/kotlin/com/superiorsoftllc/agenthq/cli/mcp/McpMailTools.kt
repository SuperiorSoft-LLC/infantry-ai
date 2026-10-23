package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.mailbox.Message
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * MAIL-MCP-1: an agent reads its own mail through `agenthq mcp serve`, exactly as `agenthq inbox` / `agenthq read` do it.
 *
 * Both tools run over the frames the CLI already sends -- `mail_inbox` for the listing, `mail_read` to acknowledge one
 * message -- so the daemon stays the single owner of the mailbox logic. WHO I AM is the CLI's identity: the session named
 * by `AGENTHQ_SESSION_ID` (the daemon exports it into every session it spawns), else the human handle `user`.
 *
 * `agenthq_read` resolves a prefix against MY inbox (the wire takes the full id) and refuses what the CLI refuses: no
 * match, several matches, a message that failed delivery, and one that has not reached a console yet.
 */
internal class McpMailTools(private val getenv: (String) -> String?) {

    suspend fun inbox(stream: FrameStream, arguments: JsonObject): ToolResult {
        val unreadOnly = (arguments["unreadOnly"] as? JsonPrimitive)?.booleanOrNull == true
        val messages = fetchInbox(stream).let { all -> if (unreadOnly) all.filterNot { it.read } else all }
        return ok(buildJsonObject {
            put("messages", buildJsonArray { messages.forEach { add(row(it)) } })
            put("count", messages.size)
            put("unreadOnly", unreadOnly)
        })
    }

    suspend fun read(stream: FrameStream, arguments: JsonObject): ToolResult {
        val prefix = (arguments["messageId"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return refused("$READ needs a messageId (the full id, or a unique prefix of one from $INBOX)")
        val matches = fetchInbox(stream).filter { it.id.startsWith(prefix) }
        val found = when (matches.size) {
            1 -> matches.single()
            0 -> return refused("$READ: no such message '$prefix' in this inbox")
            else -> return refused("$READ: message id '$prefix' is ambiguous: ${matches.joinToString(", ") { it.id }}")
        }
        if (found.failed) {
            return refused("$READ: message '${found.id}' was never delivered: ${found.failureReason ?: "the delivery failed"}")
        }
        if (!found.delivered) {
            return refused("$READ: message '${found.id}' has not reached a console yet (state ${found.state}), so it cannot be marked read; it is delivered when your current turn ends (see it now with: agenthq thread \"${found.threadId}\")")
        }
        // Idempotent: acknowledging a READ message returns it unchanged.
        val answer = stream.request(Envelope(Envelope.MAIL_READ, found.id, session()), Envelope.MAIL, "mail_read", idempotent = true)
        val updated = json.decodeFromString(Message.serializer(), answer.data)
        return ok(buildJsonObject {
            row(updated).forEach { (key, value) -> put(key, value) }
            put("body", updated.body)
            updated.replyTo?.let { put("replyTo", it) }
        })
    }

    private fun session(): String? = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }

    /** Every message addressed to me, as the daemon holds it (every state, oldest first). */
    private suspend fun fetchInbox(stream: FrameStream): List<Message> {
        val session = session()
        val answer = stream.request(Envelope(Envelope.MAIL_INBOX, session ?: HUMAN, session), Envelope.MAILS, "mail_inbox", idempotent = true)
        return json.decodeFromString(ListSerializer(Message.serializer()), answer.data)
    }

    private fun row(message: Message) = buildJsonObject {
        put("id", message.id)
        put("threadId", message.threadId)
        put("from", message.from)
        put("to", message.to)
        put("subject", message.subject)
        put("sentAt", message.sentAt)
        put("state", message.state.name)
        put("read", message.read)
        put("preview", message.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(PREVIEW_CHARS) ?: "")
    }

    private fun ok(payload: JsonObject) = ToolResult(json.encodeToString(JsonObject.serializer(), payload))

    private fun refused(text: String) = ToolResult(text, isError = true)

    companion object {
        const val INBOX = "agenthq_inbox"
        const val READ = "agenthq_read"
        val NAMES = listOf(INBOX, READ)

        private const val HUMAN = "user"
        private const val PREVIEW_CHARS = 120

        private val json = Json { ignoreUnknownKeys = true }

        /** `tools/list` entries for the mail tools. */
        fun descriptors() = buildJsonArray {
            add(buildJsonObject {
                put("name", INBOX)
                put(
                    "description",
                    "List the AgentHQ mailbox mail addressed to you (this session, or the human handle outside one): " +
                        "id, from, subject, state, read, and a one-line preview, oldest first. Read one in full with $READ.",
                )
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("unreadOnly", buildJsonObject {
                            put("type", "boolean")
                            put("default", false)
                            put("description", "Only the messages not read yet.")
                        })
                    })
                    put("additionalProperties", false)
                })
                put("annotations", buildJsonObject { put("readOnlyHint", true) })
            })
            add(buildJsonObject {
                put("name", READ)
                put("description", "Read one message of your inbox in full (its body) and mark it read.")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("messageId", buildJsonObject {
                            put("type", "string")
                            put("description", "Message id from $INBOX, or a unique prefix of one.")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("messageId")) })
                    put("additionalProperties", false)
                })
                put("annotations", buildJsonObject {
                    put("readOnlyHint", false)
                    put("destructiveHint", false)
                    put("idempotentHint", true)
                    put("openWorldHint", false)
                })
            })
        }
    }
}
