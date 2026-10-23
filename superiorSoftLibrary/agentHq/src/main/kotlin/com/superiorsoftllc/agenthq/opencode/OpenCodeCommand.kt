package com.superiorsoftllc.agenthq.opencode

import com.superiorsoftllc.agenthq.session.InfantryEnv

import com.superiorsoftllc.agenthq.harness.ToolApproval
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import java.io.File

/**
 * Builds the `opencode run` invocation. Two Phase 0 facts are encoded here and must not be
 * "simplified":
 *  - the NATIVE `opencode.exe` is spawned, never the npm shim: `ProcessBuilder` gets
 *    `CreateProcess error=193` on the extensionless / `.ps1` script.
 *  - the prompt is written to stdin and stdin is then CLOSED by the caller: `opencode run` blocks
 *    on stdin EOF and emits nothing while the pipe stays open.
 *
 * `--format json` is used because it gives one JSON object per line on stdout, empty stderr and no
 * ANSI (Phase 0 finding #4) -- ideal for a line-oriented pipe reader.
 */
object OpenCodeCommand {
    /** Free model: the daemon demo must never burn a metered model (Phase 0 used the same one). */
    const val DEFAULT_MODEL = "opencode/deepseek-v4.1-flash-free"

    /**
     * CLIPATH-1: the one resolver ([com.superiorsoftllc.agenthq.executables.CliExecutables]): the Settings override, then
     * `AGENTHQ_OPENCODE_EXE`, then the known install locations ([com.superiorsoftllc.agenthq.executables.CliTools.OPENCODE]),
     * then PATH. Not found throws with the reason and every path tried.
     */
    fun resolveExecutable(): String =
        com.superiorsoftllc.agenthq.executables.CliExecutables.require(com.superiorsoftllc.agenthq.executables.CliTools.OPENCODE)

    /** `AGENTHQ_OPENCODE_MODEL`, else [DEFAULT_MODEL]. A blank or "default" value is not a model id (BUG-G2). */
    fun model(env: (String) -> String? = InfantryEnv::get): String =
        env("AGENTHQ_OPENCODE_MODEL")?.let(::realModel) ?: DEFAULT_MODEL

    /** [value] trimmed, or null when it names no model: blank, or the display placeholder "default". */
    internal fun realModel(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("default", ignoreCase = true) }

    /**
     * The prompt is NOT an argv element: the caller writes it to stdin and closes stdin, which is
     * how the Phase 0 spike delivered it (T3).
     *
     * [sessionId], when set, continues an existing session (`run --session <id>`); null starts a
     * fresh one. Phase 2 lifted this call behind `AgentProvider` so the daemon is CLI-agnostic.
     *
     * [model] is the session's own choice (contract 1.3.0); see [effectiveModel].
     */
    fun spec(
        workingDir: File? = null,
        sessionId: String? = null,
        model: String? = null,
        toolApproval: ToolApproval = ToolApproval.DENY,
    ): ProcessSpec =
        ProcessSpec(
            command = command(resolveExecutable(), effectiveModel(model), sessionId, toolApproval),
            workingDir = workingDir,
            env = OpenCodePermissions.runEnv(toolApproval),
        )

    /**
     * The model a run names: the session's explicit choice (profile / ticket / spawn) first, then
     * `AGENTHQ_OPENCODE_MODEL`, then [DEFAULT_MODEL]. "default" is never passed through as an id.
     */
    internal fun effectiveModel(requested: String?, env: (String) -> String? = InfantryEnv::get): String =
        realModel(requested) ?: model(env)

    /**
     * Pure argv composition, separate from environment resolution so it can be asserted in tests. [toolApproval] adds
     * [OpenCodePermissions.runFlags] (BUG-PERM-2: `--auto` for allowAll; allowEdits rides the env, see [spec]).
     */
    internal fun command(
        executable: String,
        model: String,
        sessionId: String?,
        toolApproval: ToolApproval = ToolApproval.DENY,
    ): List<String> =
        buildList {
            add(executable)
            add("run")
            add("--format"); add("json")
            add("-m"); add(model)
            sessionId?.takeIf { it.isNotBlank() }?.let { add("--session"); add(it) }
            addAll(OpenCodePermissions.runFlags(toolApproval))
        }
}
