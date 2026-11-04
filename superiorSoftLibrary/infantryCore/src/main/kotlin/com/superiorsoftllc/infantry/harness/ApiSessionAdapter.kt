// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.harness.api.ApprovalReply
import com.superiorsoftllc.infantry.harness.api.HarnessSession
import com.superiorsoftllc.infantry.prompts.Decision
import com.superiorsoftllc.infantry.harness.api.SessionConnection
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.session.host.AdapterSink
import com.superiorsoftllc.infantry.session.host.HarnessSessionAdapter
import com.superiorsoftllc.infantry.session.host.HostedProcess
import com.superiorsoftllc.infantry.session.host.TurnEnd
import com.superiorsoftllc.infantry.harness.api.HarnessRequest as ApiRequest
import com.superiorsoftllc.infantry.harness.api.ToolApproval as ApiToolApproval

/**
 * HARNESS H6a (API 2.8.0): a plugin's [HarnessSession] as the daemon's [HarnessSessionAdapter]. The session host keeps owning the lifecycle
 * (one process per session, reuse, restart, idle stop); the plugin gives the command and the line protocol. Every call into plugin code is
 * caught and attributed through [onFailure]; a throwing plugin ends its own turn as FAILED and never the host.
 */
internal class ApiSessionAdapter(
    private val harnessId: String,
    private val pluginId: String,
    private val session: HarnessSession,
    private val onFailure: (PluginFailure) -> Unit,
    /** H6c: the daemon's additions to the launched spec (watch, sandbox, launch extras) for a session with env `env`. */
    private val decorate: (ProcessSpec, Map<String, String>) -> ProcessSpec = { spec, _ -> spec },
) : HarnessSessionAdapter {
    override val label: String = runCatching { session.label }.getOrDefault(harnessId)
    override val bootTimeoutMs: Long = runCatching { session.bootTimeoutMs }.getOrDefault(60_000L)

    /** CLI-LIVE-1: the session type's own idle limit (0 = never); null or a throw = the host's global keep-alive. */
    override fun idleMinutes(global: Int): Int =
        runCatching { session.idleMinutes() }.getOrNull()?.let { if (it <= 0) Int.MAX_VALUE else it } ?: global

    /** The resume id is never part of the fingerprint: a changed model or effort restarts the process and resumes. */
    override fun fingerprint(request: RunRequest, env: Map<String, String>): String =
        attributed { session.fingerprint(request.toApi(null), env) }

    override fun launch(request: RunRequest, env: Map<String, String>, runner: ProcessRunner, sink: AdapterSink): HostedProcess {
        val spec = attributed { session.launch(request.toApi(request.resumeSessionId?.takeIf { it.isNotBlank() }), env) }
        val process = Hosted()
        val out = Output(process, sink)
        process.connection = attributed { session.connect(out, spec) }
        process.start(runner, decorate(ProcessSpec(spec.command, spec.workingDir ?: request.workingDir, env + spec.env), env), sink)
        return process
    }

    override fun sendTurn(process: HostedProcess, request: RunRequest) {
        val p = process as Hosted
        try { p.connection.sendTurn(request.toApi(request.resumeSessionId)) } catch (t: Throwable) { p.fail(t) }
    }

    override fun abortTurn(process: HostedProcess): Boolean = try { (process as Hosted).connection.abortTurn() } catch (t: Throwable) { (process as Hosted).fail(t); false }

    override fun close(process: HostedProcess): Boolean = try { (process as Hosted).connection.close() } catch (t: Throwable) { onFailure(PluginFailure(pluginId, PluginFailurePhase.RUN, t)); false }

    private fun <T> attributed(block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        throw HarnessPluginCallException(PluginFailure(pluginId, PluginFailurePhase.RUN, t).also(onFailure))
    }

    private fun RunRequest.toApi(resume: String?) = ApiRequest(
        prompt, harnessId, workingDir?.path.orEmpty(), model, resume, effort,
        when (toolApproval) {
            ToolApproval.DENY -> ApiToolApproval.DENY
            ToolApproval.ALLOW_EDITS -> ApiToolApproval.ALLOW_EDITS
            ToolApproval.ALLOW_ALL -> ApiToolApproval.ALLOW_ALL
            ToolApproval.ASK -> ApiToolApproval.ASK
        },
        contextLimit,
    )

    private class Output(private val process: Hosted, private val sink: AdapterSink) : SessionOutput {
        override fun write(text: String): Boolean = process.child?.let { runCatching { it.writeInput(text) }.isSuccess } ?: false
        override fun closeInput() { process.child?.let { runCatching { it.closeInput() } } }
        override fun detail(text: String) = sink.detail(text)
        // H8b-2 (API 2.12): a record the plugin read off its own channel (opencode serve's SSE), as if the process printed it.
        override fun output(line: String) = sink.output(line)
        override fun diagnostic(line: String) = sink.diagnostic(line)
        override fun requestApproval(tool: String, input: String): ApprovalReply = when (sink.requestApproval(tool, input)) {
            Decision.ALLOW_ONCE -> ApprovalReply.ALLOW_ONCE
            Decision.ALLOW_SESSION -> ApprovalReply.ALLOW_SESSION
            Decision.ALLOW_ALWAYS -> ApprovalReply.ALLOW_ALWAYS
            Decision.DENY -> ApprovalReply.DENY
        }
        override fun providerSession(id: String) = sink.providerSession(id)
        override fun turnEnded(end: SessionTurnEnd, exitCode: Int?, reason: String?) {
            sink.turnEnded(TurnEnd.valueOf(end.name), exitCode, reason)
        }
    }

    private inner class Hosted : HostedProcess {
        private val lock = Any()
        private val handlers = mutableListOf<(Int) -> Unit>()
        @Volatile private var exit: Int? = null
        @Volatile var child: RunningProcess? = null
        @Volatile lateinit var connection: SessionConnection
        @Volatile private var sink: AdapterSink? = null

        override val pid: Long? get() = child?.pid
        override val isAlive: Boolean get() = exit == null && child?.isAlive == true

        override fun onExit(handler: (exitCode: Int) -> Unit) {
            val code = synchronized(lock) { exit ?: run { handlers += handler; null } } ?: return
            handler(code)
        }

        override fun killTree() { child?.let { runCatching { it.killTree() } } }

        fun fail(t: Throwable) {
            val f = PluginFailure(pluginId, PluginFailurePhase.RUN, t).also(onFailure)
            sink?.turnEnded(TurnEnd.FAILED, null, f.message)
        }

        fun start(runner: ProcessRunner, spec: ProcessSpec, sink: AdapterSink) {
            this.sink = sink
            child = runner.start(spec, object : ProcessListener {
                override fun onStdout(line: String) {
                    sink.output(line)
                    try { connection.onStdout(line) } catch (t: Throwable) { fail(t) }
                }

                override fun onStderr(line: String) {
                    sink.diagnostic(line)
                    try { connection.onStderr(line) } catch (t: Throwable) { fail(t) }
                }

                override fun onExit(exitCode: Int) {
                    val hs = synchronized(lock) {
                        if (exit != null) return
                        exit = exitCode
                        handlers.toList().also { handlers.clear() }
                    }
                    hs.forEach { runCatching { it(exitCode) } }
                }
            })
        }
    }
}
