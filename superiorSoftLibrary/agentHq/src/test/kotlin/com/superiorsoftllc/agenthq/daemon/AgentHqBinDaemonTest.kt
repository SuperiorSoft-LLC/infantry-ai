package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BUG-G2: a session the REAL daemon spawns gets the `agenthq` CLI's bin dir first on its PATH (and as
 * AGENTHQ_BIN), so the `agenthq knowledge` call P10-K9 teaches it resolves instead of "command not found".
 */
class AgentHqBinDaemonTest {

    private class RecordingRunner(private val inner: ProcessRunner) : ProcessRunner {
        val specs: MutableList<ProcessSpec> = CopyOnWriteArrayList()
        override val id: String = "recording-${inner.id}"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            return inner.start(spec, listener)
        }
    }

    @Test
    fun aSpawnedSessionHasTheAgentHqBinFirstOnItsPath() {
        val bin = Files.createTempDirectory("agenthq-bin").toFile()
        File(bin, "agenthq").writeText("#!/bin/sh\n")
        val script = StubScript.default()
        val runner = RecordingRunner(ScriptedRunner(script, delayMs = 0))
        val fixture = DaemonFixture.launch(script, runner = runner, agentHqBin = bin)
        try {
            fixture.client.await(Envelope.READY)
            fixture.client.send(Envelope(Envelope.PROMPT, "run agenthq knowledge"))
            fixture.await(what = "the run was spawned") { runner.specs.isNotEmpty() }

            val env = runner.specs.first().env
            val path = assertNotNull(env["PATH"], "the spawned process is given a PATH: $env")
            assertTrue(
                path.startsWith(bin.absolutePath + File.pathSeparator) || path == bin.absolutePath,
                "the agenthq bin dir must be FIRST on the session's PATH; was: $path",
            )
            val inherited = System.getenv("PATH")
            if (!inherited.isNullOrBlank()) assertTrue(path.endsWith(inherited), "the daemon's own PATH is kept after it")
            assertEquals(bin.absolutePath, env[AgentHqBin.BIN_ENV], "AGENTHQ_BIN names the directory")
        } finally {
            fixture.stop()
            fixture.deleteState()
            bin.deleteRecursively()
        }
    }

    /**
     * BUG-G2c: a daemon on a non-default port tells its sessions where it is. The CLI falls back to
     * ws://127.0.0.1:8787/ws without AGENTHQ_URL, so `agenthq knowledge` inside a session of a daemon on
     * 8799 reported "daemon not running on 127.0.0.1:8787".
     */
    @Test
    fun aSpawnedSessionIsToldTheDaemonsOwnUrl() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val script = StubScript.default()
        val runner = RecordingRunner(ScriptedRunner(script, delayMs = 0))
        val fixture = DaemonFixture.launch(script, runner = runner, port = port)
        try {
            fixture.client.await(Envelope.READY)
            fixture.client.send(Envelope(Envelope.PROMPT, "run agenthq knowledge"))
            fixture.await(what = "the run was spawned") { runner.specs.isNotEmpty() }

            // An operator's explicit AGENTHQ_URL wins; otherwise the daemon's own address.
            val expected = System.getenv("AGENTHQ_URL")?.takeIf { it.isNotBlank() } ?: "ws://127.0.0.1:$port/ws"
            assertEquals(expected, runner.specs.first().env["AGENTHQ_URL"], "the session must be told the daemon's URL")
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
