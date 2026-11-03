package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.ToolInvoker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SCRIPT-4: the host's invokeTool (what `api.tools.invoke` runs): the live registry, as an agent identity, no role check. */
class ToolInvokeTest {
    @Test fun `invokeTool runs a registered tool for any role and an unknown tool is an error`() {
        val host = DaemonPluginHost.empty()
        val got = CopyOnWriteArrayList<String>()
        host.namedEvents.subscribe("script.*") { got += it.source }
        for (role in listOf("qa", "pm", "backend")) {
            val by = ToolInvoker(Caller(owner = false, agent = true), "sess-$role", role, null, "script:x")
            val r = host.invokeTool("infantry_event_emit", JsonObject(mapOf("name" to JsonPrimitive("script.done"))), by)
            assertTrue(!r.isError, r.text)
        }
        Thread.sleep(300)
        assertEquals(listOf("session:sess-qa", "session:sess-pm", "session:sess-backend"), got.toList())
        val unknown = host.invokeTool("no_such_tool", JsonObject(emptyMap()), ToolInvoker(Caller(owner = true)))
        assertTrue(unknown.isError && "Unknown tool" in unknown.text)
    }
}
