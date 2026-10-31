package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.opencode.OpenCodeCommand
import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.provider.providerconfig.OpenCodeConfigLocations
import com.superiorsoftllc.agenthq.provider.providerconfig.OpenCodeConfigReader

/**
 * The Phase 0/1 provider, unchanged in behaviour: `opencode run --format json`, prompt on stdin.
 *
 * Phase 2 only lifted the argv construction behind [AgentProvider] and added the session-resume
 * argument (`--session <id>`), which `opencode run` already exposed.
 */
class OpenCodeProvider(
    /**
     * The `provider/model` ids on offer, injectable so tests need neither the CLI nor a home directory.
     * The default ([ConfigFirstModels]) reads the user's opencode config files and only asks the CLI
     * when those yield nothing.
     */
    private val modelLister: () -> List<String> = ConfigFirstModels(),
    /**
     * BUG-M2-11b-2: opencode's persistent mode ([com.superiorsoftllc.agenthq.opencode.OpenCodeServeAdapter]), or null
     * when it has none. [ProviderFactory] passes one; a bare construction (tests) has none, so the host runs it one-shot.
     */
    private val sessionAdapter: com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter?,
) : AgentProvider {
    /** No persistent mode; keeps `OpenCodeProvider { models }` and `OpenCodeProvider(lister)` working. */
    constructor(modelLister: () -> List<String> = ConfigFirstModels()) : this(modelLister, null)

    override val id: String = "opencode"

    /**
     * CONTEXT-LIMIT-1: not mapped, reported honestly. Verified on opencode 1.18.35: its compaction threshold is the model's
     * `limit.input - compaction.reserved` (or `limit.context - output`), and an inline override of a model's limit is refused
     * unless it also names `limit.output` ("Missing key ...limit.output"), which the daemon does not know per model.
     */
    override fun contextLimit(): com.superiorsoftllc.agenthq.harness.ContextLimitCapability =
        com.superiorsoftllc.agenthq.harness.ContextLimitCapability(
            com.superiorsoftllc.agenthq.harness.ContextLimitSupport.UNSUPPORTED,
            "opencode sets its window only through a full per-model limit override (context + output); not mapped yet",
        )

    /**
     * EFFORT-1: not mapped, reported honestly. `opencode run --help` (1.18.35) lists `--variant` ("provider-specific reasoning
     * effort"), but a live `opencode run --variant high` did not finish within 60 s here, so the flag is not verified and the
     * persistent (serve) mode has no variant input the daemon uses.
     */
    override fun effort(): com.superiorsoftllc.agenthq.harness.EffortCapability =
        com.superiorsoftllc.agenthq.harness.EffortCapability(
            com.superiorsoftllc.agenthq.harness.EffortSupport.UNSUPPORTED,
            "opencode has only a provider-specific --variant flag, not verified end to end; not mapped yet",
        )

    override val commands: List<com.superiorsoftllc.agenthq.harness.HarnessCommand> =
        com.superiorsoftllc.agenthq.opencode.OpenCodeCommands.catalog

    override fun spec(request: RunRequest): ProcessSpec = OpenCodeCommand.spec(
        workingDir = request.workingDir,
        sessionId = request.resumeSessionId,
        model = request.model,
        toolApproval = request.toolApproval,
    )

    override fun sessionIdFromLine(line: String): String? = SessionId.fromLine(line)

    override fun sessionAdapter(): com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter? = sessionAdapter

    /** `AGENTHQ_OPENCODE_MODEL`, else the free [OpenCodeCommand.DEFAULT_MODEL]. Never null: opencode always gets `-m`. */
    override fun defaultModel(): String = OpenCodeCommand.model()

    /**
     * The SAME list [models] groups, so the picker and the profile validation can never disagree about
     * which models exist. Null (= "cannot tell, accept anything") when the listing fails or is empty.
     */
    override fun knownModels(): List<String>? = try {
        modelLister().map { it.trim() }.filter(::isModelId).takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /** `opencode models` prints one model id per line (e.g. `opencode/deepseek-v4.1-flash-free`); keep those. */
    internal fun parseModels(output: String): List<String>? =
        output.lineSequence()
            .map { it.trim() }
            .filter(::isModelId)
            .toList()
            .takeIf { it.isNotEmpty() }

    override fun models(): List<ProviderModels> {
        val byVendor = LinkedHashMap<String, MutableList<String>>()
        for (line in modelLister().map { it.trim() }) {
            val vendor = line.substringBefore('/').takeIf { '/' in line && it.isNotEmpty() } ?: continue
            byVendor.getOrPut(vendor) { mutableListOf() }.add(line)
        }
        val notes = (modelLister as? ConfigFirstModels)?.notes().orEmpty()
        if (byVendor.isEmpty()) {
            val why = (listOf("opencode reported no configured models") + notes).joinToString("; ")
            return listOf(ProviderModels(id, "", id, emptyList(), why))
        }
        val groups = byVendor.map { (vendor, ids) -> ProviderModels(id, vendor, vendor, ids) }
        // A broken config file must be VISIBLE in the picker, not read as a smaller catalogue.
        return if (notes.isEmpty()) groups else groups + ProviderModels(id, "config", "config notes", emptyList(), notes.joinToString("; "))
    }

    private fun isModelId(s: String) = s.isNotEmpty() && s.contains('/') && s.none(Char::isWhitespace)
}

/**
 * The default opencode model source: the user's config FILES first ([OpenCodeConfigReader]: instant,
 * needs no executable, reflects an edit on the next read), and `opencode models` only when the files
 * name no model at all (a machine where the cache/auth files are not where we look). The CLI answer is
 * kept [cliTtlMs] so a daemon asking every few seconds does not spawn a process each time.
 */
class ConfigFirstModels(
    private val reader: OpenCodeConfigReader = OpenCodeConfigReader(OpenCodeConfigLocations.fromEnvironment()),
    private val cli: () -> List<String> = ::listOpenCodeModels,
    private val cliTtlMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : () -> List<String> {
    private var cliMemo: Pair<Long, List<String>>? = null

    override fun invoke(): List<String> {
        reader.read().modelIds().takeIf { it.isNotEmpty() }?.let { return it }
        return synchronized(this) {
            val now = clock()
            cliMemo?.takeIf { now - it.first < cliTtlMs }?.second
                ?: cli().also { cliMemo = now to it }
        }
    }

    /** Config problems (unreadable file, missing model cache) to show beside the catalogue. */
    fun notes(): List<String> = reader.read().notes
}

/** Runs `opencode models`; a failure throws so the daemon can report the reason. */
internal fun listOpenCodeModels(): List<String> {
    val proc = ProcessBuilder(OpenCodeCommand.resolveExecutable(), "models").redirectErrorStream(true).start()
    proc.outputStream.close()
    val out = proc.inputStream.bufferedReader().readLines()
    if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
        proc.destroyForcibly()
        error("opencode models timed out")
    }
    check(proc.exitValue() == 0) { "opencode models exited ${proc.exitValue()}" }
    return out
}
