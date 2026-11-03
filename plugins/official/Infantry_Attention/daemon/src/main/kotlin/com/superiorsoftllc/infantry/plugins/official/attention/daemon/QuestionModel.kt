// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.attention.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** ATTN-2c: one answer option of an owner question. */
data class QuestionOption(val label: String, val description: String = "")

/** ATTN-2c: the wire states of an owner question (`OwnerQuestion.state`). */
object QuestionStates {
    const val OPEN = "open"
    const val ANSWERED = "answered"
    const val AUTO = "auto"
    const val CANCELLED = "cancelled"
}

/** ATTN-2c: who ended a question (`OwnerQuestion.resolvedBy`). */
object QuestionResolvedBy {
    const val OWNER = "owner"
    const val AUTO = "auto"
    const val ASKER = "asker"
    const val ORPHANED = "orphaned"
}

/** ATTN-2c: one owner question as stored and pushed (the `OwnerQuestion` record clients decode). Times are epoch ms. */
data class OwnerQuestion(
    val id: String,
    val projectId: String? = null,
    val sessionId: String,
    val agentId: String? = null,
    val role: String? = null,
    val question: String,
    val options: List<QuestionOption>,
    val recommended: String,
    val reason: String,
    val allowOther: Boolean = false,
    val defaultAfterMin: Int? = null,
    val state: String = QuestionStates.OPEN,
    val answer: String? = null,
    val otherText: String? = null,
    val resolvedBy: String? = null,
    val createdAt: Long,
    val resolvedAt: Long? = null,
    val overridden: Boolean = false,
)

/** `question_ask` payload. */
data class QuestionAsk(
    val question: String,
    val options: List<QuestionOption>,
    val recommended: String,
    val reason: String,
    val allowOther: Boolean = true,
    val defaultAfterMin: Int? = null,
) {
    companion object {
        const val QUESTION_MAX = 2_000
        const val REASON_MAX = 200
        const val OPTIONS_MAX = 8
        val DEFAULT_AFTER_MIN_RANGE: IntRange = 1..1_440
    }
}

/** `question_answer` payload: pick [answer] (an option label) and/or type [otherText]. */
data class QuestionAnswer(val questionId: String, val answer: String? = null, val otherText: String? = null)

/** The asking session as the daemon holds it, frozen on the question. */
data class Asker(val sessionId: String, val agentId: String? = null, val role: String? = null, val projectId: String? = null) {
    /** The rate-limit key: the agent outlives its sessions, so its id wins. */
    val address: String get() = agentId?.takeIf { it.isNotBlank() } ?: sessionId
}

/** ATTN-2c: the wire form of the question frames (same JSON as the typed records of infantryProtocol). */
internal object QuestionJson {
    private val json = Json

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun parseObject(text: String, what: String): JsonObject =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: throw IllegalArgumentException("$what: malformed payload")

    private fun strictKeys(o: JsonObject, allowed: Set<String>, what: String) {
        val extra = o.keys.firstOrNull { it !in allowed }
        require(extra == null) { "$what: unknown field '${extra!!.take(40)}'" }
    }

    fun decodeAsk(text: String): QuestionAsk {
        val o = parseObject(text, "question_ask payload")
        strictKeys(o, setOf("question", "options", "recommended", "reason", "allowOther", "defaultAfterMin"), "question_ask payload")
        val options = (o["options"] as? JsonArray)?.map { e ->
            val opt = e as? JsonObject ?: throw IllegalArgumentException("question_ask payload: each option is {label, description}")
            QuestionOption(
                opt.str("label") ?: throw IllegalArgumentException("question_ask payload: an option needs a label"),
                opt.str("description").orEmpty(),
            )
        } ?: throw IllegalArgumentException("question_ask payload: options is required")
        return QuestionAsk(
            question = o.str("question") ?: throw IllegalArgumentException("question_ask payload: question is required"),
            options = options,
            recommended = o.str("recommended") ?: throw IllegalArgumentException("question_ask payload: recommended is required"),
            reason = o.str("reason") ?: throw IllegalArgumentException("question_ask payload: reason is required"),
            allowOther = (o["allowOther"] as? JsonPrimitive)?.booleanOrNull ?: true,
            defaultAfterMin = (o["defaultAfterMin"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let {
                it.intOrNull ?: throw IllegalArgumentException("question_ask payload: defaultAfterMin must be an integer")
            },
        )
    }

    fun decodeAnswer(text: String): QuestionAnswer {
        val o = parseObject(text, "question_answer payload")
        strictKeys(o, setOf("questionId", "answer", "otherText"), "question_answer payload")
        return QuestionAnswer(
            o.str("questionId") ?: throw IllegalArgumentException("question_answer payload: questionId is required"),
            o.str("answer"),
            o.str("otherText"),
        )
    }

    fun decodeCancel(text: String): String {
        val o = parseObject(text, "question_cancel payload")
        strictKeys(o, setOf("questionId"), "question_cancel payload")
        return o.str("questionId") ?: throw IllegalArgumentException("question_cancel payload: questionId is required")
    }

    fun options(options: List<QuestionOption>): JsonArray = JsonArray(options.map {
        JsonObject(mapOf("label" to JsonPrimitive(it.label), "description" to JsonPrimitive(it.description)))
    })

    fun parseOptions(text: String): List<QuestionOption> =
        (json.parseToJsonElement(text) as JsonArray).map { e ->
            val o = e as JsonObject
            QuestionOption(o.str("label").orEmpty(), o.str("description").orEmpty())
        }

    private fun s(v: String?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull
    private fun n(v: Number?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull

    /** Every key present (as the typed encode with defaults), absent values as null. */
    fun question(q: OwnerQuestion): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(q.id),
            "projectId" to s(q.projectId),
            "sessionId" to JsonPrimitive(q.sessionId),
            "agentId" to s(q.agentId),
            "role" to s(q.role),
            "question" to JsonPrimitive(q.question),
            "options" to options(q.options),
            "recommended" to JsonPrimitive(q.recommended),
            "reason" to JsonPrimitive(q.reason),
            "allowOther" to JsonPrimitive(q.allowOther),
            "defaultAfterMin" to n(q.defaultAfterMin),
            "state" to JsonPrimitive(q.state),
            "answer" to s(q.answer),
            "otherText" to s(q.otherText),
            "resolvedBy" to s(q.resolvedBy),
            "createdAt" to JsonPrimitive(q.createdAt),
            "resolvedAt" to n(q.resolvedAt),
            "overridden" to JsonPrimitive(q.overridden),
        ),
    )

    fun questionText(q: OwnerQuestion): String = question(q).toString()

    fun listText(list: List<OwnerQuestion>): String = JsonArray(list.map(::question)).toString()
}
