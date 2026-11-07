// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.TestDaemonPlugins
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.ToolInvoker
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionReads
import com.superiorsoftllc.infantry.plugin.host.ModuleResolver
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HOOK-TIMER-SCOPE-1: a project hook's script (every hook kind: timer, lane, event, mail, exec.*) calls tools as
 * `Caller(owner = true, project = P)` with no session (ScriptHooks.invokerOf). Its call with no projectId means "project P", never
 * `all`; a call naming another project (or `all`) is still refused. Runs the real, BUILT Infantry_Agents daemon jar.
 */
class HookCallerScopeTest {
    private fun record(id: String, role: String, project: String?) = SessionRecord(
        id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0,
        state = SessionState.IDLE, role = role, projectId = project,
    )

    private val records = listOf(record("s-a1", "backend", "A"), record("s-a-pm", "pm", "A"), record("s-b1", "frontend", "B"))
    private val reads = LocalSessionReads(sessions = { records })
    private val hosts = mutableListOf<DaemonPluginHost>()
    private val roots = mutableListOf<File>()

    @AfterTest fun cleanup() {
        hosts.forEach { runCatching { it.close() } }
        roots.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun host(): DaemonPluginHost {
        val root = TestDaemonPlugins.root("official/Infantry_Agents").also { roots += it }
        val config = PluginFolders.config(root)
        val resolution = ModuleResolver.resolve(PluginFolders.scan(root, config, null), config, false)
        val services = object : DaemonServices by DaemonServices.NONE {
            override val sessionReads: SessionReads get() = reads
        }
        return DaemonPluginHost.load(resolution, config, TestDaemonPlugins.trustAll, services, File(root, "state"), {}).also { hosts += it }
    }

    /** Exactly the invoker ScriptHooks gives a project hook of [project] (no session, confined to the project). */
    private fun hook(project: String) = ToolInvoker(Caller(owner = true, project = project), null, null, project, "script:worker-silent-ping")

    private fun ids(text: String): List<String> =
        Json.parseToJsonElement(text).jsonObject["sessions"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.sorted()

    private fun args(vararg kv: Pair<String, String>): JsonObject = buildJsonObject { kv.forEach { (k, v) -> put(k, v) } }

    @Test fun `a hook's list_sessions with no projectId lists its own project`() {
        val r = host().invokeTool("infantry_list_sessions", JsonObject(emptyMap()), hook("A"))
        assertFalse(r.isError, r.text)
        assertEquals(listOf("s-a-pm", "s-a1"), ids(r.text))
    }

    @Test fun `a hook naming its own project is answered, another project or all is refused`() {
        val h = host()
        val own = h.invokeTool("infantry_list_sessions", args("projectId" to "A"), hook("A"))
        assertFalse(own.isError, own.text)
        assertEquals(listOf("s-a-pm", "s-a1"), ids(own.text))
        for (p in listOf("B", "all")) {
            val r = h.invokeTool("infantry_list_sessions", args("projectId" to p), hook("A"))
            assertTrue(r.isError, "projectId=$p: ${r.text}")
            assertFalse("s-b1" in r.text, r.text)
        }
    }

    @Test fun `a session caller and the owner keep their defaults`() {
        val h = host()
        val worker = h.invokeTool("infantry_list_sessions", JsonObject(emptyMap()), ToolInvoker(Caller(owner = true, activeSession = "s-a1"), "s-a1", "backend", "A", "mcp"))
        assertFalse(worker.isError, worker.text)
        assertEquals(listOf("s-a-pm", "s-a1"), ids(worker.text))
        val owner = h.invokeTool("infantry_list_sessions", JsonObject(emptyMap()), ToolInvoker(Caller(owner = true)))
        assertFalse(owner.isError, owner.text)
        assertEquals(listOf("s-a-pm", "s-a1", "s-b1"), ids(owner.text))
    }

    @Test fun `the default scope of a hook caller is its project, the one the board tool reads`() {
        val scope = assertIs<SessionsResult.Ok<com.superiorsoftllc.infantry.daemon.api.sessions.ReadScope>>(reads.scope(Caller(owner = true, project = "A"), null, "A")).value
        assertEquals("A", scope.projectId)
        assertNull(scope.refusal)
        val all = assertIs<SessionsResult.Ok<com.superiorsoftllc.infantry.daemon.api.sessions.ReadScope>>(reads.scope(Caller(owner = true, project = "A"), "all")).value
        assertTrue(all.refusal!!.contains("project hook"), all.refusal)
    }
}
