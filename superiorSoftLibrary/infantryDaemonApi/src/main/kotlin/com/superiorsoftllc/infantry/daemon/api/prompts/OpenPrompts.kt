// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.prompts

/**
 * SPAWN-PARAMS-1 (DaemonApi 1.56.0, additive): the approval cards and questions open on this daemon right now, the ones the app shows
 * (a runner session's relayed cards included). Published by core in the service registry (`services.find(OpenPrompts::class)`); read
 * only. A card is answered with the same `prompt_response` frame the app sends (`ToolCall.request`), so the same gate applies.
 */
interface OpenPrompts {
    /** Every open prompt, oldest first; [sessionId] non-null = only that session's. */
    fun open(sessionId: String? = null): List<OpenPrompt>
}

/** One open prompt: [kind] is `tool_approval` or `question`; [tool] / [input] for an approval, [question] / [options] for a question. */
data class OpenPrompt(
    val promptId: String,
    val sessionId: String,
    val kind: String,
    val createdAt: Long,
    val tool: String? = null,
    val input: String? = null,
    val question: String? = null,
    /** The option values a decision / answer may name (an approval's `allow_once`, `deny`, ...; a question's labels). */
    val options: List<String> = emptyList(),
)
