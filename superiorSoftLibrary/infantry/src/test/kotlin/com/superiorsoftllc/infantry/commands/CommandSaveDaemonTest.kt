// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.commands

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RUN-CMD-1 (contract 1.94.0): `command_save` / `command_delete` write `<INFANTRY_HOME>/commands.json` through the
 * daemon, and every client gets the updated `commands` list. Every daemon here runs on a temp INFANTRY_HOME
 * ([home]) and a temp repo, never the operator's.
 */
class CommandSaveDaemonTest {
    private val home: File = Files.createTempDirectory("infantry-cmdsave-home").toFile()
    private val repoDir: File = File(home, "repo").also { File(it, "sub").mkdirs() }
    // The escape target EXISTS, so `..\x` can only be refused by the cwd check, never for "no such directory".
    private val outsideDir: File = File(home, "x").also { it.mkdirs() }
    private val file = File(home, "commands.json")
    private val bs = 92.toChar().toString() // a backslash, kept out of string literals on purpose
    private val secret = "SECRET-ARGV-TOKEN"
    private val fixtures = ArrayList<DaemonFixture>()
    // Wire names spelled out, so this file compiles on a base that has no such frame and fails there for the right reason.
    private val SAVE = "command_save"
    private val DELETE = "command_delete"

    @AfterTest fun cleanUp() {
        fixtures.forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
        home.deleteRecursively()
    }

    private fun launch(): DaemonFixture =
        DaemonFixture.launch(repo = repoDir, commandsFile = file, pluginsRoot = com.superiorsoftllc.infantry.daemon.TestDaemonPlugins.boardRoot("official/Infantry_Run")).also { fixtures += it; it.client.await(Envelope.READY) }

    private fun save(id: String, cwd: String? = "sub", argv: String = """["cmd.exe","/c","$secret"]""", grace: Long = 2000): String {
        val cwdJson = cwd?.let { "\"" + it.replace(bs, bs + bs) + "\"" } ?: "null"
        return """{"id":"$id","name":"Build $id","argv":$argv,"cwd":$cwdJson,"graceMillis":$grace}"""
    }

    private fun ids(commands: Envelope): List<String> =
        Json.parseToJsonElement(commands.data).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test fun `command_save then command_list carries the new id with its argv`() {
        val d = launch()
        d.send(SAVE, save("build"))
        val answer = d.client.await(Envelope.COMMANDS)
        assertEquals(listOf("build"), ids(answer), answer.data)

        d.send(Envelope.COMMAND_LIST, "")
        val listed = d.client.await(Envelope.COMMANDS, occurrence = 1)
        assertEquals(listOf("build"), ids(listed), listed.data)
        // Owner decision (RUN-CMD-1): the editor shows the argv, so the list now carries it and the grace window.
        val entry = Json.parseToJsonElement(listed.data).jsonArray.single().jsonObject
        assertEquals(listOf("cmd.exe", "/c", secret), (entry["argv"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("2000", entry["graceMillis"]!!.jsonPrimitive.content)
        assertEquals("sub", entry["cwd"]!!.jsonPrimitive.content)
        assertTrue(file.readText().contains("\"build\""), "the save is on disk: ${file.readText()}")
    }

    @Test fun `a save upserts by id and a delete removes it`() {
        val d = launch()
        d.send(SAVE, save("build"))
        d.client.await(Envelope.COMMANDS)
        d.send(SAVE, save("test", cwd = null))
        assertEquals(listOf("build", "test"), ids(d.client.await(Envelope.COMMANDS, occurrence = 1)))
        d.send(SAVE, save("build", argv = """["gradlew","build"]"""))
        val upserted = d.client.await(Envelope.COMMANDS, occurrence = 2)
        assertEquals(listOf("build", "test"), ids(upserted), "an existing id is replaced in place, not repeated")
        val build = Json.parseToJsonElement(upserted.data).jsonArray.first().jsonObject
        assertEquals(listOf("gradlew", "build"), build["argv"]!!.jsonArray.map { it.jsonPrimitive.content }, upserted.data)

        d.send(DELETE, """{"id":"build"}""")
        assertEquals(listOf("test"), ids(d.client.await(Envelope.COMMANDS, occurrence = 3)))
        assertFalse(file.readText().contains("\"build\""), file.readText())

        d.send(DELETE, """{"id":"build"}""")
        val refused = d.client.await(Envelope.ERROR)
        assertTrue(refused.data.startsWith("command_delete refused:"), refused.data)
    }

    @Test fun `a cwd that climbs out of the repo is refused and never written`() {
        val d = launch()
        d.send(SAVE, save("build"))
        d.client.await(Envelope.COMMANDS)
        val before = file.readText()

        val climbs = listOf("..${bs}x", "../x", "sub/../../x")
        for ((i, bad) in climbs.withIndex()) {
            d.send(SAVE, save("evil$i", cwd = bad))
            val refused = d.client.await(Envelope.ERROR, occurrence = i)
            assertTrue(refused.data.startsWith("command_save refused:"), refused.data)
            assertTrue(refused.data.contains("cwd"), "the reason names the cwd: ${refused.data}")
            assertFalse(refused.data.contains(secret), "a refusal never quotes the argv: ${refused.data}")
        }
        assertEquals(before, file.readText(), "a refused save leaves commands.json byte for byte")
        d.send(Envelope.COMMAND_LIST, "")
        assertEquals(listOf("build"), ids(d.client.await(Envelope.COMMANDS, occurrence = 1)))
    }

    @Test fun `an absolute cwd, an empty argv, a shell string, a blank id and a huge grace are refused`() {
        val d = launch()
        val bad = listOf(
            save("abs", cwd = outsideDir.absolutePath),
            save("noargv", argv = "[]"),
            save("shell", argv = "\"cmd.exe /c $secret\""),
            save(" "),
            save("grace", grace = 0),
            save("grace2", grace = 24L * 3600 * 1000),
            """{"id":"x","argv":["a"],"unknown":1}""",
            "not json $secret",
        )
        for ((i, data) in bad.withIndex()) {
            d.send(SAVE, data)
            val refused = d.client.await(Envelope.ERROR, occurrence = i)
            assertTrue(refused.data.startsWith("command_save refused:"), "#$i: ${refused.data}")
            assertFalse(refused.data.contains(secret), "#$i quoted the argv: ${refused.data}")
        }
        assertFalse(file.exists(), "no bad entry is ever written: ${if (file.exists()) file.readText() else ""}")
    }

    @Test fun `a saved command survives a daemon restart`() {
        val first = launch()
        first.send(SAVE, save("build"))
        first.client.await(Envelope.COMMANDS)
        first.stop()

        val second = launch()
        second.send(Envelope.COMMAND_LIST, "")
        val listed = second.client.await(Envelope.COMMANDS)
        assertEquals(listOf("build"), ids(listed), listed.data)
    }

    @Test fun `a hand-written file the daemon refused is never overwritten by a save`() {
        file.writeText("""[{"id":"a","argv":[]}]""")
        val d = launch()
        d.send(SAVE, save("build"))
        val refused = d.client.await(Envelope.ERROR)
        assertTrue(refused.data.startsWith("command_save refused:"), refused.data)
        assertEquals("""[{"id":"a","argv":[]}]""", file.readText())
    }

    @Test fun `every client hears the updated list`() {
        val d = launch()
        val other = d.connectClient()
        d.send(SAVE, save("build"))
        val pushed = other.await(Envelope.COMMANDS)
        assertEquals(listOf("build"), ids(pushed))
    }
}
