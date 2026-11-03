package com.superiorsoftllc.infantry.plugins.official.triggers

import com.superiorsoftllc.infantry.ui.*

import com.superiorsoftllc.infantry.ui.protocol.TriggerView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The schedule floor the daemon enforces (`TriggerService.MIN_INTERVAL_MS`): one minute. */
// S4 (docs/SHELL-LEAKS.md): shell API, TriggerDraft's lower bound, shown by the trigger form.
const val MIN_TRIGGER_INTERVAL_MINUTES = 1L

/** A schedule interval typed in whole minutes, at least [MIN_TRIGGER_INTERVAL_MINUTES]; null when it is not one. */
internal fun parseIntervalMinutes(text: String): Long? =
    text.trim().toLongOrNull()?.takeIf { it >= MIN_TRIGGER_INTERVAL_MINUTES && it <= Long.MAX_VALUE / 60_000L }?.let { it * 60_000L }

/**
 * TRIGGER-NUDGE (contract 1.105.0): the trigger form's state, kept apart from the composable so its rules are tested.
 * [kind] is `manual` or `schedule`; [action] is `start_next` (the next ticket, optionally only [agentId]'s) or
 * `prompt` (mail [prompt] to [agentId] -- any `role:<role>` or a profile -- in [projectId]). Nothing is preset: the
 * target, the interval (any whole number of minutes, at least one) and the prompt are the owner's to type or pick.
 *
 * An edit starts from the trigger as stored ([from]), so saving it never changes its kind or its interval unless the
 * owner changed them in the form.
 */
// S4 (docs/SHELL-LEAKS.md): shell API, the input of the public Controller.saveTrigger.
data class TriggerDraft(
    val name: String = "",
    val kind: String = "manual",
    val action: String = "start_next",
    val agentId: String? = null,
    val prompt: String = "",
    val projectId: String? = null,
    val intervalMinutes: String = "",
    val coalesceMinutes: String = "0",
    val ceiling: String = "6",
    val enabled: Boolean = true,
) {
    val isSchedule: Boolean get() = kind == "schedule"
    val isPrompt: Boolean get() = action == "prompt"
    val intervalMs: Long? get() = parseIntervalMinutes(intervalMinutes)
    val coalesceMs: Long? get() = minutesToMs(coalesceMinutes)
    val ceilingRuns: Int? get() = ceiling.trim().toIntOrNull()?.takeIf { it > 0 }

    /** The first thing that keeps this draft from being saved, worded for the owner; null = it can be saved. */
    fun problem(): String? = when {
        name.isBlank() -> "Give the trigger a name."
        isSchedule && intervalMs == null -> "The interval is a whole number of minutes, at least $MIN_TRIGGER_INTERVAL_MINUTES."
        isPrompt && agentId.isNullOrBlank() -> "Pick who receives the prompt."
        isPrompt && prompt.isBlank() -> "Write the prompt to send."
        isPrompt && prompt.length > MAX_PROMPT_CHARS -> "The prompt is longer than $MAX_PROMPT_CHARS characters."
        coalesceMs == null -> "The coalesce window is a number of minutes (0 = none)."
        ceilingRuns == null -> "Max starts per hour is a whole number, at least 1."
        else -> null
    }

    /** The `trigger_save` payload ([id] null = create). Only valid when [problem] is null. */
    fun toSave(id: String?): JsonObject = buildJsonObject {
        if (id != null) put("id", id)
        put("name", name.trim())
        put("kind", kind)
        put("enabled", enabled)
        put("coalesceMs", coalesceMs ?: 0L)
        put("ceilingRuns", ceilingRuns ?: 6)
        if (isSchedule) intervalMs?.let { put("intervalMs", it) }
        if (isPrompt) projectId?.takeIf { it.isNotBlank() }?.let { put("projectId", it) }
        putJsonObject("action") {
            put("kind", action)
            agentId?.takeIf { it.isNotBlank() }?.let { put("agentId", it) }
            if (isPrompt) put("prompt", prompt.trim())
        }
    }

    companion object {
        const val MAX_PROMPT_CHARS = 4000

        /** The form for [existing] as stored, or a blank one; a new prompt trigger defaults to [activeProjectId]. */
        fun from(existing: TriggerView?, activeProjectId: String?): TriggerDraft = if (existing == null) {
            TriggerDraft(projectId = activeProjectId)
        } else {
            TriggerDraft(
                name = existing.name,
                kind = existing.kind,
                action = existing.action.kind,
                agentId = existing.action.agentId?.takeIf { it.isNotBlank() },
                prompt = existing.action.prompt.orEmpty(),
                projectId = existing.projectId,
                intervalMinutes = existing.intervalMs?.let { (it / 60_000L).coerceAtLeast(MIN_TRIGGER_INTERVAL_MINUTES).toString() }.orEmpty(),
                coalesceMinutes = msToMinutesText(existing.coalesceMs),
                ceiling = existing.ceilingRuns.toString(),
                enabled = existing.enabled,
            )
        }
    }
}
