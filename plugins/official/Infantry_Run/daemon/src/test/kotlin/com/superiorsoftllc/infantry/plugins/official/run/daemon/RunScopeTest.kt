// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.run.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RUN-SCOPE-1: a run command belongs to one project, its cwd is relative to that project's folder, and calls act in a project. */
class RunScopeTest {
    private val home: File = Files.createTempDirectory("infantry-runscope-test").toFile()
    private val siteDir = File(home, "site").also { File(it, "vue").mkdirs() }
    private val appDir = File(home, "app").also { it.mkdirs() }
    private val file = File(home, "commands.json")
    private var folders = mutableMapOf<String, File?>("site" to siteDir, "app" to appDir)
    private val rootOf: (String?) -> File? = { p -> if (p == null) null else folders[p] }

    @AfterTest fun cleanUp() { home.deleteRecursively() }

    private fun entry(id: String, cwd: String? = null) =
        """{"id":"$id","argv":["x"]${cwd?.let { ",\"cwd\":${JsonPrimitive(it)}" } ?: ""}}"""

    @Test fun `a saved command keeps its project and the list carries it`() {
        val store = CommandStore(file, rootOf)
        assertNull(store.save(entry("dev", "vue"), project = "site"))
        val spec = Json.parseToJsonElement(store.registry.specsJson()).jsonArray.single().jsonObject
        assertEquals("site", spec["project"]?.jsonPrimitive?.content)
        assertEquals("vue", spec["cwd"]?.jsonPrimitive?.content)
    }

    @Test fun `dot means the project folder and an absolute path inside it becomes relative`() {
        val store = CommandStore(file, rootOf)
        assertNull(store.save(entry("root", "."), project = "site"))
        assertNull(store.registry.find("root")!!.cwd)
        assertEquals(siteDir.canonicalFile, store.registry.find("root")!!.workingDir.canonicalFile)
        assertNull(store.save(entry("abs", File(siteDir, "vue").absolutePath), project = "site"))
        assertEquals("vue", store.registry.find("abs")!!.cwd)
    }

    @Test fun `an absolute path outside the project folder is refused`() {
        val refusal = CommandStore(file, rootOf).save(entry("out", appDir.absolutePath), project = "site")
        assertNotNull(refusal); assertTrue("outside the project folder" in refusal, refusal)
    }

    @Test fun `another project's command is never overwritten and a save naming no project keeps its owner`() {
        val store = CommandStore(file, rootOf)
        assertNull(store.save(entry("dev", "vue"), project = "site"))
        val refusal = store.save(entry("dev"), project = "app")
        assertNotNull(refusal); assertTrue("belongs to project 'site'" in refusal, refusal)
        // An older client (no project in the frame) edits it in place, under its own project.
        assertNull(store.save(entry("dev", "vue")))
        assertEquals("site", store.registry.find("dev")!!.projectId)
    }

    @Test fun `delete scoped to a project refuses another project's command`() {
        val store = CommandStore(file, rootOf)
        assertNull(store.save(entry("dev", "vue"), project = "site"))
        assertNotNull(store.delete("""{"id":"dev"}""", project = "app"))
        assertNull(store.delete("""{"id":"dev"}""", project = "site"))
    }

    @Test fun `a command whose project has no folder still loads, listed unrooted, and cannot start`() {
        file.writeText("""[{"id":"a","argv":["x"],"cwd":"vue","project":"gone"},{"id":"b","argv":["y"],"project":"site"}]""")
        val reg = CommandRegistry.load(file, rootOf)
        assertNull(reg.refusal)
        assertEquals(listOf("a", "b"), reg.commands.map { it.id })
        assertFalse(reg.find("a")!!.rooted)
        assertTrue(reg.find("b")!!.rooted)
        val manager = RunManager({ reg }, ScriptedRunner(StubScript(stdoutLines = listOf("a")), 60_000), { })
        val started = manager.start("""{"commandId":"a"}""")
        assertEquals(Envelope.ERROR, started.reply.type)
        assertTrue("no folder registered" in started.reply.data, started.reply.data)
    }

    @Test fun `a folder registered later takes effect without a restart`() {
        folders["late"] = null
        file.writeText("""[{"id":"a","argv":["x"],"project":"late"}]""")
        val store = CommandStore(file, rootOf)
        assertFalse(store.registry.find("a")!!.rooted)
        folders["late"] = appDir
        assertTrue(store.refreshUnrooted().find("a")!!.rooted)
    }

    @Test fun `a start naming another project is refused`() {
        val store = CommandStore(file, rootOf)
        assertNull(store.save(entry("dev", "vue"), project = "site"))
        val manager = RunManager({ store.registry }, ScriptedRunner(StubScript(stdoutLines = listOf("a")), 60_000), { })
        val refused = manager.start("""{"commandId":"dev","project":"app"}""")
        assertEquals(Envelope.ERROR, refused.reply.type)
        assertTrue("belongs to project 'site'" in refused.reply.data, refused.reply.data)
    }

    @Test fun `migration assigns unassigned commands to the project whose folder they ran in, and keeps the rest`() {
        // The legacy file: global commands rooted at the daemon repo (home), no project.
        file.writeText("""[{"id":"site-dev","argv":["x"],"cwd":"site/vue"},{"id":"loose","argv":["y"]}]""")
        val store = CommandStore(file, rootOf = { p: String? -> if (p == null) home else folders[p] })
        val assigned = store.assignUnassigned(listOf("site" to siteDir, "app" to appDir))
        assertEquals(listOf("site-dev"), assigned)
        val moved = store.registry.find("site-dev")!!
        assertEquals("site", moved.projectId); assertEquals("vue", moved.cwd)
        assertEquals(File(siteDir, "vue").canonicalFile, moved.workingDir.canonicalFile)
        // The unmatched one stays, unassigned, never deleted.
        assertNull(store.registry.find("loose")!!.projectId)
        assertTrue(file.readText().contains("\"project\": \"site\""))
        // A second pass changes nothing.
        assertTrue(store.assignUnassigned(listOf("site" to siteDir)).isEmpty())
    }

    @Test fun `the tools list only the caller's project and the unassigned commands`() {
        folders["p1"] = siteDir; folders["p2"] = appDir
        val store = CommandStore(file, rootOf = { p: String? -> if (p == null) home else folders[p] })
        assertNull(store.save(entry("one"), project = "p1"))
        assertNull(store.save(entry("two"), project = "p2"))
        assertNull(store.save(entry("loose")))
        val manager = RunManager({ store.registry }, ScriptedRunner(StubScript(stdoutLines = listOf("a")), 60_000), { })
        val frames = CopyOnWriteArrayList<Envelope>()
        val tools = RunCommandTools(store, manager, { }, { frames += it }).all().associateBy { it.name }
        fun call(name: String, vararg args: Pair<String, String>): ToolResult = tools.getValue(name).call(object : ToolCall {
            override val arguments = JsonObject(args.associate { (k, v) -> k to JsonPrimitive(v) })
            override val caller: Caller get() = error("unused")
            override val clientProjectId = "p1"
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long) = error("unused")
        })
        val ids = Json.parseToJsonElement(call("infantry_run_commands").text).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("one", "loose"), ids)
        val start = call("infantry_run_start", "id" to "two")
        assertTrue(start.isError && "belongs to project 'p2'" in start.text, start.text)
        assertTrue(call("infantry_run_command_delete", "id" to "two").isError)
    }
}
