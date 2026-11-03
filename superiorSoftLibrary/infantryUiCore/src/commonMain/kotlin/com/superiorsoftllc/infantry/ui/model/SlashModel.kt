package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.CommandCatalogAnswer

/** CP-4: a `command_catalog` answer and the session (null = the daemon's default provider) it was asked for. */
data class SlashAnswer(val sessionId: String?, val answer: CommandCatalogAnswer)

/**
 * One row of the slash autocomplete: what it shows and the exact text the box takes when it is picked. A row that is
 * not [selectable] only informs (CMD-MODEL-1's "No model matches" row): it is never picked by Enter, Tab or a click.
 */
data class SlashSuggestion(val label: String, val detail: String, val replacement: String, val selectable: Boolean = true)

/** True when [text] is a `/` command: it is never sent to the agent as a prompt, so it is not a chat line either. */
fun isSlashCommand(text: String): Boolean = slashTyped(text) != null

/** The text after the leading slash as typed so far, or null when [text] is not a command (CP-4: a leading `/` is a command). */
fun slashTyped(text: String): String? = text.trimStart().takeIf { it.startsWith("/") }?.removePrefix("/")

/**
 * The rows to offer for [text] in [sessionId]'s box; empty unless the last answer is for exactly this session and this
 * text (and, with no session, for the picked [provider]). Without a space the rows are command names (picking one that takes an argument types `/name ` so the next
 * answer lists its values); with a space they are the named command's values (picking one completes `/name value`).
 */
fun slashSuggestions(state: AppState, sessionId: String?, text: String, provider: String? = null): List<SlashSuggestion> {
    val typed = slashTyped(text) ?: return emptyList()
    val held = heldAnswer(state, sessionId, typed, provider) ?: return emptyList()
    return if (typed.none { it.isWhitespace() }) {
        held.answer.commands.map { c ->
            SlashSuggestion(
                label = "/${c.name}" + (c.argument?.let { " $it" } ?: ""),
                detail = c.description,
                replacement = "/${c.name}" + if (c.argument != null) " " else "",
            )
        }
    } else {
        val args = slashArgs(typed)
        val rows = held.answer.commands.flatMap { c ->
            // /model with no suggestions of its own lists the daemon's `models` catalog for THIS provider (never hardcoded).
            val values = if (c.action == "SET_MODEL") (c.values + catalogModelsFor(state, held.answer.provider)).distinct() else c.values
            // CMD-MODEL-1: everything after the space filters, case-insensitive, by any part of the value. A model's
            // id is also its label here (the catalog lists ids), so one test covers both. The daemon already narrowed
            // the values by prefix; a substring test is a superset of that, so it never hides what the daemon sent.
            values.filter { it.contains(args, ignoreCase = true) }
                .map { v -> SlashSuggestion(v, c.description, "/${c.name} $v") }
        }
        if (rows.isEmpty() && args.isNotEmpty() && held.answer.commands.any { it.action == "SET_MODEL" }) {
            listOf(SlashSuggestion("No model matches '$args'", "", text, selectable = false))
        } else rows
    }
}

/**
 * CMD-MODEL-1: the title over the list while `/model ` values are offered, naming whose models they are
 * (e.g. "claude models"); null for every other list. CMD-HARNESS-1 builds on this header.
 */
fun slashHeader(state: AppState, sessionId: String?, text: String, provider: String? = null): String? {
    val typed = slashTyped(text)?.takeIf { t -> t.any { it.isWhitespace() } } ?: return null
    val held = heldAnswer(state, sessionId, typed, provider) ?: return null
    return if (held.answer.commands.any { it.action == "SET_MODEL" }) "${held.answer.provider} models" else null
}

/**
 * CMD-MODEL-1: the row Enter should pick AND send at once, or null. Only when the typed value narrowed the list to
 * exactly one selectable row (e.g. `/model haik` -> `/model haiku`): there is no choice left to make.
 */
