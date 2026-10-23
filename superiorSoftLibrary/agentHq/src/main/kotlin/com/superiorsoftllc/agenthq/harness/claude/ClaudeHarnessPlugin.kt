package com.superiorsoftllc.agenthq.harness.claude

import com.superiorsoftllc.agenthq.harness.HARNESS_CONTRACT_VERSION
import com.superiorsoftllc.agenthq.harness.HarnessPlugin
import com.superiorsoftllc.agenthq.harness.HarnessRegistrar
import com.superiorsoftllc.agenthq.harness.HarnessRequest
import com.superiorsoftllc.agenthq.runner.PipedProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter
import com.superiorsoftllc.agenthq.claude.ClaudeSessionAdapter

/**
 * HARNESS 3/4 - Claude Code as a PLUGIN. The first real harness, and deliberately written the way a
 * third party would write one: it implements [HarnessPlugin], registers its own harness through the
 * [HarnessRegistrar] it is handed, declares its contract version, and is found by the daemon through
 * `META-INF/services/com.superiorsoftllc.agenthq.harness.HarnessPlugin`. Nothing in the daemon side
 * ([com.superiorsoftllc.agenthq.harness.HarnessRegistry]) names Claude, so adding this plugin edited no
 * daemon code. If a change there is ever needed to make Claude work, HARNESS 1/4 or 2/4 is wrong.
 *
 * Construction and [register] have NO side effects (the claude executable is resolved only when a run
 * starts), so discovering this plugin on a machine without Claude installed is safe; the first RUN then
 * reports a typed [com.superiorsoftllc.agenthq.harness.HarnessEvent.Error].
 *
 * WHAT THIS PROVES, AND WHAT IT DOES NOT: the interface WORKS on Claude. One implementation cannot
 * fail a parity claim, so this is NOT evidence that the interface is general. That is HARNESS 4/4 (Pi).
 *
 * [runner] and [specFor] are the seams tests (and any host) use to replace the OS process; the no-arg
 * constructor the ServiceLoader needs is the all-defaults one.
 */
class ClaudeHarnessPlugin(
    private val runner: ProcessRunner,
    private val specFor: (HarnessRequest) -> ProcessSpec,
    /** BUG-M2-11c: claude's persistent mode, so a session on this harness keeps ONE process (the daemon's SessionHost). */
    private val sessionAdapter: HarnessSessionAdapter?,
) : HarnessPlugin {
    /** The ServiceLoader's construction: the real CLI, with its persistent mode. */
    constructor() : this(PipedProcessRunner(), { ClaudeHarness.defaultSpec(it) }, ClaudeSessionAdapter())

    /** A replaced process (tests, a host): no persistent mode, so the session host drives [runner] one-shot. */
    constructor(
        runner: ProcessRunner,
        specFor: (HarnessRequest) -> ProcessSpec = { ClaudeHarness.defaultSpec(it) },
    ) : this(runner, specFor, null)

    override val pluginId: String = PLUGIN_ID

    // Must be written out here, NOT inherited: this const is inlined when the PLUGIN is compiled, which is
    // what makes the daemon's version handshake mean something (see HarnessPlugin.contractVersion).
    override val contractVersion: String = HARNESS_CONTRACT_VERSION

    override fun register(registrar: HarnessRegistrar) {
        registrar.register(ClaudeHarness(runner, specFor, sessionAdapter = sessionAdapter))
    }

    companion object {
        const val PLUGIN_ID = "agenthq-claude"
    }
}
