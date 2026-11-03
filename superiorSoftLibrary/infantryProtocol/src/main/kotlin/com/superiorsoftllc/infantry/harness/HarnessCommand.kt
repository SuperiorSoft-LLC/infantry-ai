// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import kotlinx.serialization.Serializable

/**
 * What the daemon does when a client runs a command from a harness's catalog. The daemon knows these few
 * ACTIONS, never a vendor: a harness declares its commands in terms of them (CP-4).
 *
 *  - [SET_MODEL]  `/model <id>`: the session's next run names that model. Validated like a spawn's model.
 *  - [SET_EFFORT] `/effort <level>`: the session's next run carries that effort level.
 *  - [NATIVE]     the harness has its OWN command for this (Claude's `/compact`): the daemon hands [HarnessCommand.native]
 *                 to the harness as the session's next turn and the harness does the work. The daemon does not
 *                 reimplement it (no summarising of its own).
 */
@Serializable
enum class CommandAction { SET_MODEL, SET_EFFORT, NATIVE }

/**
 * One entry of a harness's command/option catalog: what a client offers as the user types `/`.
 *
 * [name] is what follows the slash (no slash, no whitespace). [argument] is a hint for the value to type
 * (`<model>`), null when the command takes none. [values] are the values the command accepts: for
 * [CommandAction.SET_EFFORT] a non-empty list is the CLOSED set the daemon validates against, an empty list means
 * "the harness decides" and the text is passed through; for [CommandAction.SET_MODEL] it is a suggestion list
 * (the model itself is validated by the provider). [native] is the text delivered to the harness for
 * [CommandAction.NATIVE], and only then.
 */
@Serializable
data class HarnessCommand(
    val name: String,
    val description: String,
    val action: CommandAction,
    val argument: String? = null,
    val values: List<String> = emptyList(),
    val native: String? = null,
) {
    init {
        require(name.isNotBlank() && name.none { it.isWhitespace() || it == '/' }) { "a command name is a bare word, got '$name'" }
        require((action == CommandAction.NATIVE) == (native != null)) { "only a NATIVE command carries native text (command '$name')" }
    }
}

/**
 * The catalog most CLI harnesses share: switch the model, set the effort, compact the conversation. A harness lists
 * the commands it can honour; [efforts] are ITS levels (the daemon never invents them), [compactText] is the
 * harness's own compaction command, handed to it verbatim.
 */
object StandardCommands {
    fun catalog(efforts: List<String>, compactText: String = "/compact", models: List<String> = emptyList()): List<HarnessCommand> = listOf(
        HarnessCommand("model", "Switch the model for the next turn", CommandAction.SET_MODEL, argument = "<model>", values = models),
        HarnessCommand("effort", "Set the reasoning effort for the next turn", CommandAction.SET_EFFORT, argument = "<level>", values = efforts),
        HarnessCommand("compact", "Compact the conversation (the harness's own compaction)", CommandAction.NATIVE, native = compactText),
    )
}

/** What the user typed after the slash, split into the command word and the rest. */
data class CommandLine(val name: String, val args: String)

/** The pure rules for reading a slash input and filtering a catalog by what has been typed so far. */
object CommandLines {
    /** More values than this are never sent in one answer: a picker narrows them with the typed prefix. */
    const val MAX_VALUES = 50

    /** A leading `/` (after optional whitespace) makes the whole input a command, never a prompt. */
    fun isCommand(text: String): Boolean = text.trimStart().startsWith("/")

    /** `"/model  a b"` -> name `model`, args `a b`. The caller has checked [isCommand]. */
    fun parse(text: String): CommandLine {
        val body = text.trimStart().removePrefix("/")
        val name = body.takeWhile { !it.isWhitespace() }
        return CommandLine(name, body.drop(name.length).trim())
    }

    /**
     * The catalog entries matching [typed], the text after the slash as typed so far (a leading `/` is tolerated).
     * Without a space it is a name prefix and the matches carry no values (a picker lists names first). With a space
     * it names ONE command and the value prefix after it narrows that command's [HarnessCommand.values].
     * Matching ignores case. Nothing matching is an empty list.
     */
    fun complete(catalog: List<HarnessCommand>, typed: String): List<HarnessCommand> {
        val body = typed.trimStart().removePrefix("/")
        if (body.none { it.isWhitespace() }) {
            return catalog.filter { it.name.startsWith(body, ignoreCase = true) }.map { it.copy(values = emptyList()) }
        }
        val line = parse(body)
        val command = catalog.firstOrNull { it.name.equals(line.name, ignoreCase = true) } ?: return emptyList()
        // CMD-MODEL-1: a model is found by any part of its id (`son` keeps claude-sonnet-5-5); other values by prefix.
        val matches: (String) -> Boolean =
            if (command.action == CommandAction.SET_MODEL) { v -> v.contains(line.args, ignoreCase = true) }
            else { v -> v.startsWith(line.args, ignoreCase = true) }
        val narrowed = command.values.filter(matches).take(MAX_VALUES)
        return listOf(command.copy(values = narrowed))
    }
}
