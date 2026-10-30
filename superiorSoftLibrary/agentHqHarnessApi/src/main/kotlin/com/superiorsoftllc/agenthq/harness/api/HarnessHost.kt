package com.superiorsoftllc.agenthq.harness.api

import java.io.File

/**
 * HARNESS-PLUGIN-3 H3: everything a harness plugin may call on the daemon, handed to it through [HarnessRegistrar.host],
 * never a static. Scoped to ONE module: its own data folder, its own declared settings and CLI, its granted hooks.
 *
 * A call the module did not declare (or, for a community module, was not granted in modules.json) is refused: the
 * getters answer null, [start] throws [SecurityException], [ask] answers [HarnessAnswer.NONE]; each refusal is logged with
 * the module's name. Grants: `harness.cli` ([resolveCli], [start]), `harness.setting:<key>` ([setting]),
 * `harness.hook.mcp` ([mcpLaunch]), `harness.hook.watch` ([watchLauncher]), `harness.hook.ask` ([ask]).
 */
interface HarnessHost {
    /** The module this host belongs to (module.json `name`). */
    val moduleId: String

    /** The [HARNESS_API_VERSION] the daemon implements. */
    val apiVersion: String

    /** This module's own data folder (`<state>/plugin-data/<Module>/`), created on first use. */
    val stateDir: File

    /** One line in the daemon log, prefixed with the module name. */
    fun log(line: String)

    /** The value of a setting the module declared in `harness.settings`; null when unset or undeclared. */
    fun setting(key: String): String?

    /**
     * Where the module's own CLI ([tool] = module.json `harness.cli.id`) resolves on this machine (the owner's Settings
     * location, the tool's install locations, PATH); null when it is not installed, or [tool] is not the declared one.
     */
    fun resolveCli(tool: String): String?

    /**
     * Start a helper process (a model listing, a version probe). NOT for the agent run itself: that is [Harness.process],
     * which the daemon launches under the session's policy. Throws [SecurityException] when not declared/granted.
     */
    fun start(process: HarnessProcess, listener: HarnessProcessListener): HarnessRunningProcess

    /** The command that starts the agenthq MCP server for a session whose environment is [sessionEnv]; null when not granted. */
    fun mcpLaunch(sessionEnv: Map<String, String>): HarnessProcess?

    /** The watched-runner launcher a harness hook rewrites commands to, when watch is ON for that session; else null. */
    fun watchLauncher(sessionEnv: Map<String, String>): String?

    /** Ask the owner a question on behalf of [sessionId]; suspends until answered, timed out or the session ends. */
    suspend fun ask(sessionId: String, question: HarnessQuestion): HarnessAnswer
}

interface HarnessProcessListener {
    fun onStdout(line: String)
    fun onStderr(line: String)
    fun onExit(exitCode: Int)
}

interface HarnessRunningProcess {
    val isAlive: Boolean
    fun writeInput(text: String)
    fun closeInput()
    fun killTree()
}

data class HarnessQuestion(
    val question: String,
    val options: List<String>,
    /** The option a timer picks at expiry; null = none. */
    val recommended: String? = null,
    val timeoutMillis: Long? = null,
)

data class HarnessAnswer(val answers: List<String>, val otherText: String?, val answered: Boolean) {
    companion object {
        val NONE = HarnessAnswer(emptyList(), null, answered = false)
    }
}
