package com.superiorsoftllc.agenthq.harness.pi

import com.superiorsoftllc.agenthq.harness.CliHarness
import com.superiorsoftllc.agenthq.harness.HARNESS_CONTRACT_VERSION
import com.superiorsoftllc.agenthq.harness.HarnessCapability
import com.superiorsoftllc.agenthq.harness.HarnessPlugin
import com.superiorsoftllc.agenthq.harness.HarnessRegistrar
import com.superiorsoftllc.agenthq.harness.HarnessRequest
import com.superiorsoftllc.agenthq.harness.HarnessTranslator
import com.superiorsoftllc.agenthq.runner.PipedProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter
import java.io.File

/**
 * Pi behind the HARNESS 1/4 interface (HARNESS 4/4): the second harness, from a different vendor than the first.
 *
 * Written the way the Claude one is and the way any third party would write one: the shared [CliHarness] runs the
 * process, [PiStreamTranslator] is the only code that knows Pi's wire schema, and only the argv ([defaultSpec]) is
 * Pi's own. `HarnessRequest.project` is an opaque label and is NOT interpreted (a host supplies a working directory
 * through [specFor], as with Claude).
 */
class PiHarness(
    runner: ProcessRunner,
    specFor: (HarnessRequest) -> ProcessSpec = { defaultSpec(it) },
    translatorFactory: () -> HarnessTranslator = { PiStreamTranslator() },
    /** BUG-M2-11d: pi's persistent mode ([PiSessionAdapter], `--mode rpc`); null = the session host runs it one-shot. */
    private val sessionAdapter: HarnessSessionAdapter? = null,
) : CliHarness(runner, specFor, translatorFactory, cliName = "pi") {
    override val id: String = HARNESS_ID

    override fun sessionAdapter(): HarnessSessionAdapter? = sessionAdapter

    /** H6a: one catalog per harness, so its TTL cache survives repeated `models` requests. */
    private val modelCatalog = PiModelCatalog()

    override fun catalog(): com.superiorsoftllc.agenthq.provider.HarnessModelCatalog? = modelCatalog

    /**
     * EFFORT-1: pi calls the reasoning effort "thinking" and takes `--thinking <level>` (off, minimal, low, medium, high, xhigh,
     * max) in both modes ([PiCommand]). Verified on pi 1.3.0: `pi --help` lists the flag and `pi --thinking low -p "say ok"` ran.
     * pi clamps a level the chosen model lacks, so the support is as the model allows.
     */
    override val effort: com.superiorsoftllc.agenthq.harness.EffortCapability = com.superiorsoftllc.agenthq.harness.EffortCapability(
        com.superiorsoftllc.agenthq.harness.EffortSupport.SUPPORTED,
        "pi --thinking <level> (the effort levels low..max map 1:1)",
    )

    /** CONTEXT-LIMIT-1: best effort through the persistent session; a one-shot pi run (`--mode json`) has no way to apply it. */
    override val contextLimit: com.superiorsoftllc.agenthq.harness.ContextLimitCapability =
        if (sessionAdapter is PiSessionAdapter) PiSessionAdapter.CAPABILITY
        else com.superiorsoftllc.agenthq.harness.ContextLimitCapability(
            com.superiorsoftllc.agenthq.harness.ContextLimitSupport.UNSUPPORTED,
            "pi runs one-shot here (--mode json): no flag or env sets its compaction threshold",
        )

    // Written out on purpose, as in the Claude harness: the interface's default getter is evaluated in the daemon's
    // copy of the interface; this inlines the const when THIS plugin is compiled.
    override val contractVersion: String = HARNESS_CONTRACT_VERSION

    override val capabilities: Set<HarnessCapability> = setOf(
        HarnessCapability.MODEL_SELECTION, // --model
        HarnessCapability.SESSION_RESUME, // --session
        HarnessCapability.TOOL_CALLS,
        HarnessCapability.REASONING, // thinking_delta
        HarnessCapability.USAGE, // assistant message usage
        HarnessCapability.EFFORT_SELECTION, // --thinking
    )

    companion object {
        const val HARNESS_ID = "pi"

        /** The real invocation for [request]: model and resume come from the request, never from a guess. */
        fun defaultSpec(
            request: HarnessRequest,
            launcher: () -> List<String> = PiCommand::resolveLauncher,
            workingDir: File? = null,
        ): ProcessSpec = PiCommand.spec(workingDir, request.sessionId, request.model, launcher(), request.effort, request.toolApproval)
    }
}

/**
 * HARNESS 4/4 - Pi as a PLUGIN, registered exactly as Claude is: it implements [HarnessPlugin], registers its own
 * harness through the [HarnessRegistrar] it is handed, declares its contract version, and is found through
 * `META-INF/services/com.superiorsoftllc.agenthq.harness.HarnessPlugin`. Adding it edited no daemon code.
 *
 * Construction and [register] have NO side effects (the Pi launcher is resolved only when a run starts), so
 * discovering this plugin on a machine without Pi is safe; the first RUN then reports a typed Error event.
 */
class PiHarnessPlugin(
    private val runner: ProcessRunner,
    private val specFor: (HarnessRequest) -> ProcessSpec,
    /** BUG-M2-11d: pi's persistent mode, so a session on this harness keeps ONE process (the daemon's SessionHost). */
    private val sessionAdapter: HarnessSessionAdapter?,
) : HarnessPlugin {
    /** The ServiceLoader's construction: the real CLI, with its persistent mode. */
    constructor() : this(PipedProcessRunner(), { PiHarness.defaultSpec(it) }, PiSessionAdapter())

    /** A replaced process (tests, a host): no persistent mode, so the session host drives [runner] one-shot. */
    constructor(
        runner: ProcessRunner,
        specFor: (HarnessRequest) -> ProcessSpec = { PiHarness.defaultSpec(it) },
    ) : this(runner, specFor, null)

    override val pluginId: String = PLUGIN_ID

    // Must be written out here, NOT inherited (see HarnessPlugin.contractVersion).
    override val contractVersion: String = HARNESS_CONTRACT_VERSION

    override fun register(registrar: HarnessRegistrar) {
        registrar.register(PiHarness(runner, specFor, sessionAdapter = sessionAdapter))
    }

    companion object {
        const val PLUGIN_ID = "agenthq-pi"
    }
}
