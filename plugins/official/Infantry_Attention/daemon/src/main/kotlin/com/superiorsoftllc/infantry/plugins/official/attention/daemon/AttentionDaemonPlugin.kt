// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.attention.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import com.superiorsoftllc.infantry.daemon.api.attention.Attention
import com.superiorsoftllc.infantry.daemon.api.attention.AttentionEntry
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * RUNTIME-LOAD-3 (docs/RUNTIME-LOAD.md, F4): Infantry_Attention's daemon half. ASK-1's `infantry_ask`, the MCP form of
 * `infantry ask --recommend`, was built into the CLI's MCP server; now it sends the same `question_ask` frame on the caller's
 * connection and returns the question id AT ONCE (the owner's pick arrives as mail in thread `question:<id>`). The asker is the
 * session the MCP process runs in ([ToolCall.clientSessionId], `INFANTRY_SESSION_ID`); outside a session there is nobody to
 * deliver the answer to. Same name, schema, answer and gating (`questions.ask`).
 */
class AttentionDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(AskTool)
        // ATTN-2b: the attention store, service, frames and `GET /attention` live here; core only looks the service up.
        val store = AttentionStore(api.storage)
        store.eventSink = { api.events.broadcast("attention", AttentionJson.itemText(it)) }
        // BRIDGE-EVENTS-1: scripts raise items too (the restart summary is a script now).
        api.tools.register(AttentionRaiseTool { kind, ticketId, sessionId, outcome, detail -> store.raise(kind, null, ticketId, null, sessionId, outcome, detail).id })
        api.services.provide(Attention::class, object : Attention {
            override fun raise(kind: String, triggerId: String?, ticketId: String?, podId: String?, sessionId: String?, outcome: String, detail: String): String? =
                store.raise(kind, triggerId, ticketId, podId, sessionId, outcome, detail).id

            override fun resolve(id: String): Boolean = store.acknowledge(id) != null
            override fun list(project: String?): List<AttentionEntry> = store.list()
        })
        api.frames.handle("attention_list") { _, _ -> Frame("attention_items", AttentionJson.listText(store.list())) }
        api.frames.handle("attention_ack") { frame, _ ->
            val id = frame.data.trim()
            when {
                id.isEmpty() -> Frame("error", "attention_ack expects an attention id")
                store.acknowledge(id) == null -> Frame("error", "unknown attention item '$id'")
                else -> null
            }
        }
        // ATTN-2c: the owner questions (store, sweep, frames, mail, connect replay) live here too.
        QuestionsWiring.install(api)
        api.routes.handle("GET", "/attention") { HttpReply(200, AttentionJson.listText(store.list()), "application/json") }
    }
}

object AskTool : DaemonTool {
    const val NAME = "infantry_ask"
    private const val ANSWER_TIMEOUT_MS = 45_000L
    /** `QuestionAsk` limits of the wire contract (infantryProtocol): the schema states them, the daemon enforces them. */
    private const val QUESTION_MAX = 2_000
    private const val REASON_MAX = 200
    private const val OPTIONS_MAX = 8
    private const val DEFAULT_AFTER_MIN_FIRST = 1
    private const val DEFAULT_AFTER_MIN_LAST = 1_440
    private val json = Json { ignoreUnknownKeys = true }

