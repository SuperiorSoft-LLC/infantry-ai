// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.attention.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.prompts.OpenPrompt
import com.superiorsoftllc.infantry.daemon.api.prompts.OpenPrompts
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * SPAWN-PARAMS-1 (owner 2026-11-12: "Tool, pm/owner only"): `infantry_prompt_answer` (script alias `prompt_answer`). A script or an
 * agent lists the approval cards open on a session, or answers one with the SAME `prompt_response` frame the app's card sends (so the
 * `approvals.answer` gate and PendingPrompts' own checks apply). Who may answer: the owner (a project hook's owner-scoped script only
 * inside its project), or a pm session of the target session's project; never the session that asked (no self-approval).
 */
class PromptAnswerTool(private val api: DaemonApi) : DaemonTool {
    override val name = NAME
    override val description =
        "List the approval cards / questions open on a session (no decision), or answer one as the app's card does: decision allow_once, " +
            "allow_session, allow_always or deny for a tool approval, answers for a question. promptId omitted = the session's oldest open " +
            "prompt. Owner or a pm of the session's project only, never the asking session itself."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("sessionId", prop("The session whose card to list or answer (full id)."))
            put("promptId", prop("The card to answer; omitted = the session's oldest open one."))
            put("decision", prop("Tool approval: allow_once | allow_session | allow_always | deny. Omitted (and no answers) = list only."))
            put("answers", buildJsonObject {
                put("type", "array"); put("items", buildJsonObject { put("type", "string") })
                put("description", "Question: the chosen option label(s).")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("sessionId")) })
        put("additionalProperties", false)
    }
    override val annotations = buildJsonObject {
        put("readOnlyHint", false); put("destructiveHint", false); put("idempotentHint", false); put("openWorldHint", false)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun refused(text: String) = ToolResult("$NAME refused: $text", isError = true)

    override fun call(call: ToolCall): ToolResult {
        val a = call.arguments
        val sessionId = a.string("sessionId") ?: return refused("sessionId is required")
        val prompts = api.services.find(OpenPrompts::class) ?: return refused("this daemon publishes no open prompts")
        val caller = call.caller.copy(activeSession = call.clientSessionId ?: call.caller.activeSession)
        val target = when (val r = api.spawns.target(sessionId, caller)) {
            is SessionsResult.Ok -> r.value
            is SessionsResult.Missing -> return refused(r.text)
            is SessionsResult.Refused -> return refused(r.reason)
            is SessionsResult.Unavailable -> return refused(r.reason)
        }
        who(caller, target.id, target.projectId)?.let { return refused(it) }
        val open = prompts.open(target.id)
        val decision = a.string("decision")
        val answers = (a["answers"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (decision == null && answers.isEmpty()) return ToolResult(listing(target.id, open).toString())
        val wanted = a.string("promptId")
        val prompt = (if (wanted != null) open.firstOrNull { it.promptId == wanted } else open.firstOrNull())
            ?: return refused(if (wanted != null) "prompt '$wanted' is not open on session ${target.id}" else "session ${target.id} has no open prompt")
        if (decision != null && decision !in prompt.options && prompt.kind == TOOL_APPROVAL) {
            return refused("unknown decision '${decision.take(40)}' (known: ${prompt.options.joinToString()})")
        }
        val data = buildJsonObject {
            put("promptId", prompt.promptId)
            decision?.let { put("decision", it) }
            if (answers.isNotEmpty()) put("answers", buildJsonArray { answers.forEach { add(JsonPrimitive(it)) } })
        }.toString()
        // The daemon answers a refused prompt_response with an `error` frame and an accepted one with NOTHING (the app reads the
        // card's close from the broadcast). So a request that times out (an `error` frame or an exception, by host) is the success
        // path when the card is gone: the open-card list decides, a still-open card refuses with what the daemon said.
        val reply = try {
            call.request(FRAME, data, null, SETTLE_MS)
        } catch (e: Exception) {
            null.also { if (stillOpen(target.id, prompt.promptId)) return refused("the card ${prompt.promptId} is still open after the answer (${e.message ?: e.javaClass.simpleName})") }
        }
        if (reply?.type == "error" && stillOpen(target.id, prompt.promptId)) return refused(reply.data.removePrefix("$FRAME refused: "))
        return ToolResult(buildJsonObject {
            put("sessionId", target.id); put("promptId", prompt.promptId); put("kind", prompt.kind)
            prompt.tool?.let { put("tool", it) }
            decision?.let { put("decision", it) }
            put("answered", true)
        }.toString())
    }

    private fun stillOpen(sessionId: String, promptId: String) = api.services.find(OpenPrompts::class)?.open(sessionId).orEmpty().any { it.promptId == promptId }

    /** The pm/owner rule; null = allowed. */
    private fun who(caller: com.superiorsoftllc.infantry.daemon.api.Caller, targetId: String, targetProject: String?): String? {
        val own = caller.activeSession?.trim()?.takeIf { it.isNotEmpty() }
        if (own == null) {
            if (!caller.owner) return "only the owner or a pm may answer a card"
            val scope = caller.project ?: return null
            return if (scope == targetProject) null else "session $targetId belongs to project '${targetProject ?: "none"}', not this script's project '$scope'"
        }
        if (own == targetId) return "session $targetId is this session itself; a session never answers its own approval card"
        val me = (api.spawns.target(own, caller) as? SessionsResult.Ok)?.value ?: return "session '$own' is unknown to this daemon"
        if (me.role?.removePrefix("role:") != PM) return "only the owner or a pm may answer a card; this session's role is '${me.role ?: "none"}'"
        if (me.projectId != targetProject) return "session $targetId belongs to project '${targetProject ?: "none"}', not this pm's project '${me.projectId ?: "none"}'"
        return null
    }

    private fun listing(sessionId: String, open: List<OpenPrompt>) = buildJsonObject {
        put("sessionId", sessionId)
        put("open", buildJsonArray {
            open.forEach { p ->
                add(buildJsonObject {
                    put("promptId", p.promptId); put("kind", p.kind); put("createdAt", p.createdAt)
                    p.tool?.let { put("tool", it) }; p.input?.let { put("input", it) }; p.question?.let { put("question", it) }
                    put("options", buildJsonArray { p.options.forEach { add(JsonPrimitive(it)) } })
                })
            }
        })
    }

    private fun prop(description: String) = buildJsonObject { put("type", "string"); put("description", description) }

    companion object {
        const val NAME = "infantry_prompt_answer"
        const val FRAME = "prompt_response"
        const val TOOL_APPROVAL = "tool_approval"
        const val PM = "pm"
        /** How long to wait for a refusal frame before the open-card list decides (an accepted answer sends no frame back). */
        const val SETTLE_MS = 3_000L
    }
}
