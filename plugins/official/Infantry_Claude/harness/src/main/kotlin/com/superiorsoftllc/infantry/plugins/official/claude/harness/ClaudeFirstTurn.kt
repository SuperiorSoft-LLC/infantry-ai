// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

/**
 * FIRST-TURN-1 (ticket 36419e4e): the first turn an interactive claude is sent must show signs of life, or the session says so instead of
 * sitting silent until the host's stall watchdog kills it (15 minutes on the owner's install).
 *
 * Two deadlines, each a setting read at every launch (0 = off):
 * - [submitSeconds] ([SUBMIT_SETTING]): from the paste to the CLI taking the prompt (its `UserPromptSubmit` hook, or the prompt recorded in
 *   the transcript). Missed: the prompt is on screen = Enter once more; it is not = pasted again.
 * - [replySeconds] ([REPLY_SETTING]): from the CLI taking the prompt to the model's first record (text, a tool call, a Stop, an API error).
 *   The case seen on the owner's install (session e73cf3f2, ticket 02c1fe44): the prompt was submitted, claude sent its first API request
 *   and nothing ever came back. Missed: Esc stops the stuck request and a short [NUDGE] is sent so the model carries on.
 *
 * Only ONE recovery per turn. A deadline missed again after it fails the turn with the screen tail as the reason (the session goes FAILED,
 * so `session.error` / `session.idle` fire and scripts can react).
 */
data class ClaudeFirstTurn(val submitSeconds: Int = DEFAULT_SUBMIT_SEC, val replySeconds: Int = DEFAULT_REPLY_SEC) {
    val submitMs: Long get() = submitSeconds * 1000L
    val replyMs: Long get() = replySeconds * 1000L

    companion object {
        const val SUBMIT_SETTING = "harness.claude.firstTurnSubmitSeconds"
        const val REPLY_SETTING = "harness.claude.firstTurnReplySeconds"
        const val DEFAULT_SUBMIT_SEC = 30
        /** Generous: a high-effort first request can think for minutes before its first record is written. */
        const val DEFAULT_REPLY_SEC = 300

        /** The turn pasted after a stuck first request was stopped; the conversation (the brief) is already in claude's context. */
        const val NUDGE_PREFIX = "The previous request got no reply and was stopped."
        const val NUDGE = "$NUDGE_PREFIX Please carry on with the task above."

        /** What claude shows instead of a long paste (`[Pasted text #1 +40 lines]`), spaces squeezed out and lower-cased. */
        const val PASTED_MARKER = "[pastedtext#"

        /** How many leading characters of a prompt (squeezed) prove it sits in the input box. */
        const val PROMPT_HEAD_CHARS = 24

        /** A setting's value: a whole number of seconds, 0 = off; blank or unreadable = [default]. */
        fun seconds(raw: String?, default: Int): Int = raw?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: default

        fun of(submit: String?, reply: String?) = ClaudeFirstTurn(seconds(submit, DEFAULT_SUBMIT_SEC), seconds(reply, DEFAULT_REPLY_SEC))
    }
}
