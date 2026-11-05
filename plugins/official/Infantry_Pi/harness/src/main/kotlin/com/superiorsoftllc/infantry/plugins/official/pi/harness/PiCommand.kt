// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.pi.harness

import com.superiorsoftllc.infantry.harness.api.HarnessHost

/**
 * Builds the Pi (`@earendil-works/pi-coding-agent`) invocation for one non-interactive run.
 *
 *   <launcher> --mode json [--model <m>] [--thinking <level>] [--session <id>]
 *
 * and the prompt is written to **stdin** then stdin is closed (the harness API's contract for [com.superiorsoftllc.infantry.harness.api.Harness.process]).
 * That is Pi's documented "piped stdin is prepended to the first prompt" behaviour, checked live on pi 1.3.0: a prompt that starts with `-x`
 * and contains `@foo` arrived as the plain user message, which an argv prompt would not guarantee (a leading `@` is a file include, a leading
 * `-` an option, and Windows caps the command line).
 *
 * Facts encoded here and not to be "simplified":
 *  - JSON mode, not `--print`: print mode returns only the final text. `--mode json` streams the events.
 *  - A failed or aborted Pi run exits **0** (documented, and seen live: a rejected API call came back as a `stopReason: "error"` assistant
 *    message and exit code 0). The verdict is in the stream, not the exit code.
 *  - The launcher is `node <cli.js>`, never the npm shims (`pi`, `pi.cmd`, `pi.ps1`): the extensionless shim is not a Windows executable
 *    (`CreateProcess error=193`) and a `.cmd` hop would put a shell between us and the process we have to kill.
 *  - Sessions are persisted (no `--no-session`), because a run that cannot be resumed would make the `SessionStarted` id useless.
 *  - Pi has NO approval gate (audited on pi 1.3.0, BUG-PERM-3): every enabled tool runs without asking in `--mode json` and `--mode rpc` alike,
 *    so no tool-approval level needs a flag. HARNESS-GAP G5: the modes are enforced by the `infantry-approval.ts` extension instead
 *    (env `INFANTRY_TOOL_APPROVAL`; DENY blocks non-read tools, ALLOW_EDITS also lets edit/write run, ASK relays a rpc confirm dialog).
 */
internal object PiCommand {
    const val CLI_ID = "pi"

    /**
     * The argv prefix that starts Pi. CLIPATH-1: the file comes from the daemon's one resolver through [HarnessHost.resolveCli]
     * (Settings override, `INFANTRY_PI_EXE`, the known install locations, PATH). A `.js` entry point is run with the node the same
     * resolver finds (CLIPATH-2: `host.resolveCli("node")`; if that has none, `INFANTRY_NODE_EXE`, else `node` on PATH); anything else is the executable itself. Not found throws with the reason.
     */
    fun resolveLauncher(host: HarnessHost, node: () -> String = { host.resolveCli("node") ?: defaultNode() }): List<String> {
        val path = host.resolveCli(CLI_ID) ?: error("the pi CLI was not found (set its location in Settings, INFANTRY_PI_EXE, or install @earendil-works/pi-coding-agent)")
        return launcherFor(path, node)
    }

    /** Pure: the argv prefix for a resolved Pi file. [node] is asked only for a `.js` entry point. */
    fun launcherFor(path: String, node: () -> String = ::defaultNode): List<String> =
        if (path.endsWith(".js", ignoreCase = true)) listOf(node(), path) else listOf(path)

    private fun defaultNode(): String = System.getenv("INFANTRY_NODE_EXE")?.takeIf { it.isNotBlank() } ?: "node"

    /** Pure argv composition, separate from environment resolution so it is assertable with no Pi installed. */
    fun command(launcher: List<String>, model: String?, resumeSessionId: String?, effort: String? = null, rpc: Boolean = false): List<String> = buildList {
        addAll(launcher)
        // BUG-M2-11d: `--mode rpc` is the persistent mode (one process, prompts as stdin JSON lines; PiSession).
        add("--mode"); add(if (rpc) "rpc" else "json")
        model?.takeIf { it.isNotBlank() }?.let { add("--model"); add(it) }
        effort?.takeIf { it.isNotBlank() }?.let { add("--thinking"); add(it) } // Pi calls reasoning effort "thinking"
        resumeSessionId?.takeIf { it.isNotBlank() }?.let { add("--session"); add(it) }
    }
}
