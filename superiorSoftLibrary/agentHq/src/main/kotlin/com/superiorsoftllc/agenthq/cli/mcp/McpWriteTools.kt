package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.mailbox.MailRefused
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.mailbox.Message
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The one WRITE tool `agenthq mcp serve` still serves itself: `agenthq_message`. RUNTIME-LOAD-7 moved spawn, prompt, kill, restart, remove
 * and ticket start into Infantry_Agents / Infantry_Board's daemon halves over the host spawn service (docs/RUNTIME-LOAD.md section 13),
 * which now holds the depth, concurrency and kill-scope guardrails this class used to keep for the life of the server process.
 */
internal class McpWriteTools(
    private val getenv: (String) -> String?,
) {
    /**
     * Mail through the Infantry AI mailbox, exactly as `agenthq send` does it: the sender is the session named by
     * `AGENTHQ_SESSION_ID` (the daemon exports it into every spawned agent), else the human handle `user`;
     * the answer is the LAST `mail`/`mail_refused` frame before the `mail_outbox` fence.
     */
    suspend fun message(stream: FrameStream, arguments: JsonObject): ToolResult {
        val to = arguments.string("to") ?: return refused("agenthq_message needs a recipient (to)")
        val body = arguments.string("body") ?: return refused("agenthq_message needs a body")
        val session = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        val sender = session ?: HUMAN_SENDER
        val request = MailSendRequest(
            from = if (session == null) HUMAN_SENDER else "",
            to = to,
            subject = arguments.string("subject") ?: "",
            body = body,
            replyTo = arguments.string("replyTo"),
            allowCrossProject = (arguments["allowCrossProject"] as? JsonPrimitive)?.booleanOrNull == true,
        )
        val answer = stream.requestLastOf(
            Envelope(Envelope.MAIL_SEND, json.encodeToString(MailSendRequest.serializer(), request), session),
            answerTypes = setOf(Envelope.MAIL, Envelope.MAIL_REFUSED),
            fence = Envelope(Envelope.MAIL_OUTBOX, sender),
            fenceType = Envelope.MAILS,
            what = "mail_send",
            dropWarning = "Do not re-send unless a duplicate message is acceptable",
        )
        if (answer.type == Envelope.MAIL_REFUSED) {
            val reason = runCatching { json.decodeFromString(MailRefused.serializer(), answer.data).reason }.getOrNull()
            return refused("agenthq_message refused: ${reason ?: answer.data}")
        }
        val message = json.decodeFromString(Message.serializer(), answer.data)
        if (message.failed) return refused("agenthq_message: ${message.failureReason ?: "the message could not be delivered"}")
        return ok(buildJsonObject {
            put("messageId", message.id)
            put("threadId", message.threadId)
            put("from", message.from)
            put("to", message.to)
            put("state", message.state.name)
        })
    }

    private fun ok(payload: JsonObject) = ToolResult(json.encodeToString(JsonObject.serializer(), payload))

    private fun refused(text: String) = ToolResult(text, isError = true)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val MESSAGE = "agenthq_message"
        val NAMES = listOf(MESSAGE)

        private const val HUMAN_SENDER = "user"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        /** `tools/list` entries for the write tools. */
        fun descriptors() = buildJsonArray {
            add(tool(
                MESSAGE,
                "Send Infantry AI COMMS mail to an agent id, a role (of the sender's project) or a session id.",
                destructive = false,
                properties = {
                    put("to", prop("string", "Recipient: agent id, role, or session id."))
                    put("body", prop("string", "The message."))
                    put("subject", prop("string", "Optional subject."))
                    put("replyTo", prop("string", "Optional message id this answers."))
                    put("allowCrossProject", prop("boolean", "Send even though the recipient is in another project (refused by default)."))
                },
                required = listOf("to", "body"),
            ))
        }

        private fun prop(type: String, description: String) = buildJsonObject {
            put("type", type)
            put("description", description)
        }

        private fun tool(
            name: String,
            description: String,
            destructive: Boolean,
            properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
            required: List<String>,
        ) = buildJsonObject {
            put("name", name)
            put("description", description)
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject(properties))
                if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                put("additionalProperties", false)
            })
            put("annotations", buildJsonObject {
                put("readOnlyHint", false)
                put("destructiveHint", destructive)
                put("openWorldHint", false)
            })
        }
    }
}
