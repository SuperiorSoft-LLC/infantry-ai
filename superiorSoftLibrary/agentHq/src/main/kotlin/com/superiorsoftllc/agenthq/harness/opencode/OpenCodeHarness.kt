package com.superiorsoftllc.agenthq.harness.opencode

import com.superiorsoftllc.agenthq.harness.ContextLimitCapability
import com.superiorsoftllc.agenthq.harness.ContextLimitSupport
import com.superiorsoftllc.agenthq.harness.EffortCapability
import com.superiorsoftllc.agenthq.harness.EffortSupport
import com.superiorsoftllc.agenthq.harness.HarnessCommand
import com.superiorsoftllc.agenthq.opencode.OpenCodeCommand
import com.superiorsoftllc.agenthq.opencode.OpenCodeCommands
import com.superiorsoftllc.agenthq.opencode.OpenCodeServeAdapter
import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter

/**
 * HARNESS H8a: the opencode harness as ONE layer, still in core (H8b moves it to a plugin). Everything the
 * [com.superiorsoftllc.agenthq.provider.OpenCodeProvider] used to decide itself (argv, default model, model grouping,
 * the honest context-limit / effort answers, the `/` catalog, the persistent serve adapter) lives here; the provider only
 * adapts it to `AgentProvider`. Behaviour identical; opencode is kept for testing/demo, so no feature work.
 *
 * Contributions that stay where they are until H8b (marked `// H8b` at the call sites): the serve adapter's permission
 * mapping ([com.superiorsoftllc.agenthq.opencode.OpenCodePermissions]), the MCP config merge
 * (`AgentHqMcp.mergeOpenCode`) and the watch plugin (`WatchHooks.mergeOpenCodePlugin`).
 */
object OpenCodeHarness {
    const val HARNESS_ID = "opencode"

    val commands: List<HarnessCommand> get() = OpenCodeCommands.catalog

    /** Verified on opencode 1.18.35: a window is only settable through a full per-model limit override (context + output). */
    val contextLimit: ContextLimitCapability = ContextLimitCapability(
        ContextLimitSupport.UNSUPPORTED,
        "opencode sets its window only through a full per-model limit override (context + output); not mapped yet",
    )

    /** `--variant` exists but was not verified end to end, and the serve mode has no variant input the daemon uses. */
    val effort: EffortCapability = EffortCapability(
        EffortSupport.UNSUPPORTED,
        "opencode has only a provider-specific --variant flag, not verified end to end; not mapped yet",
    )

    /** The `opencode run` process for [request]. */
    fun specFor(request: RunRequest): ProcessSpec = OpenCodeCommand.spec(
        workingDir = request.workingDir,
        sessionId = request.resumeSessionId,
        model = request.model,
        toolApproval = request.toolApproval,
    )

    /** `AGENTHQ_OPENCODE_MODEL`, else the free default. Never null: opencode always gets `-m`. */
    fun defaultModel(): String = OpenCodeCommand.model()

    /** Persistent mode: `opencode serve`. */
    fun sessionAdapter(): HarnessSessionAdapter = OpenCodeServeAdapter()

    fun isModelId(s: String): Boolean = s.isNotEmpty() && s.contains('/') && s.none(Char::isWhitespace)

    /** The picker rows for [lines] (`provider/model` ids), grouped by vendor; [notes] make a broken config visible. */
    fun models(lines: List<String>, notes: List<String>): List<ProviderModels> {
        val byVendor = LinkedHashMap<String, MutableList<String>>()
        for (line in lines.map { it.trim() }) {
            val vendor = line.substringBefore('/').takeIf { '/' in line && it.isNotEmpty() } ?: continue
            byVendor.getOrPut(vendor) { mutableListOf() }.add(line)
        }
        if (byVendor.isEmpty()) {
            val why = (listOf("opencode reported no configured models") + notes).joinToString("; ")
            return listOf(ProviderModels(HARNESS_ID, "", HARNESS_ID, emptyList(), why))
        }
        val groups = byVendor.map { (vendor, ids) -> ProviderModels(HARNESS_ID, vendor, vendor, ids) }
        return if (notes.isEmpty()) groups else groups + ProviderModels(HARNESS_ID, "config", "config notes", emptyList(), notes.joinToString("; "))
    }
}
