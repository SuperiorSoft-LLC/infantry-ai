// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.daemon.api.orchestrator.FrameSide

/**
 * RUNNER-POOL-10 (docs/RUNNER-POOL.md section 12): what one daemon process is. One build, one image; the startup mode picks which
 * side runs.
 *
 * - [BOTH] (default): the orchestrator and runner `local`, exactly as before this switch existed.
 * - [RUNNER]: a stand-alone runner (a remote server): sessions, harnesses, worktrees and the runner link. It has no board, so a
 *   client frame that reads or writes orchestrator state is refused with a reason.
 * - [ORCHESTRATOR]: the board side only. No session runs on this machine: placement refuses `local`.
 *
 * Chosen by `infantry daemon --mode <m>` (which sets the system property), else `INFANTRY_MODE`, else the setting [SETTING],
 * else [BOTH]. A value that names no mode is refused at start-up, never ignored.
 */
enum class DeploymentMode(val id: String) {
    BOTH("both"),
    RUNNER("runner"),
    ORCHESTRATOR("orchestrator");

    /** Whether sessions run on this machine (runner `local` exists). */
    val runsSessions: Boolean get() = this != ORCHESTRATOR

    /** Whether this daemon is the board. */
    val hasBoard: Boolean get() = this != RUNNER

    /**
     * Why a client frame of [type] (declared [side], null = unknown) is refused in this mode, or null when it may go on. A runner
     * refuses what [FrameSide.ORCHESTRATOR] names and the board family, unless the frame answers a session a remote orchestrator
     * started (the caller checks that first: those are forwarded, not refused).
     */
    fun refusal(type: String, side: String?): String? = when {
        this == RUNNER && (side == FrameSide.ORCHESTRATOR || type in BoardFrames.TYPES || type in BoardFrames.PIPELINE_TYPES ||
            type in BoardFrames.KNOWLEDGE_RECORD_TYPES || type in BoardFrames.MEMORY_TYPES || type in BoardFrames.ROLE_TYPES) ->
            "${type.filter { it in ' '..'~' }.take(SHOWN)} refused: this daemon runs in runner mode (--mode runner); the board, tickets and mail live on the orchestrator"
        else -> null
    }

    companion object {
        /** The declared setting; the lowest source. */
        const val SETTING = "daemon.mode"
        const val ENV = "INFANTRY_MODE"
        const val PROPERTY = "infantry.mode"
        private const val SHOWN = 64

        fun parse(text: String?): DeploymentMode? = entries.firstOrNull { it.id == text?.trim()?.lowercase() }

        /**
         * The first non-blank of [explicit] (`--mode` / system property), [env] and [setting], parsed. All blank = [BOTH].
         * A non-blank value that is not a mode throws, naming the three.
         */
        fun resolve(explicit: String?, env: String?, setting: String?): DeploymentMode {
            val chosen = listOf(explicit, env, setting).firstOrNull { !it.isNullOrBlank() } ?: return BOTH
            return parse(chosen) ?: throw IllegalArgumentException("unknown mode '${chosen.trim().take(SHOWN)}': use ${entries.joinToString(" | ") { it.id }}")
        }

        /** The `--mode <m>` / `--mode=<m>` of a command line, or null. */
        fun fromArgs(args: Array<String>): String? {
            args.firstOrNull { it.startsWith("--mode=") }?.let { return it.removePrefix("--mode=") }
            val i = args.indexOf("--mode")
            return if (i >= 0) args.getOrNull(i + 1) else null
        }
    }
}
