package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.agenthq.harness.api.HarnessAnswer
import com.superiorsoftllc.agenthq.harness.api.HarnessHost
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.harness.api.HarnessProcessListener
import com.superiorsoftllc.agenthq.harness.api.HarnessQuestion
import com.superiorsoftllc.agenthq.harness.api.HarnessRunningProcess
import com.superiorsoftllc.agenthq.runner.PipedProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import java.io.File

/**
 * HARNESS-PLUGIN-3 H3: the daemon services behind every [HarnessHost], as plain functions so the harness package names no
 * daemon class. The daemon builds one at start (DaemonMain); [NONE] answers nothing (tests, a daemon without the service).
 */
class HarnessHostServices(
    /** A module's stored setting (`<module>.<key>` in the daemon config). */
    val setting: (module: String, key: String) -> String? = { _, _ -> null },
    /** Where CLI [id] (command [command]) resolves: the executable resolver (Settings override, install locations, PATH). */
    val resolveCli: (id: String, command: String) -> String? = { _, _ -> null },
    val runner: ProcessRunner = PipedProcessRunner(),
    val mcpLaunch: (sessionEnv: Map<String, String>) -> HarnessProcess? = { null },
    val watchLauncher: (sessionEnv: Map<String, String>) -> String? = { null },
    val ask: suspend (sessionId: String, question: HarnessQuestion) -> HarnessAnswer = { _, _ -> HarnessAnswer.NONE },
) {
    companion object {
        val NONE = HarnessHostServices()
    }
}

/** What one module DECLARED in its module.json `harness` block, as far as its host is concerned. */
data class HarnessHostDeclaration(
    val settings: List<String> = emptyList(),
    /** `harness.cli.id` / `harness.cli.command`; null = the module declares no CLI of its own. */
    val cliId: String? = null,
    val cliCommand: String? = null,
    val hooks: Set<String> = emptySet(),
)

/**
 * HARNESS-PLUGIN-3 H3: the [HarnessHost] of ONE module. Every call checks two things: the module DECLARED it (module.json),
 * and, for a community module, the owner GRANTED it ([granted]: always true for bundled / official). A refusal never throws
 * except [start] (a caller that ignored null would launch nothing anyway); each is logged with the module name.
 */
class ModuleHarnessHost(
    override val moduleId: String,
    stateRoot: File,
    private val declared: HarnessHostDeclaration,
    private val granted: (String) -> Boolean,
    private val services: HarnessHostServices,
    private val logSink: (String) -> Unit,
) : HarnessHost {
    override val apiVersion: String = HARNESS_API_VERSION
    override val stateDir: File by lazy { File(stateRoot, "plugin-data/$moduleId").apply { mkdirs() } }

    override fun log(line: String) = logSink("[harness $moduleId] $line")

    private fun refused(what: String): Nothing? { log("$what: refused"); return null }

    override fun setting(key: String): String? = when {
        key !in declared.settings -> refused("setting $key is not declared in harness.settings")
        !granted("harness.setting:$key") -> refused("setting $key is not granted (harness.setting:$key)")
        else -> services.setting(moduleId, key)
    }

    override fun resolveCli(tool: String): String? = when {
        // H6c: `node` is the runtime of a JS CLI (CLIPATH-2); a module that declares a CLI may ask where the daemon resolves it.
        declared.cliId == null || (tool != declared.cliId && tool != NODE_RUNTIME) -> refused("cli $tool is not the declared harness.cli.id")
        !granted(CLI_GRANT) -> refused("cli $tool is not granted ($CLI_GRANT)")
        else -> services.resolveCli(tool, if (tool == declared.cliId) declared.cliCommand ?: tool else tool)
    }

    override fun start(process: HarnessProcess, listener: HarnessProcessListener): HarnessRunningProcess {
        if (declared.cliId == null || !granted(CLI_GRANT)) {
            log("start ${process.command.firstOrNull().orEmpty().take(60)}: refused")
            throw SecurityException("harness module $moduleId may not start processes (declare harness.cli; a community module also needs the $CLI_GRANT grant)")
        }
        val running = services.runner.start(
            ProcessSpec(process.command, process.workingDir, process.env),
            object : ProcessListener {
                override fun onStdout(line: String) = listener.onStdout(line)
                override fun onStderr(line: String) = listener.onStderr(line)
                override fun onExit(exitCode: Int) = listener.onExit(exitCode)
            },
        )
        return object : HarnessRunningProcess {
            override val isAlive: Boolean get() = running.isAlive
            override fun writeInput(text: String) = running.writeInput(text)
            override fun closeInput() = running.closeInput()
            override fun killTree() = running.killTree()
        }
    }

    private fun hook(kind: String): Boolean = when {
        kind !in declared.hooks -> { log("hook $kind is not declared in harness.hooks: refused"); false }
        !granted("harness.hook.$kind") -> { log("hook $kind is not granted (harness.hook.$kind): refused"); false }
        else -> true
    }

    /** H5c / H5d: true when hook [kind] is declared AND granted (logs the refusal otherwise); used by the daemon-side contribution adapters. */
    fun hookAllowed(kind: String): Boolean = hook(kind)

    override fun mcpLaunch(sessionEnv: Map<String, String>): HarnessProcess? = if (hook("mcp")) services.mcpLaunch(sessionEnv) else null

    override fun watchLauncher(sessionEnv: Map<String, String>): String? = if (hook("watch")) services.watchLauncher(sessionEnv) else null

    override suspend fun ask(sessionId: String, question: HarnessQuestion): HarnessAnswer =
        if (hook("ask")) services.ask(sessionId, question) else HarnessAnswer.NONE

    companion object {
        const val CLI_GRANT = "harness.cli"

        /** H6c: the one extra tool [resolveCli] answers beside the declared CLI: the node runtime that runs a JS CLI. */
        const val NODE_RUNTIME = "node"

        /** The hook kinds module.json `harness.hooks` may name. watch/mcp/ask are host calls; sandbox/stats/usage are H5 contributions. */
        val HOOK_KINDS: Set<String> = setOf("watch", "mcp", "ask", "sandbox", "stats", "usage", "session", "models")
    }
}
