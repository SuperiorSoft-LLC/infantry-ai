// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.session.JsonSessionStore
import java.io.File

/**
 * Shared test doubles for the mailbox suites, so the router tests and the agent-facing HTTP tests
 * exercise the SAME session machinery without copying it.
 */

/** A provider whose child reports a session id and echoes the resume id into argv. */
class FakeAgentProvider : AgentProvider {
    override val id = "fake"
    override fun spec(request: RunRequest) = ProcessSpec(listOf("fake", request.resumeSessionId ?: "FRESH"))
    override fun sessionIdFromLine(line: String) = if (line.contains("session_id")) "prov-123" else null
}

/**
 * A stand-in child. Records the prompt written to its stdin (so a test can prove a message reached
 * the console) and can end on demand ([exit]) or immediately.
 */
class ScriptedProcess(private val listener: ProcessListener) : RunningProcess {
    @Volatile private var alive = true
    val input = StringBuilder()
    override val pid = 4242L
    override val isAlive: Boolean get() = alive
    override fun writeInput(text: String) {
        input.append(text)
    }
    override fun closeInput() = Unit
    override fun killTree() {
        if (alive) {
            alive = false
            listener.onExit(1)
        }
    }
    override fun awaitExit(): Int = 0

    /** Prints one stdout [line], as the harness would. */
    fun say(line: String) = listener.onStdout(line)

    fun exit(code: Int = 0) {
        if (alive) {
            alive = false
            listener.onExit(code)
        }
    }
}

/**
 * @param autoExit true = every run finishes immediately (a session lands IDLE); false = the run
 *   stays BUSY until a test calls [ScriptedProcess.exit].
 * @param emitSessionId false = the child never reveals a session id (a non-resumable session).
 */
class ScriptedRunner(
    private val autoExit: Boolean = true,
    private val emitSessionId: Boolean = true,
    private val autoExitCode: Int = 0,
) : ProcessRunner {
    override val id = "fake"
    val specs = mutableListOf<ProcessSpec>()
    val processes = mutableListOf<ScriptedProcess>()
    var current: ScriptedProcess? = null
    override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
        specs += spec
        val p = ScriptedProcess(listener)
        processes += p
        current = p
        if (emitSessionId) listener.onStdout("{\"session_id\":\"prov-123\"}")
        if (autoExit) listener.onExit(autoExitCode)
        return p
    }
}

/** An in-memory mail store. */
class MemMailStore : MailStore {
    var rows: List<Message> = emptyList()
    override fun load(): List<Message> = rows
    override fun saveAll(messages: Collection<Message>) {
        rows = messages.toList()
    }
}

/** A fresh on-disk session store in the temp dir. */
fun tempSessionStore(): JsonSessionStore {
    val file = File.createTempFile("infantry-router", ".json")
    file.delete()
    return JsonSessionStore(file)
}
