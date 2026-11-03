// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chat.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.ProjectInfo
import com.superiorsoftllc.infantry.daemon.api.Projects
import com.superiorsoftllc.infantry.daemon.api.SessionInfo
import com.superiorsoftllc.infantry.daemon.api.Sessions
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import java.lang.reflect.Proxy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** XPROJ-1: the read-only project directory tool: id, name, description, live roles; a query narrows it. */
class ProjectsToolTest {
    private fun session(project: String, role: String, state: String = "IDLE", neverRun: Boolean = false) =
        SessionInfo("s-$project-$role", project, role, null, false, 0, state, neverRun, null, 1)

    private val api: DaemonApi = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DaemonApi::class.java)) { _, m, _ ->
        when (m.name) {
            "getProjects" -> object : Projects {
                override fun list() = listOf(
                    ProjectInfo("p-site", "Website", "/w", "Marketing site"),
                    ProjectInfo("p-app", "Infantry AI", "/a", "The app and daemon"),
                )
                override fun create(name: String, goal: String, directory: String) = error("unused")
            }
            "getSessions" -> object : Sessions {
                override fun list() = listOf(
                    session("p-app", "pm"), session("p-app", "backend", state = "BUSY"), session("p-app", "qa", neverRun = true),
                    session("p-app", "dead", state = "FAILED"), session("p-site", "pm"),
                )
                override fun get(id: String) = null
                override fun spawn(provider: String, model: String, role: String, projectId: String) = error("unused")
                override fun resume(id: String, prompt: String) = error("unused")
                override fun restart(id: String) = Unit
            }
            else -> error("unexpected ${m.name}")
        }
    } as DaemonApi

    private class Call(override val arguments: JsonObject) : ToolCall {
        override val caller: Caller get() = error("not used")
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): com.superiorsoftllc.infantry.daemon.api.Frame = error("not used")
    }

    private fun run(json: String): JsonObject {
        val r = MailTools.ProjectsTool(api).call(Call(Json.parseToJsonElement(json).jsonObject))
        assertTrue(!r.isError, r.text)
        return Json.parseToJsonElement(r.text).jsonObject
    }

    @Test fun listsEveryProjectWithDescriptionAndLiveRoles() {
        val out = run("{}")
        assertEquals("2", out["count"]!!.jsonPrimitive.content)
        val app = out["projects"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "p-app" }
        assertEquals("The app and daemon", app["description"]!!.jsonPrimitive.content)
        assertEquals(listOf("backend", "pm"), app["liveRoles"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun aQueryFindsByNameIdOrDescription() {
        for (q in listOf("infantry", "P-APP", "daemon")) {
            val out = run("""{"query":"$q"}""")
            assertEquals(listOf("p-app"), out["projects"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }, q)
        }
        assertEquals("0", run("""{"query":"zzz"}""")["count"]!!.jsonPrimitive.content)
    }
}