fun slashSubmitOnEnter(rows: List<SlashSuggestion>, text: String): SlashSuggestion? {
    val typed = slashTyped(text) ?: return null
    if (typed.none { it.isWhitespace() }) return null
    return rows.singleOrNull()?.takeIf { it.selectable }
}

/** The text after the command word: what filters that command's values. */
private fun slashArgs(typed: String): String = typed.removePrefix(typed.takeWhile { !it.isWhitespace() }).trim()

/** The last answer when it is for exactly this session and this text (and, with no session, the picked [provider]). */
private fun heldAnswer(state: AppState, sessionId: String?, typed: String, provider: String?): SlashAnswer? {
    val held = state.slash ?: return null
    if (held.sessionId != sessionId || held.answer.prefix != typed) return null
    // No session (the New agent box): the catalog must be the picked provider's, not one asked before the pick changed.
    if (sessionId == null && provider != null && held.answer.provider != provider) return null
    return held
}

/** CP-4(e): what a `/` line typed in the New agent box means; it never becomes a task prompt. */
sealed interface SpawnSlash {
    /** Nothing is spawned or changed; [reason] is shown. */
    data class Refuse(val reason: String) : SpawnSlash

    /** The next spawn runs on [model] of [provider]. */
    data class SetModel(val provider: String, val model: String) : SpawnSlash

    /** The next spawn starts with reasoning effort [level]. */
    data class SetEffort(val level: String) : SpawnSlash
}

/**
 * CP-4(e): decide the spawn-box line [text] against the picked provider's full catalog [answer]. Session-scoped
 * `SET_MODEL` / `SET_EFFORT` become the spawn's selection; `NATIVE` (e.g. `/compact`) needs a session, so it is
 * refused; an unknown name lists the known commands.
 */
fun spawnSlash(text: String, answer: CommandCatalogAnswer): SpawnSlash {
    val typed = slashTyped(text) ?: return SpawnSlash.Refuse("not a command")
    val name = typed.takeWhile { !it.isWhitespace() }
    val args = typed.removePrefix(name).trim()
    val command = answer.commands.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: return SpawnSlash.Refuse(
            "unknown command '/$name' for provider '${answer.provider}'; known commands: " +
                answer.commands.joinToString(", ") { "/${it.name}" }.ifEmpty { "(none)" },
        )
    return when (command.action) {
        "SET_MODEL" ->
            if (args.isEmpty()) SpawnSlash.Refuse("usage: /${command.name} ${command.argument ?: "<model>"}")
            else SpawnSlash.SetModel(answer.provider, args)
        "SET_EFFORT" -> {
            val level = command.values.firstOrNull { it.equals(args, ignoreCase = true) }
                ?: args.takeIf { it.isNotEmpty() && command.values.isEmpty() }
            if (level != null) SpawnSlash.SetEffort(level)
            else SpawnSlash.Refuse("usage: /${command.name} ${command.values.joinToString("|").ifEmpty { command.argument ?: "<level>" }}")
        }
        else -> SpawnSlash.Refuse("/${command.name} needs a running session; a new agent has none yet, so it was not spawned")
    }
}

/** The highlighted row after Up (-1) / Down (+1) in a list of [size] rows; wraps around, and is 0 for an empty list. */
fun moveSlashSelection(index: Int, delta: Int, size: Int): Int = if (size <= 0) 0 else (index + delta).mod(size)

/**
 * The row Enter or Tab should pick, or null when the key must keep its normal job. A row whose text is already in the
 * box is not a pick: Enter then submits (otherwise a command with no argument could never be sent).
 */
fun slashPick(rows: List<SlashSuggestion>, index: Int, text: String): SlashSuggestion? =
    rows.getOrNull(index)?.takeIf { it.selectable && it.replacement != text }

/** Every model id the daemon's `models` frame lists for [provider], in the order listed. */
fun catalogModelsFor(state: AppState, provider: String): List<String> =
    state.modelCatalog.filter { it.provider == provider }.flatMap { it.models }
