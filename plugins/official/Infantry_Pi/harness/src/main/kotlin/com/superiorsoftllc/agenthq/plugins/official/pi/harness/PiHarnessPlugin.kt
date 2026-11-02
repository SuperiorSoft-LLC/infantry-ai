package com.superiorsoftllc.agenthq.plugins.official.pi.harness

import com.superiorsoftllc.agenthq.harness.api.ContextLimitCapability
import com.superiorsoftllc.agenthq.harness.api.EffortCapability
import com.superiorsoftllc.agenthq.harness.api.EffortSupport
import com.superiorsoftllc.agenthq.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.agenthq.harness.api.Harness
import com.superiorsoftllc.agenthq.harness.api.HarnessCapability
import com.superiorsoftllc.agenthq.harness.api.HarnessHost
import com.superiorsoftllc.agenthq.harness.api.HarnessModels
import com.superiorsoftllc.agenthq.harness.api.HarnessPlugin
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.harness.api.HarnessRegistrar
import com.superiorsoftllc.agenthq.harness.api.HarnessRequest
import com.superiorsoftllc.agenthq.harness.api.HarnessSession
import com.superiorsoftllc.agenthq.harness.api.HarnessTranslator
import com.superiorsoftllc.agenthq.harness.api.SandboxContribution
import com.superiorsoftllc.agenthq.harness.api.SandboxRoots
import com.superiorsoftllc.agenthq.harness.api.WatchContribution
import java.io.File

/**
 * HARNESS H6c: Pi as a harness module (it was the daemon's built-in pi harness). Registers one harness, `pi`, through the harness API 2.9:
 * the argv ([PiCommand]), the stream reader (the API's shared [EventStreamTranslator]), the persistent `--mode rpc` session ([PiSession]),
 * the model listing ([PiModels]), the watch extension and the sandbox dir. Construction and [register] have no side effects (the Pi
 * launcher is resolved only when a run starts), so discovering this plugin on a machine without Pi is safe.
 */
class PiHarnessPlugin : HarnessPlugin {
    override val pluginId: String = "Infantry_Pi"

    // Must be written out here, NOT inherited (see HarnessPlugin.contractVersion).
    override val contractVersion: String = HARNESS_API_VERSION

    override fun register(registrar: HarnessRegistrar) {
        registrar.register(PiHarness(registrar.host))
    }
}

class PiHarness internal constructor(
    private val host: HarnessHost,
    private val launcher: () -> List<String>,
    private val models: PiModels,
) : Harness {
    constructor(host: HarnessHost) : this(
        host,
        { PiCommand.resolveLauncher(host) },
        PiModels({ PiModels.runListModels(PiCommand.resolveLauncher(host) + "--list-models") }),
    )

    override val id: String = HARNESS_ID

    override val capabilities: Set<HarnessCapability> = setOf(
        HarnessCapability.MODEL_SELECTION, // --model
        HarnessCapability.SESSION_RESUME, // --session
        HarnessCapability.TOOL_CALLS,
        HarnessCapability.REASONING, // thinking_delta
        HarnessCapability.USAGE, // assistant message usage
        HarnessCapability.EFFORT_SELECTION, // --thinking
    )

    override fun process(request: HarnessRequest): HarnessProcess =
        HarnessProcess(PiCommand.command(launcher(), request.model, request.sessionId, request.effort))

    override fun newTranslator(): HarnessTranslator = EventStreamTranslator()

    /**
     * EFFORT-1: pi calls the reasoning effort "thinking" and takes `--thinking <level>` (off, minimal, low, medium, high, xhigh, max) in both
     * modes ([PiCommand]). Verified on pi 1.3.0. pi clamps a level the chosen model lacks, so the support is as the model allows.
     */
    override val effort: EffortCapability = EffortCapability(EffortSupport.SUPPORTED, "pi --thinking <level> (the effort levels low..max map 1:1)")

    /** CONTEXT-LIMIT-1: best effort through the persistent session (see [PiSession]). */
    override val contextLimit: ContextLimitCapability = PiSession.CAPABILITY

    override fun session(): HarnessSession = PiSession(host, launcher)

    override fun models(): List<HarnessModels> = models.models()

    /** The extension that rewrites a shell call into the watched runner. It runs whatever the approval mode (pi has no approval prompt of its own). */
    override fun watch(env: Map<String, String>, launcher: String): WatchContribution = WatchContribution(
        extraArgs = listOf("-e", File(host.stateDir, PiExtensions.WATCH).absolutePath),
        files = mapOf(PiExtensions.WATCH to PiExtensions.source(PiExtensions.WATCH)),
    )

    /** The extensions folder (the to-do and watch extensions) must be readable inside the session sandbox. */
    override fun sandbox(dirs: SandboxRoots): SandboxContribution = SandboxContribution(readDirs = listOf(dirs.dataDir))

    companion object {
        const val HARNESS_ID = "pi"
    }
}
