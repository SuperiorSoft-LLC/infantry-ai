package com.superiorsoftllc.agenthq.daemon.wiring

import com.superiorsoftllc.agenthq.daemon.AgentHqMcp
import com.superiorsoftllc.agenthq.daemon.DaemonConfig
import com.superiorsoftllc.agenthq.daemon.WatchHooks
import com.superiorsoftllc.agenthq.daemon.plugins.DaemonPluginHost
import com.superiorsoftllc.agenthq.daemon.plugins.HarnessModules
import com.superiorsoftllc.agenthq.executables.CliTools
import com.superiorsoftllc.agenthq.executables.ExecutableResolver
import com.superiorsoftllc.agenthq.harness.HarnessHostServices
import com.superiorsoftllc.agenthq.harness.HarnessRegistry
import com.superiorsoftllc.agenthq.harness.api.HarnessAnswer
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.prompts.PendingPrompts
import com.superiorsoftllc.agenthq.prompts.QuestionSpec
import com.superiorsoftllc.agenthq.protocol.PromptOption
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** HARNESS-PLUGIN-3 H4: builds the daemon's [HarnessModules], cut from DaemonMain (CORE-SPLIT-4a3). `modulesRef` is read lazily (resolveCli runs after it is set). */
object HarnessModulesWiring {
    fun build(
        harnesses: HarnessRegistry, trust: DaemonPluginHost.Trust, daemonConfig: DaemonConfig, modulesRef: AtomicReference<HarnessModules?>,
        executableResolver: ExecutableResolver, agentHqBin: File?, pendingPrompts: PendingPrompts, stateDir: File, reserved: () -> Set<String>,
    ): HarnessModules = HarnessModules(
        harnesses, trust,
        HarnessHostServices(
            setting = { module, key -> daemonConfig.value(module, key) },
            resolveCli = { cliId, command ->
                // H5a: the module's own manifest tool (its place in the installer's prefix included), else PATH.
                val tool = modulesRef.get()?.cliTool(cliId) ?: CliTools.forCommand(cliId, cliId, command)
                executableResolver.resolve(tool).path
            },
            mcpLaunch = { env -> AgentHqMcp.launch(env).let { HarnessProcess(it.argv, env = it.env) } },
            watchLauncher = { env -> if (WatchHooks.on(env)) WatchHooks.launcher(agentHqBin) ?: "agenthq" else null },
            ask = { sessionId, q ->
                val answer = pendingPrompts.askQuestion(sessionId, QuestionSpec(
                    q.question, q.options.map { PromptOption(it) }, timeoutMillis = q.timeoutMillis, recommended = q.recommended,
                ))
                HarnessAnswer(answer.answers, answer.otherText, answer.answered)
            },
        ),
        stateDir,
        reserved = reserved,
    )
}
