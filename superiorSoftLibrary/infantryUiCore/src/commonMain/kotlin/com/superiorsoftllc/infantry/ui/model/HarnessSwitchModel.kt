// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

/**
 * SWAP-AGENT-1: `/harness <name>` opens the one Switch agent picker with that harness pre-selected; Apply sends the swap.
 * CMD-HARNESS-1: `/harness` moves the selected agent onto another harness with the daemon's EXISTING `agent_swap`
 * (contract 1.47.0, session `auto`); UX-HARNESS-2: a session with no agent is switched in place with `session_swap`
 * (contract 1.83.0), and nothing is saved as an agent. It is a CLIENT command: the harness list is the daemon's `providers` frame
 * (availability from CLIPATH-1), so the box never asks the daemon's command catalog for it, and the line is never
 * sent as a prompt.
 */
object HarnessSwitch {
    const val COMMAND = "harness"

    /** The `/harness ` row the command list offers while the human types a command word that `harness` starts with. */
    fun commandRow(): SlashSuggestion =
        SlashSuggestion("/$COMMAND", "switch this session's agent: role, provider and model (the conversation carries over)", "/$COMMAND ")

    /**
     * What a `/harness ...` line names (blank for a bare `/harness`, which [plan] refuses with usage), or null when
     * [text] is not a `/harness` line at all.
     */
    fun parse(text: String): String? {
        val typed = slashTyped(text) ?: return null
        val word = typed.takeWhile { !it.isWhitespace() }
        if (!word.equals(COMMAND, ignoreCase = true)) return null
        return typed.substring(word.length).trim()
    }

    /** True while [text] is `/harness` followed by a space: the values (harnesses) are what is listed. */
    fun listingValues(text: String): Boolean {
        val typed = slashTyped(text) ?: return false
        val word = typed.takeWhile { !it.isWhitespace() }
        return word.equals(COMMAND, ignoreCase = true) && typed.length > word.length
    }

    /**
     * The rows for [text]: the harnesses whose id contains what follows `/harness ` (any part, ignoring case, as
     * CMD-MODEL-1 filters models). An unavailable harness is listed with the daemon's reason and cannot be picked;
     * no match is one row that says so. Null when [text] is not a `/harness ` line, so the caller keeps its own list.
     */
    fun suggestions(state: AppState, text: String, current: String?): List<SlashSuggestion>? {
        if (!listingValues(text)) return null
        val typed = slashTyped(text)!!
        val args = typed.substring(typed.takeWhile { !it.isWhitespace() }.length).trim()
        val rows = state.providers.filter { it.id.contains(args, ignoreCase = true) }.map { p ->
            val detail = when {
                !p.available -> "unavailable: ${p.unavailableReason ?: "the daemon cannot find its CLI"}"
                p.id == current -> "current"
                else -> p.defaultModel?.let { "default model $it" } ?: ""
            }
            SlashSuggestion(p.id, detail, "/$COMMAND ${p.id}", selectable = p.available)
        }
        return rows.ifEmpty { listOf(SlashSuggestion("No harness matches '$args'", "", text, selectable = false)) }
    }

    /** The title over the `/harness ` list. */
    const val HEADER = "harnesses"

    /**
     * What a switch sends. [agentId] non-null = a saved agent (`agent_swap`, session auto); null = a session with no
     * agent (UX-HARNESS-2 `session_swap`, [sessionId] in place). [model] null = the harness default. SWAP-AGENT-1:
     * [role] null = keep the session's role, blank = no role, else the role the session runs as from its next turn.
     */
    data class Plan(val sessionId: String, val agentId: String?, val harness: String, val model: String?, val role: String? = null)

    /**
     * SWAP-AGENT-1: the reason `/harness <name>` cannot open the Switch agent picker on [harness], or null when it can.
     * A harness the daemon reports as unavailable is refused with its reason; one it does not report at all is refused
     * only while the daemon has reported its harnesses (an empty list = not known yet: the picker decides).
     */
    fun refusal(state: AppState, harness: String): String? {
        val target = harness.trim()
        if (target.isEmpty()) return "name a provider to switch to: /$COMMAND <provider>"
        val info = state.providers.firstOrNull { it.id == target }
        if (info != null && !info.available) return unavailable(info.id, info.unavailableReason)
        if (info == null && state.providers.isNotEmpty()) return "There is no provider '${target.take(40)}' here. Pick one of: ${state.providers.joinToString { it.id }}."
        return null
    }

    /**
     * UX-HARNESS-2: the daemon's `session_swap refused: <reason>` in plain words ("Couldn't switch: <reason>"), or null
     * when [error] is not a session-switch refusal.
     */
    fun plainRefusal(error: String): String? {
        val reason = when {
            error.startsWith("session_swap refused: ") -> error.removePrefix("session_swap refused: ")
            error.startsWith("session_swap failed: ") -> error.removePrefix("session_swap failed: ")
            else -> return null
        }
        return "Couldn't switch: $reason"
    }

    /** A provider that cannot run, in plain words: what happened and what to do. */
    fun unavailable(id: String, reason: String?): String =
        "Can't switch to $id: ${reason ?: "it isn't installed on this computer"}. Install it, or pick another provider."

    /** [model] when [to] lists it (ExecutorPickerModel.withHarness's rule), else null: the new harness's default. */
    fun keptModel(state: AppState, from: String?, model: String?, to: String): String? {
        if (model == null) return null
        val catalog = ExecutorPickerModel.catalogWithProviderLists(state.providers, state.modelCatalog)
        val seeded = ExecutorPickerModel.seeded(state.providers, catalog, from?.let { PickedModel(it, model) })
            .let { if (it.harness == null) ExecutorPickerModel(state.providers, catalog, harness = from, model = model) else it }
        return seeded.withHarness(to).model
    }

    /**
     * The one-line notice after the daemon confirmed the switch: exactly "Switched to <agent name> · <provider> · <model>"
     * (SWAP-AGENT-1; no agent name, a session with no role or agent, = "Switched to <provider> · <model>"), naming the
     * model actually used ([model] null = the harness's default, named by [defaultModel] when the daemon reports one).
     * Nothing more: since HARNESS-CARRY-1 the conversation simply continues.
     */
    fun switchedNotice(harness: String, model: String?, defaultModel: String?, agentName: String? = null): String {
        val shown = model?.takeIf { it.isNotBlank() } ?: defaultModel?.takeIf { it.isNotBlank() } ?: "its default model"
        return "Switched to " + listOfNotNull(agentName?.takeIf { it.isNotBlank() }, harness, shown).joinToString(" · ")
    }
}

/** The `<harness> · <model>` header chip's text; the model is the harness default when the session names none. */
fun SessionExecutor.switchChipText(): String =
    listOfNotNull(harness ?: "harness?", fullModel ?: "default").joinToString(" · ")

/** SWAP-AGENT-1: open the Switch agent picker on [harness] for session [sessionId] (see [AppState.harnessPickerAsk]). */
data class HarnessPickerAsk(val sessionId: String, val harness: String, val seq: Long)

/** SWAP-AGENT-1: why the Switch agent picker's Apply is disabled while the session's turn is in flight (its tooltip). */
const val SWITCH_WAIT_FOR_TURN = "Wait for the current turn to finish"
