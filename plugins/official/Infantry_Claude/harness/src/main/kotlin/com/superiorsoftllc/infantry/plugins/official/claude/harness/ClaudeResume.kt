// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import java.io.File

/**
 * HARNESS-GAP G2 (ticket d472e3f6): a stored provider session id that claude never wrote a transcript for is NOT resumable.
 *
 * The interactive claude names a new session itself (`--session-id`) and reports that id on its first step, before the CLI is up, so a CLI
 * that quits first (the folder trust question, a crash at boot) leaves the daemon with an id claude does not know. `--resume <id>` then
 * fails on every later start ("No conversation found with session ID"), in either mode, and the session is wedged for good.
 *
 * The fix is at launch: an id with no transcript starts a FRESH conversation under the SAME id (`--session-id <id>`), so the id the daemon
 * keeps becomes real once claude writes its first record, and nothing has to be reported again. The daemon composed the ticket brief into
 * the session's first turn only, and that turn never reached the model; so the first prompt of every new session is kept next to its
 * terminal state ([firstPromptFile]) until its transcript exists, and a recovered launch sends it again ahead of its own prompt (the same
 * recovery opencode does for a refused session, OpenCodeServeSession.recoverFreshSession).
 */
object ClaudeResume {
    /** True when claude has a transcript for [sessionId] (under [cwd]'s project folder or any other): `--resume` can find it. */
    fun resumable(configDir: File, cwd: String, sessionId: String): Boolean = ClaudeTranscripts.find(configDir, cwd, sessionId) != null

    /** Where the first prompt of new session [sessionId] waits until claude has recorded it. */
    fun firstPromptFile(stateDir: File, sessionId: String): File = File(File(stateDir, ClaudeTtySession.TTY_DIR), "$sessionId.first-prompt")

    /** Keeps [prompt] as [sessionId]'s first prompt, unless one is kept already (a recovered launch's prompt is not a first one). */
    fun keepFirstPrompt(file: File, prompt: String) {
        if (prompt.isBlank() || file.exists()) return
        runCatching { file.parentFile?.mkdirs(); file.writeText(prompt) }
    }

    fun savedFirstPrompt(file: File): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    /** The turn a recovered launch sends: the lost first prompt (the brief), then [prompt]. Nothing kept = [prompt] alone. */
    fun recoveryPrompt(saved: String?, prompt: String): String =
        if (saved.isNullOrBlank() || saved == prompt) prompt
        else "$saved\n\n---\n\n$RECOVERY_NOTE\n\n$prompt"

    /** The line a recovered launch reports, for the session's detail log. */
    fun recoveredDetail(sessionId: String, briefResent: Boolean): String =
        "claude has no conversation $sessionId (it quit before it wrote a transcript): started it fresh under the same id" +
            if (briefResent) ", with the session's first prompt sent again" else ""

    const val RECOVERY_NOTE = "(The message above was sent earlier but never reached you: the CLI quit before it started. The newer message follows.)"
}
