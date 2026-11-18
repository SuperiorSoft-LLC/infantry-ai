// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.HarnessAnswer
import com.superiorsoftllc.infantry.harness.api.HarnessQuestion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * ASK-STUCK-1 (ticket c2d09882): Claude's `AskUserQuestion` becomes an Infantry prompt card in every Tool approval mode.
 *
 * The interactive claude draws AskUserQuestion as its own menu in the hidden terminal. With allowAll no permission hook exists, and with
 * one the call reaches [ClaudePermissionHook] only as a yes/no approval; either way the menu waits for keys nobody can send, so the turn
 * stalls until someone types. This `PreToolUse` hook (matcher `AskUserQuestion`, served by [PermissionHookServer]) asks each question
 * through [com.superiorsoftllc.infantry.harness.api.HarnessHost.ask] (the owner's card, blocking) and answers the call with
 * `permissionDecision: allow` plus `updatedInput.answers` (question text -> chosen label(s) or the owner's own text), the way an SDK host
 * answers the tool, so the menu is never drawn. A question nobody answered (timeout, session end) denies the call with a reason the
 * model reads, so the turn goes on instead of waiting on the menu.
 */
object ClaudeQuestionHook {
    const val EVENT = "PreToolUse"
    const val TOOL = "AskUserQuestion"
    const val PATH = "/question/"

    /** One question of the tool's input. */
    data class Question(val text: String, val header: String, val options: List<Pair<String, String>>, val multiSelect: Boolean)

    /** The settings entry (only AskUserQuestion). */
    fun entry(url: String, timeoutSec: Int): JsonObject = JsonObject(
        mapOf(
            "matcher" to JsonPrimitive(TOOL),
            "hooks" to JsonArray(listOf(JsonObject(mapOf(
                "type" to JsonPrimitive("http"),
                "url" to JsonPrimitive(url),
                "timeout" to JsonPrimitive(timeoutSec),
            )))),
        ),
    )

    /** The call's tool input and its questions; null when the body is not an AskUserQuestion call with at least one question. */
    fun parse(body: String): Pair<JsonObject, List<Question>>? {
        val root = runCatching { TtyJson.json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if ((root["tool_name"] as? JsonPrimitive)?.contentOrNull != TOOL) return null
        val input = root["tool_input"] as? JsonObject ?: return null
        val questions = (input["questions"] as? JsonArray)?.mapNotNull { q ->
            val o = q as? JsonObject ?: return@mapNotNull null
            val text = o.str("question")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val options = (o["options"] as? JsonArray)?.mapNotNull { opt ->
                val oo = opt as? JsonObject ?: return@mapNotNull null
                val label = oo.str("label")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                label to oo.str("description").orEmpty().trim()
            }.orEmpty().distinctBy { it.first }
            Question(text, o.str("header").orEmpty().trim(), options, (o["multiSelect"] as? JsonPrimitive)?.contentOrNull == "true")
        }.orEmpty()
        return if (questions.isEmpty()) null else input to questions
    }

    /**
     * The card for [q]: the header and each option's description go into the question text (the card shows labels only). ASK-OTHER-1:
     * the card always has an "Other" field and is multi-select when the tool's question is. The one label marked "(Recommended)" is the
     * timer's pick.
     */
    fun harnessQuestion(q: Question, timeoutMillis: Long? = null): HarnessQuestion {
        val text = buildString {
            if (q.header.isNotEmpty()) append('[').append(q.header).append("] ")
            append(q.text.trim())
            val described = q.options.filter { it.second.isNotEmpty() }
            if (described.isNotEmpty()) {
                append("\n")
                for ((label, desc) in described) append("\n- ").append(label).append(": ").append(desc)
            }
        }
        val recommended = q.options.map { it.first }.filter { RECOMMENDED.containsMatchIn(it) }.singleOrNull()
        return HarnessQuestion(text, q.options.map { it.first }, recommended, timeoutMillis, allowOther = true, multiSelect = q.multiSelect)
    }

    /** The answer as AskUserQuestion takes it (labels comma separated, then the owner's own text); null = not answered. */
    fun answerText(a: HarnessAnswer): String? {
        if (!a.answered) return null
        val parts = a.answers.map { it.trim() }.filter { it.isNotEmpty() } + listOfNotNull(a.otherText?.trim()?.takeIf { it.isNotEmpty() })
        return parts.joinToString(", ").takeIf { it.isNotEmpty() }
    }

    const val NO_ANSWER = "The owner did not answer this question in Infantry AI (it timed out or the session is ending). " +
        "Do not ask it again right away: carry on with your best judgement and say what you assumed."

    /** The hook's answer to one call; [ask] puts one question to the owner (blocking). */
    fun decide(body: String, ask: (HarnessQuestion) -> HarnessAnswer): String {
        val (input, questions) = parse(body) ?: return deny("unreadable AskUserQuestion call; ask the owner in plain text instead")
        val answers = LinkedHashMap<String, JsonPrimitive>()
        for (q in questions) {
            val text = answerText(runCatching { ask(harnessQuestion(q)) }.getOrDefault(HarnessAnswer.NONE)) ?: return deny(NO_ANSWER)
            answers[q.text] = JsonPrimitive(text)
        }
        return allow(JsonObject(input + ("answers" to JsonObject(answers))))
    }

    fun allow(updatedInput: JsonObject): String = output(
        "permissionDecision" to JsonPrimitive("allow"),
        "updatedInput" to updatedInput,
    )

    fun deny(reason: String): String = output(
        "permissionDecision" to JsonPrimitive("deny"),
        "permissionDecisionReason" to JsonPrimitive(reason),
    )

    private fun output(vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>): String =
        JsonObject(mapOf("hookSpecificOutput" to JsonObject(mapOf("hookEventName" to JsonPrimitive(EVENT)) + fields))).toString()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private val RECOMMENDED = Regex("""\(\s*recommended\s*\)""", RegexOption.IGNORE_CASE)
}
