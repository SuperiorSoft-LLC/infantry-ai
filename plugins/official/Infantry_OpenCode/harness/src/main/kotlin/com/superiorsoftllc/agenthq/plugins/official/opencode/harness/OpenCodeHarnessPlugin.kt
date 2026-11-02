package com.superiorsoftllc.agenthq.plugins.official.opencode.harness

import com.superiorsoftllc.agenthq.harness.api.ConfigParts
import com.superiorsoftllc.agenthq.harness.api.ContextLimitCapability
import com.superiorsoftllc.agenthq.harness.api.EffortCapability
import com.superiorsoftllc.agenthq.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.agenthq.harness.api.Harness
import com.superiorsoftllc.agenthq.harness.api.HarnessCapability
import com.superiorsoftllc.agenthq.harness.api.HarnessCommandSpec
import com.superiorsoftllc.agenthq.harness.api.HarnessHost
import com.superiorsoftllc.agenthq.harness.api.HarnessModels
import com.superiorsoftllc.agenthq.harness.api.HarnessPlugin
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.harness.api.HarnessProcessListener
import com.superiorsoftllc.agenthq.harness.api.HarnessRegistrar
import com.superiorsoftllc.agenthq.harness.api.HarnessRequest
import com.superiorsoftllc.agenthq.harness.api.HarnessSession
import com.superiorsoftllc.agenthq.harness.api.HarnessStats
import com.superiorsoftllc.agenthq.harness.api.HarnessTranslator
import com.superiorsoftllc.agenthq.harness.api.ToolApproval
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * HARNESS H8b-2: opencode as a harness module, on the harness API 2.12: the `opencode run` argv ([OpenCodeCommand]), the stream reader
 * ([OpenCodeTranslator]), the persistent `opencode serve` session over HTTP/SSE ([OpenCodeServeSession]), the permission mapping
 * ([OpenCodePermissions]), the inline-config merge (MCP server, tool allow rule, watch plugin), the `/` command catalog and the model list
 * (the user's config files first, `opencode models` when they name none). Construction and [register] have no side effects (the CLI is resolved
 * only when a run starts). H8b-3: the daemon's only opencode harness (the core copy is deleted); also reads the session store ([OpenCodeStats]).
 */
class OpenCodeHarnessPlugin : HarnessPlugin {
    override val pluginId: String = "Infantry_OpenCode"

    // Must be written out here, NOT inherited (see HarnessPlugin.contractVersion).
    override val contractVersion: String = HARNESS_API_VERSION

    override fun register(registrar: HarnessRegistrar) {
        registrar.register(OpenCodeHarness(registrar.host))
    }
}

class OpenCodeHarness internal constructor(
    private val host: HarnessHost,
    private val executable: () -> String,
    private val modelLister: () -> List<String>,
    private val notes: () -> List<String>,
) : Harness {
    constructor(host: HarnessHost) : this(host, ConfigFirstModels(cli = { listModels(host) }))

    private constructor(host: HarnessHost, models: ConfigFirstModels) : this(host, { resolve(host) }, models, models::notes)

    override val id: String = OpenCodeCommand.HARNESS_ID

    override val capabilities: Set<HarnessCapability> = setOf(
        HarnessCapability.MODEL_SELECTION, // -m
        HarnessCapability.SESSION_RESUME, // --session
        HarnessCapability.TOOL_CALLS,
        HarnessCapability.REASONING,
        HarnessCapability.USAGE, // step_finish tokens
    )

    /** One-shot `opencode run`. The `--auto` flag for allowAll is NOT added here: the host appends [approvalArgs]. allowEdits rides the inline config. */
    override fun process(request: HarnessRequest): HarnessProcess = HarnessProcess(
        command = OpenCodeCommand.command(executable(), OpenCodeCommand.effectiveModel(request.model), request.sessionId),
        env = OpenCodePermissions.runEnv(request.toolApproval, mcp = host.mcpLaunch(emptyMap())),
    )

    override fun newTranslator(): HarnessTranslator = OpenCodeTranslator()

    override val contextLimit: ContextLimitCapability = OpenCodeCommand.CONTEXT_LIMIT

    override val effort: EffortCapability = OpenCodeCommand.EFFORT

    override fun approvalArgs(approval: ToolApproval): List<String> = OpenCodeCommand.approvalArgs(approval)

    override fun commands(): List<HarnessCommandSpec> = OpenCodeCommand.COMMANDS

    override fun mcpToolRule(serverName: String): String? = "${serverName}_*"

    override fun mergeConfig(existing: String?, parts: ConfigParts): String = OpenCodeConfig.merge(existing, parts)

    override fun session(): HarnessSession = OpenCodeServeSession(host, executable)

    override fun stats(): HarnessStats = HarnessStats { psid -> OpenCodeStats.read(host, java.io.File(System.getProperty("user.home")), psid) }

    override fun models(): List<HarnessModels> = OpenCodeCommand.models(modelLister(), notes())

    companion object {
        private fun resolve(host: HarnessHost): String =
            host.resolveCli(OpenCodeCommand.CLI_ID) ?: error("the opencode CLI was not found (set its location in Settings, AGENTHQ_OPENCODE_EXE, or install opencode)")

        /** `opencode models`, through the host's helper-process service; a failure throws so the daemon can report the reason. */
        internal fun listModels(host: HarnessHost): List<String> {
            val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
            val exit = CompletableFuture<Int>()
            val process = host.start(HarnessProcess(listOf(resolve(host), "models")), object : HarnessProcessListener {
                override fun onStdout(line: String) { lines += line }
                override fun onStderr(line: String) { lines += line }
                override fun onExit(exitCode: Int) { exit.complete(exitCode) }
            })
            process.closeInput()
            val code = try { exit.get(30, TimeUnit.SECONDS) } catch (e: java.util.concurrent.TimeoutException) {
                process.killTree()
                error("opencode models timed out")
            }
            check(code == 0) { "opencode models exited $code" }
            return lines.toList()
        }
    }
}

/**
 * The opencode model source: the user's config FILES first ([OpenCodeConfigReader]: instant, reflects an edit on the next read), and `opencode models`
 * only when the files name no model at all. The CLI answer is kept [cliTtlMs] so a daemon asking every few seconds does not spawn a process each time.
 */
class ConfigFirstModels(
    private val reader: OpenCodeConfigReader = OpenCodeConfigReader(OpenCodeConfigLocations.fromEnvironment()),
    private val cli: () -> List<String>,
    private val cliTtlMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : () -> List<String> {
    private var cliMemo: Pair<Long, List<String>>? = null

    override fun invoke(): List<String> {
        reader.read().modelIds().takeIf { it.isNotEmpty() }?.let { return it }
        return synchronized(this) {
            val now = clock()
            cliMemo?.takeIf { now - it.first < cliTtlMs }?.second ?: cli().also { cliMemo = now to it }
        }
    }

    /** Config problems (unreadable file, missing model cache) to show beside the catalogue. */
    fun notes(): List<String> = reader.read().notes
}
