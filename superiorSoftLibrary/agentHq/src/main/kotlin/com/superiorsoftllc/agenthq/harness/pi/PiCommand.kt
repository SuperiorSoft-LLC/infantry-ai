package com.superiorsoftllc.agenthq.harness.pi

import com.superiorsoftllc.agenthq.harness.ToolApproval
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import java.io.File

/**
 * Builds the Pi (`@earendil-works/pi-coding-agent`) invocation for one non-interactive run.
 *
 *   <launcher> --mode json [--model <m>] [--session <id>]
 *
 * and the prompt is written to **stdin** then stdin is closed, exactly like the Claude harness. That is Pi's
 * documented "piped stdin is prepended to the first prompt" behaviour, and it was checked live on pi 1.3.0: a prompt
 * that starts with `-x` and contains `@foo` arrived as the plain user message, which an argv prompt would not
 * guarantee (a leading `@` is a file include, a leading `-` an option, and Windows caps the command line).
 *
 * Facts encoded here and not to be "simplified":
 *  - JSON mode, not `--print`: print mode returns only the final text. `--mode json` streams the events.
 *  - A failed or aborted Pi run exits **0** (documented, and seen live: a rejected API call came back as a
 *    `stopReason: "error"` assistant message and exit code 0). The verdict is in the stream, not the exit code.
 *  - The launcher is `node <cli.js>`, never the npm shims (`pi`, `pi.cmd`, `pi.ps1`): the extensionless shim is
 *    not a Windows executable (`CreateProcess error=193`, same rule as the other harnesses) and a `.cmd` hop would
 *    put a shell between us and the process we have to kill.
 *  - Sessions are persisted (no `--no-session`), because a run that cannot be resumed would make the
 *    `SessionStarted` id the harness hands back useless.
 */
object PiCommand {
    /**
     * The argv prefix that starts Pi. CLIPATH-1: the file comes from the one resolver
     * ([com.superiorsoftllc.agenthq.executables.CliExecutables]: Settings override, `AGENTHQ_PI_EXE`, the known install
     * locations in [com.superiorsoftllc.agenthq.executables.CliTools.PI], then PATH). A `.js` entry point is run with
     * the node the same resolver finds (CLIPATH-2: [com.superiorsoftllc.agenthq.executables.CliTools.NODE], whose env
     * knob is still `AGENTHQ_NODE_EXE`); anything else is the executable itself. Either not found throws with the
     * reason and every path tried.
     */
    fun resolveLauncher(): List<String> =
        launcherFor(com.superiorsoftllc.agenthq.executables.CliExecutables.require(com.superiorsoftllc.agenthq.executables.CliTools.PI))

    /** Pure: the argv prefix for a resolved Pi file. [node] is asked only for a `.js` entry point. */
    internal fun launcherFor(
        path: String,
        node: () -> String = {
            com.superiorsoftllc.agenthq.executables.CliExecutables.require(com.superiorsoftllc.agenthq.executables.CliTools.NODE)
        },
    ): List<String> =
        if (path.endsWith(".js", ignoreCase = true)) listOf(node(), path) else listOf(path)

    fun spec(
        workingDir: File? = null,
        resumeSessionId: String? = null,
        model: String? = null,
        launcher: List<String> = resolveLauncher(),
        effort: String? = null,
        toolApproval: ToolApproval = ToolApproval.DENY,
    ): ProcessSpec = ProcessSpec(command = command(launcher, model, resumeSessionId, effort, toolApproval = toolApproval), workingDir = workingDir)

    /** Pure argv composition, separate from environment resolution so it is assertable with no Pi installed. */
    internal fun command(
        launcher: List<String>,
        model: String?,
        resumeSessionId: String?,
        effort: String? = null,
        rpc: Boolean = false,
        toolApproval: ToolApproval = ToolApproval.DENY,
    ): List<String> = buildList {
        addAll(launcher)
        // BUG-M2-11d: `--mode rpc` is the persistent mode (one process, prompts as stdin JSON lines; PiSessionAdapter).
        add("--mode"); add(if (rpc) "rpc" else "json")
        model?.takeIf { it.isNotBlank() }?.let { add("--model"); add(it) }
        effort?.takeIf { it.isNotBlank() }?.let { add("--thinking"); add(it) } // Pi calls reasoning effort "thinking"
        resumeSessionId?.takeIf { it.isNotBlank() }?.let { add("--session"); add(it) }
        addAll(toolApprovalArgs(toolApproval))
    }

    /**
     * BUG-PERM-3: the argv for [mode]. EMPTY FOR EVERY MODE, on purpose (audited on pi 1.3.0, `pi --help`):
     *
     *  - Pi has NO approval gate. Every enabled tool (read, bash, powershell, edit, write...) runs without asking, in
     *    `--mode json` and `--mode rpc` alike; nothing hangs and nothing is denied. Its only switches are WHICH tools
     *    exist (`--tools`, `--exclude-tools`, `--no-tools`); `--approve` is about trusting project-local files, not tools.
     *  - So [ToolApproval.ALLOW_ALL] and [ToolApproval.ALLOW_EDITS] need no flag: Pi already allows at least that much.
     *  - [ToolApproval.DENY] is defined as "only what the CLI's own configuration already allows", and Pi's own
     *    configuration allows every enabled tool. Restricting it here (e.g. `--exclude-tools bash,powershell,edit,write`)
     *    would be a behaviour change for every Pi session, which defaults to deny. That is a product decision, not this
     *    mapping: a stricter deny is one line here.
     *
     * DENIAL NOTICE: Pi never denies a tool call, so its stream carries no denial signal and no
     * `EventNormalizer.Line.Denial` is emitted for it. A tool removed with `--exclude-tools` is simply not offered.
     */
    internal fun toolApprovalArgs(mode: ToolApproval): List<String> = when (mode) {
        ToolApproval.DENY, ToolApproval.ALLOW_EDITS, ToolApproval.ALLOW_ALL, ToolApproval.ASK -> emptyList()
    }
}
