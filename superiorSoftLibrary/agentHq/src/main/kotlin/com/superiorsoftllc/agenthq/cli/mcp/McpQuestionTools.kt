package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.PromptOption
import com.superiorsoftllc.agenthq.protocol.QuestionAsk
import com.superiorsoftllc.agenthq.protocol.QuestionAsked
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
 * ASK-1: `agenthq_ask`, the MCP form of `agenthq ask --recommend`. It sends the same `question_ask` frame and returns
 * the question id AT ONCE: the agent carries on, and the owner's pick arrives as mail in thread `question:<id>`. The
 * asker is the session in `AGENTHQ_SESSION_ID`; outside a session there is nobody to deliver the answer to.
 */
internal class McpQuestionTools(private val getenv: (String) -> String?) {

    suspend fun ask(stream: FrameStream, arguments: JsonObject): ToolResult {
        val session = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return refused("$ASK needs the asking session in ${AccessPolicy.SESSION_ID_ENV} (set for every session Infantry AI spawns)")
        val options = (arguments["options"] as? JsonArray)?.map { element ->
            val option = element as? JsonObject ?: return refused("$ASK: each option is {label, description}")
            PromptOption(
                label = option.string("label") ?: return refused("$ASK: an option needs a label"),
                description = option.string("description").orEmpty(),
            )
        } ?: return refused("$ASK needs options: [{label, description}, ...]")
        val request = QuestionAsk(
            question = arguments.string("question") ?: return refused("$ASK needs a question"),
            options = options,
            recommended = arguments.string("recommended") ?: return refused("$ASK needs recommended (one option's label)"),
            reason = arguments.string("reason") ?: return refused("$ASK needs a reason for the recommendation"),
            allowOther = (arguments["allowOther"] as? JsonPrimitive)?.booleanOrNull == true,
            defaultAfterMin = (arguments["defaultAfterMin"] as? JsonPrimitive)?.intOrNull,
        )
        val answer = try {
            stream.request(
                Envelope(Envelope.QUESTION_ASK, json.encodeToString(QuestionAsk.serializer(), request), session),
                Envelope.QUESTION_ASKED, "question_ask", idempotent = false,
            )
        } catch (e: Exception) {
            return refused(e.message ?: "question_ask failed")
        }
        val asked = json.decodeFromString(QuestionAsked.serializer(), answer.data)
        return ToolResult(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("questionId", asked.questionId)
            put("note", asked.note)
        }))
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun refused(text: String) = ToolResult(text, isError = true)

    companion object {
        const val ASK = "agenthq_ask"
        val NAMES = listOf(ASK)

        private val json = Json { ignoreUnknownKeys = true }

        /** `tools/list` entry for `agenthq_ask`. */
        fun descriptors() = buildJsonArray {
            add(buildJsonObject {
                put("name", ASK)
                put(
                    "description",
                    "Ask the owner to choose, WITHOUT waiting. Give 2-8 options (label + one-line consequence), exactly one " +
                        "recommended option and why. Returns a questionId at once: carry on with other work; the owner's pick " +
                        "arrives as mail in thread question:<id>. Optional defaultAfterMin auto-picks your recommendation if " +
                        "the owner has not answered by then (off unless set). Rate limited per agent.",
                )
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("question", buildJsonObject {
                            put("type", "string")
                            put("description", "The question, at most ${QuestionAsk.QUESTION_MAX} characters.")
                        })
                        put("options", buildJsonObject {
                            put("type", "array")
                            put("minItems", 2)
                            put("maxItems", QuestionAsk.OPTIONS_MAX)
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
                            put("description", "Why you recommend it, at most ${QuestionAsk.REASON_MAX} characters.")
                        })
                        put("allowOther", buildJsonObject {
                            put("type", "boolean")
                            put("default", false)
                            put("description", "Let the owner type an answer of their own.")
                        })
                        put("defaultAfterMin", buildJsonObject {
                            put("type", "integer")
                            put("minimum", QuestionAsk.DEFAULT_AFTER_MIN_RANGE.first)
                            put("maximum", QuestionAsk.DEFAULT_AFTER_MIN_RANGE.last)
                            put("description", "Auto-pick the recommended option after this many minutes unanswered. Omit = wait.")
                        })
                    })
                    put("required", buildJsonArray { listOf("question", "options", "recommended", "reason").forEach { add(JsonPrimitive(it)) } })
                    put("additionalProperties", false)
                })
                put("annotations", buildJsonObject {
                    put("readOnlyHint", false)
                    put("destructiveHint", false)
                    put("idempotentHint", false)
                    put("openWorldHint", false)
                })
            })
        }
    }
}
