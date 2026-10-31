package com.superiorsoftllc.agenthq.plugins.official.git.daemon

import com.superiorsoftllc.agenthq.daemon.api.Caller
import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RUNTIME-LOAD-8: the pipeline tools send the frames `agenthq pipeline` sends and return the daemon's JSON verbatim. */
class PipelineToolsTest {
    private class FakeCall(
        override val arguments: JsonObject,
        override val clientSessionId: String? = "s-me",
        override val clientProjectId: String? = "p-default",
        private val answer: Frame = Frame("pipeline_state", """{"configured":true}"""),
    ) : ToolCall {
        val sent = mutableListOf<Triple<String, JsonObject, String?>>()
        override val caller: Caller get() = error("not used")
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame {
            sent += Triple(type, Json.parseToJsonElement(data).jsonObject, sessionId)
            return answer
        }
    }

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun showSendsTheProjectAndReturnsTheStateVerbatim() {
        val call = FakeCall(args("{}"))
        val result = PipelineTools.ShowTool.call(call)
        assertEquals("""{"configured":true}""", result.text)
        assertFalse(result.isError)
        val (type, data, _) = call.sent.single()
        assertEquals("pipeline_show", type)
        assertEquals("p-default", data["projectId"]!!.jsonPrimitive.content)
        assertEquals("p-x", PipelineTools.ShowTool.let { FakeCall(args("""{"projectId":"p-x"}""")).also { c -> it.call(c) }.sent.single().second["projectId"]!!.jsonPrimitive.content })
    }

    @Test fun noProjectIsARefusal() {
        val result = PipelineTools.ShowTool.call(FakeCall(args("{}"), clientProjectId = null))
        assertTrue(result.isError)
        assertEquals("agenthq_pipeline needs a projectId (or AGENTHQ_PROJECT_ID in the environment)", result.text)
    }

    @Test fun recordMovesACommitToAStage() {
        val call = FakeCall(args("""{"commit":"abc","stage":"unit","evidence":"12 passed"}"""))
        assertFalse(PipelineTools.RecordTool.call(call).isError)
        val (type, data, session) = call.sent.single()
        assertEquals("pipeline_record", type)
        assertEquals("s-me", session)
        assertEquals("s-me", data["sessionId"]!!.jsonPrimitive.content)
        assertEquals("unit", data["stage"]!!.jsonPrimitive.content)
        assertEquals("12 passed", data["evidence"]!!.jsonPrimitive.content)
        assertTrue(PipelineTools.RecordTool.call(FakeCall(args("""{"stage":"unit"}"""))).text.contains("needs a commit"))
        assertTrue(PipelineTools.RecordTool.call(FakeCall(args("""{"commit":"abc"}"""))).text.contains("needs a stage"))
    }

    @Test fun recordWithCheckIsAPipelineCheck() {
        val call = FakeCall(args("""{"check":"start","commit":"abc","what":"gate","scope":"wt1"}"""), clientSessionId = null)
        PipelineTools.RecordTool.call(call)
        val (type, data, _) = call.sent.single()
        assertEquals("pipeline_check", type)
        assertEquals("start", data["action"]!!.jsonPrimitive.content)
        assertEquals("wt1", data["scope"]!!.jsonPrimitive.content)
        assertEquals("gate", data["what"]!!.jsonPrimitive.content)
        assertNull(data["sessionId"])
    }

    @Test fun aDaemonRefusalComesBackAsAnErrorResult() {
        val result = PipelineTools.RecordTool.call(FakeCall(args("""{"commit":"c","stage":"nope"}"""), answer = Frame("error", "unknown stage 'nope'")))
        assertTrue(result.isError)
        assertEquals("unknown stage 'nope'", result.text)
    }
}
