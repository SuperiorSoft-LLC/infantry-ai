// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.provider

import com.superiorsoftllc.infantry.harness.RunRequest

import com.superiorsoftllc.infantry.harness.AgentProvider

import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Phase 2 flexibility proof, at zero cost and with no external CLI: a provider the seam has
 * never seen is wired BY CONFIGURATION and driven purely through [AgentProvider] + the
 * [ProcessRunner] transport. If adding a provider needed a new branch in the daemon, or a change to
 * `Envelope`, this test could not be written the way it is.
 */

/** A vendor-neutral fake: no real CLI, its own argv, its own (non-JSON) session marker. */
private class FakeProvider : AgentProvider {
    override val id: String = "fake"

    override fun spec(request: RunRequest): ProcessSpec =
        ProcessSpec(command = listOf("fake-agent", "--stdin"), workingDir = request.workingDir)

    override fun sessionIdFromLine(line: String): String? =
        line.removePrefix("SESSION ").takeIf { line.startsWith("SESSION ") }
}

/** A transport double: records what the seam sent it, then replays canned output. */
private class FakeRunner : ProcessRunner {
    override val id: String = "fake"

    var lastSpec: ProcessSpec? = null
    var deliveredPrompt: String? = null

    override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
        lastSpec = spec
        return object : RunningProcess {
            override val isAlive: Boolean = true
            private val input = StringBuilder()

            override fun writeInput(text: String) {
                input.append(text)
            }

            override fun closeInput() {
                deliveredPrompt = input.toString()
                listener.onStdout("SESSION fake-1")
                listener.onStdout("hello")
                listener.onStderr("noise")
                listener.onExit(0)
            }

            override fun killTree() = Unit
            override fun awaitExit(): Int = 0
        }
    }
}

class ProviderSeamTest {

    @Test
    fun registryDispatchesOnConfigurationName() {
        ProviderFactory.register("fake", ::FakeProvider)
        // Case-insensitive, exactly as a config value would be typed.
        assertEquals("fake", ProviderFactory.fromFlag("FAKE").id)
    }

    @Test
    fun unknownProviderFailsLoudly() {
        val error = runCatching { ProviderFactory.fromFlag("does-not-exist") }.exceptionOrNull()
        check(error is IllegalStateException) { "expected IllegalStateException, got $error" }
    }

    @Test
    fun aNewProviderIsDrivenOnlyThroughTheSeam() {
        val provider = FakeProvider()
        val runner = FakeRunner()
        var session: String? = null
        val stdout = mutableListOf<String>()

        val listener = object : ProcessListener {
            override fun onStdout(line: String) {
                stdout += line
                provider.sessionIdFromLine(line)?.let { session = it }
            }

            override fun onStderr(line: String) = Unit
            override fun onExit(exitCode: Int) = Unit
        }

        val request = RunRequest(prompt = "ping")
        val process = runner.start(provider.spec(request), listener)
        provider.deliver(process, request)
        process.awaitExit()

        assertEquals(listOf("fake-agent", "--stdin"), runner.lastSpec?.command)
        assertEquals("ping", runner.deliveredPrompt)
        assertEquals(listOf("SESSION fake-1", "hello"), stdout)
        assertEquals("fake-1", session)
    }

    @Test
    fun stubDelayKnobDefaultsTo25AndRejectsGarbage() {
        assertEquals(25L, ProviderFactory.stubDelayMs(null))
        assertEquals(25L, ProviderFactory.stubDelayMs("  "))
        assertEquals(0L, ProviderFactory.stubDelayMs("0"))
        assertEquals(5000L, ProviderFactory.stubDelayMs(" 5000 "))
        check(runCatching { ProviderFactory.stubDelayMs("fast") }.exceptionOrNull() is IllegalStateException)
        check(runCatching { ProviderFactory.stubDelayMs("-1") }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun blankConfigDefaultsToOpenCode() {
        assertEquals("opencode", ProviderFactory.fromFlag(null).id)
        assertEquals("opencode", ProviderFactory.fromFlag("").id)
    }
}
