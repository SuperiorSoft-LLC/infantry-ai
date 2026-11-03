// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.wiring

import com.superiorsoftllc.infantry.daemon.InfantryMcp
import com.superiorsoftllc.infantry.daemon.DaemonConfig
import com.superiorsoftllc.infantry.daemon.WatchHooks
import com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost
import com.superiorsoftllc.infantry.daemon.plugins.HarnessModules
import com.superiorsoftllc.infantry.executables.CliTools
import com.superiorsoftllc.infantry.executables.ExecutableResolver
import com.superiorsoftllc.infantry.harness.HarnessHostServices
import com.superiorsoftllc.infantry.harness.HarnessRegistry
import com.superiorsoftllc.infantry.harness.api.HarnessAnswer
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.prompts.PendingPrompts
import com.superiorsoftllc.infantry.prompts.QuestionSpec
import com.superiorsoftllc.infantry.protocol.PromptOption
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** HARNESS-PLUGIN-3 H4: builds the daemon's [HarnessModules], cut from DaemonMain (CORE-SPLIT-4a3). `modulesRef` is read lazily (resolveCli runs after it is set). */
object HarnessModulesWiring {
    fun build(
        harnesses: HarnessRegistry, trust: DaemonPluginHost.Trust, daemonConfig: DaemonConfig, modulesRef: AtomicReference<HarnessModules?>,
        executableResolver: ExecutableResolver, infantryBin: File?, pendingPrompts: PendingPrompts, stateDir: File, reserved: () -> Set<String>,
    ): HarnessModules = HarnessModules(
        harnesses, trust,
        HarnessHostServices(
            setting = { module, key -> daemonConfig.value(module, key) },
            resolveCli = { cliId, command ->
                // H6c: the daemon's own table first (the pi plugin names its entry); H5a: else the module's own manifest tool (its place in the installer's prefix included), else PATH.
                val tool = CliTools.KNOWN.firstOrNull { it.id == cliId } ?: modulesRef.get()?.cliTool(cliId) ?: CliTools.forCommand(cliId, cliId, command)
                executableResolver.resolve(tool).path
            },
            mcpLaunch = { env -> InfantryMcp.launch(env).let { HarnessProcess(it.argv, env = it.env) } },
            watchLauncher = { env -> if (WatchHooks.on(env)) WatchHooks.launcher(infantryBin) ?: "infantry" else null },
            queryDb = { file, sql, params -> com.superiorsoftllc.infantry.stats.ReadOnlyDb.query(file, sql, params) },
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
