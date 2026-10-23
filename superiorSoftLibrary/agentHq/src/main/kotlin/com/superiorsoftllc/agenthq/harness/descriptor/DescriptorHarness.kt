package com.superiorsoftllc.agenthq.harness.descriptor

import com.superiorsoftllc.agenthq.harness.CliHarness
import com.superiorsoftllc.agenthq.harness.HARNESS_CONTRACT_VERSION
import com.superiorsoftllc.agenthq.harness.HarnessCapability
import com.superiorsoftllc.agenthq.harness.HarnessCommand
import com.superiorsoftllc.agenthq.harness.HarnessPlugin
import com.superiorsoftllc.agenthq.harness.HarnessRegistrar
import com.superiorsoftllc.agenthq.provider.HarnessModelCatalog
import com.superiorsoftllc.agenthq.runner.ProcessRunner

/**
 * HARNESS-DESC-1 - a [CliHarness] built from one [HarnessDescriptor] instead of from code. The shared [CliHarness]
 * runs the child and the [DescriptorOutput] translator reads its stdout, so a descriptor harness is spawnable
 * (WIRE-4) and parity-checked exactly like a Kotlin one; only the argv and the stream reading come from data.
 */
class DescriptorHarness(
    /** BUG-PERM-4: internal so the Tool approval matrix can read the mapping this harness was built from. */
    internal val descriptor: HarnessDescriptor,
    runner: ProcessRunner,
    /** BUG-PERM-3: where the "Tool approval unmapped" line goes (the daemon terminal by default). */
    log: (String) -> Unit = ::println,
) : CliHarness(
    runner,
    // CLIPATH-1: the command goes through the one resolver (Settings override, then PATH). Unresolved, the bare command
    // is kept so the OS has its own say at spawn; the `providers` / `cli_locations` frames already report it as not found.
    { request ->
        noteUnmapped(descriptor, request.toolApproval, log)
        descriptor.spec(request).let { spec -> spec.copy(command = listOf(executableFor(descriptor)) + spec.command.drop(1)) }
    },
    { descriptor.output.newTranslator(descriptor.id) },
    cliName = descriptor.id,
) {
    override val id: String = descriptor.id

    // Written out on purpose, as in every plugin: this inlines the const when THIS code is compiled.
    override val contractVersion: String = HARNESS_CONTRACT_VERSION

    override val capabilities: Set<HarnessCapability> = descriptor.capabilities

    override val commands: List<HarnessCommand> = descriptor.commands

    /** CLIPATH-1: the CLI this harness launches, for the resolver ([com.superiorsoftllc.agenthq.executables.CliTools.forCommand]). */
    val cliTool: com.superiorsoftllc.agenthq.executables.CliTool = cliToolOf(descriptor)

    /** The descriptor's display name; the contract has no place for it yet, so it is only readable here. */
    val displayName: String = descriptor.name

    /**
     * ROLE-FIRST S5: this harness's model listing, from the descriptor's `models` section (null = none). Read by the
     * daemon through [com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs], not by the plugin contract.
     */
    val modelCatalog: HarnessModelCatalog? = descriptor.models?.let { DescriptorModelCatalog(descriptor.id, it) }
}

/** BUG-PERM-3: "id/mode" pairs already logged as unmapped, so the line appears once per daemon, not once per run. */
private val loggedUnmapped = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

/** Logs, once per descriptor and mode, that [mode] has no `toolApproval` mapping and the run keeps today's behaviour. */
internal fun noteUnmapped(descriptor: HarnessDescriptor, mode: com.superiorsoftllc.agenthq.harness.ToolApproval, log: (String) -> Unit) {
    if (!descriptor.unmappedToolApproval(mode)) return
    if (loggedUnmapped.add(descriptor.id + "/" + mode.wire)) {
        log("[agentHq] harness " + descriptor.id + ": Tool approval '" + mode.wire + "' is unmapped (no 'toolApproval' entry); the CLI keeps its own default")
    }
}

/** Test seam: forget what [noteUnmapped] already logged. */
internal fun resetUnmappedLog() = loggedUnmapped.clear()

private fun cliToolOf(descriptor: HarnessDescriptor) =
    com.superiorsoftllc.agenthq.executables.CliTools.forCommand(descriptor.id, descriptor.name, descriptor.command)

private fun executableFor(descriptor: HarnessDescriptor): String =
    com.superiorsoftllc.agenthq.executables.CliExecutables.resolve(cliToolOf(descriptor)).path ?: descriptor.command

/** A descriptor harness registered the way any plugin's is, so the registry needs no second path. */
internal class DescriptorHarnessPlugin(private val harness: DescriptorHarness) : HarnessPlugin {
    override val pluginId: String = "descriptor:${harness.id}"
    override val contractVersion: String = HARNESS_CONTRACT_VERSION

    override fun register(registrar: HarnessRegistrar) = registrar.register(harness)
}
