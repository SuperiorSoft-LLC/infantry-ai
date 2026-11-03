package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertTrue

/** COMMENT-TOOL-1: the comment tools stay in every session's tool list; a dead comment store makes them refuse, not vanish. */
class CommentToolsListedTest {
    private val api = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DaemonApi::class.java)) { _, _, _ -> error("unused") } as DaemonApi
    private val call = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ToolCall::class.java)) { _, _, _ -> error("unused") } as ToolCall

    @Test fun `plugin registers both comment tools whether or not the store started`() {
        val src = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "plugins/official/Infantry_Board/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/board/daemon/BoardDaemonPlugin.kt") }
            .first { it.isFile }.readText()
        val reg = src.lineSequence().indexOfFirst { "TicketCommentTool(" in it }
        val guard = src.lineSequence().indexOfFirst { "if (comments != null)" in it }
        assertTrue(reg >= 0 && (guard < 0 || reg < guard), "the comment tools must be registered outside any 'if (comments != null)'")
        assertTrue("TicketCommentsTool(" in src)
    }

    @Test fun `without a store the tools refuse with the reason`() {
        val add = TicketCommentTool(api, null, "store down")
        val read = TicketCommentsTool(api, null, "store down")
        listOf(add.call(call), read.call(call)).forEach { r -> assertTrue(r.isError && "store down" in r.text, r.text) }
    }
}
