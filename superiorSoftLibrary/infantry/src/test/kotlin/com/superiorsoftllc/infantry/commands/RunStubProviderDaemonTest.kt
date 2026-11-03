// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.commands

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-RUN-STUB-1: on a daemon whose provider is the stub (INFANTRY_PROVIDER=stub), a saved Run-tab command runs a
 * REAL process. The fixture boots the real daemon on StubProvider and hands it the stub's own transport as the
 * session runner, exactly as `build` does in production -- RunManager must not inherit that ScriptedRunner.
 * Temp INFANTRY_HOME and repo, ephemeral port; never the operator's state.
 */
class RunStubProviderDaemonTest {
    private val home: File = Files.createTempDirectory("infantry-runstub-home").toFile()
    private val repoDir: File = File(home, "repo").also { it.mkdirs() }
    private val file = File(home, "commands.json")
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private var fixture: DaemonFixture? = null

    @AfterTest fun cleanUp() {
        fixture?.let { runCatching { it.stop() }; runCatching { it.deleteState() } }
        home.deleteRecursively()
    }

    @Test fun `a saved command runs a real process when the provider is the stub`() {
        val d = DaemonFixture.launch(repo = repoDir, commandsFile = file, pluginsRoot = com.superiorsoftllc.infantry.daemon.TestDaemonPlugins.boardRoot("official/Infantry_Run")).also { fixture = it }
        d.client.await(Envelope.READY)
        val argv = if (windows) """["cmd.exe","/c","echo hello-run"]""" else """["sh","-c","echo hello-run"]"""
        d.send(Envelope.COMMAND_SAVE, """{"id":"echo","name":"Echo","argv":$argv,"graceMillis":2000}""")
        d.client.await(Envelope.COMMANDS)

        d.send(Envelope.RUN_START, """{"commandId":"echo"}""")
        d.client.await(Envelope.RUN)
        val finished = Json.parseToJsonElement(d.client.await(Envelope.RUN_FINISHED).data).jsonObject

        val stdout = d.client.of(Envelope.RUN_OUTPUT)
            .map { Json.parseToJsonElement(it.data).jsonObject }
            .filter { it["stream"]?.jsonPrimitive?.content == "stdout" }
            .map { it["line"]!!.jsonPrimitive.content.trim() }
        assertEquals(listOf("hello-run"), stdout, "the real child's stdout, not the stub script's lines")
        assertEquals("0", finished["exitCode"]?.jsonPrimitive?.content, finished.toString())
        assertTrue(finished["outcome"]?.jsonPrimitive?.content == "SUCCEEDED", finished.toString())
    }
}