    override val name = NAME
    override val description =
        "USE THIS when a decision has 2-4 real options and the owner's preference matters (design, priority, destructive or irreversible choices); the owner wants it used for those. Do NOT use it for trivia or anything already decided. Ask the owner to choose, WITHOUT waiting. Give 2-8 options (label + one-line consequence), exactly one " +
            "recommended option and why. Returns a questionId at once: carry on with other work; the owner's pick " +
            "arrives as mail in thread question:<id>. Optional defaultAfterMin auto-picks your recommendation if " +
            "the owner has not answered by then (off unless set). The owner may answer with their own text instead of an option: that mail reads \"Custom answer from the owner: <text>\" and is NOT one of your options. Rate limited per agent."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("question", buildJsonObject {
                put("type", "string")
                put("description", "The question, at most $QUESTION_MAX characters.")
            })
            put("options", buildJsonObject {
                put("type", "array")
                put("minItems", 2)
                put("maxItems", OPTIONS_MAX)
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("label", buildJsonObject { put("type", "string") })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "One line: what happens if the owner picks this.")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("label")) })
                    put("additionalProperties", false)
                })
            })
            put("recommended", buildJsonObject {
                put("type", "string")
                put("description", "The label of the ONE option you recommend.")
            })
            put("reason", buildJsonObject {
                put("type", "string")
                put("description", "Why you recommend it, at most $REASON_MAX characters.")
            })
            put("allowOther", buildJsonObject {
                put("type", "boolean")
                put("default", true)
                put("description", "The owner can always answer with their own text (\"Other\"); set false ONLY for a question that must stay option-only.")
            })
            put("defaultAfterMin", buildJsonObject {
                put("type", "integer")
                put("minimum", DEFAULT_AFTER_MIN_FIRST)
                put("maximum", DEFAULT_AFTER_MIN_LAST)
                put("description", "Auto-pick the recommended option after this many minutes unanswered. Omit = wait.")
            })
        })
        put("required", buildJsonArray { listOf("question", "options", "recommended", "reason").forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
    override val annotations = buildJsonObject {
        put("readOnlyHint", false)
        put("destructiveHint", false)
        put("idempotentHint", false)
        put("openWorldHint", false)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun refused(text: String) = ToolResult(text, isError = true)

    override fun call(call: ToolCall): ToolResult {
        val arguments = call.arguments
        val session = call.clientSessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return refused("$NAME needs the asking session in INFANTRY_SESSION_ID (set for every session Infantry AI spawns)")
        val options = (arguments["options"] as? JsonArray)?.map { element ->
            val option = element as? JsonObject ?: return refused("$NAME: each option is {label, description}")
            buildJsonObject {
                put("label", option.string("label") ?: return refused("$NAME: an option needs a label"))
                put("description", option.string("description").orEmpty())
            }
        } ?: return refused("$NAME needs options: [{label, description}, ...]")
        // The frame is `QuestionAsk` (infantryProtocol): defaults left out, as the old typed encode left them out.
        val request = buildJsonObject {
            put("question", arguments.string("question") ?: return refused("$NAME needs a question"))
            put("options", JsonArray(options))
            put("recommended", arguments.string("recommended") ?: return refused("$NAME needs recommended (one option's label)"))
            put("reason", arguments.string("reason") ?: return refused("$NAME needs a reason for the recommendation"))
            val allowOther = (arguments["allowOther"] as? JsonPrimitive)?.booleanOrNull != false
            if (!allowOther) put("allowOther", false)
            (arguments["defaultAfterMin"] as? JsonPrimitive)?.intOrNull?.let { put("defaultAfterMin", it) }
        }
        return try {
            val reply = call.request("question_ask", json.encodeToString(JsonObject.serializer(), request), sessionId = session, timeoutMs = ANSWER_TIMEOUT_MS)
            when (reply.type) {
                "question_asked" -> {
                    val asked = json.parseToJsonElement(reply.data) as? JsonObject ?: return refused("question_ask: malformed answer")
                    ToolResult(json.encodeToString(JsonObject.serializer(), buildJsonObject {
                        put("questionId", asked.string("questionId").orEmpty())
                        put("note", asked.string("note") ?: NON_BLOCKING_NOTE)
                    }))
                }
                "error" -> refused(reply.data.ifBlank { "question_ask failed" })
                else -> refused("question_ask: unexpected answer ${reply.type}")
            }
        } catch (e: Exception) {
            refused(e.message ?: "question_ask failed")
        }
    }

    /** `QuestionAsked.NON_BLOCKING_NOTE`, the default the daemon leaves out of the frame. */
    private const val NON_BLOCKING_NOTE =
        "non-blocking: carry on with other work; the owner's answer arrives as mail in thread question:<id>"
}
