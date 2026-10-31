package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.backlog.BoardFromRuntime
import com.superiorsoftllc.agenthq.daemon.api.board.BoardCoreAccess
import com.superiorsoftllc.agenthq.daemon.api.board.BoardEpicInputs
import com.superiorsoftllc.agenthq.daemon.api.board.BoardHost
import com.superiorsoftllc.agenthq.daemon.api.board.BoardRuntime
import com.superiorsoftllc.agenthq.daemon.api.board.NoBoardHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** DP-5h-3e-b: the plugin's core access reads the plugin's ONLY epic store, and hands itself to the runtime on attach. */
class PluginCoreAccessTest {
    private class FakeRuntime : BoardRuntime {
        var attached: BoardCoreAccess? = null
        override val host: BoardHost = NoBoardHost
        override val epics = object : BoardEpicInputs {
            override fun loadAll() = listOf("""{"id":"e1","projectId":"p1","name":"E","baseBranch":"release/1"}""")
        }
        override fun attachCore(access: BoardCoreAccess) { attached = access }
        override fun detachCore() { attached = null }
    }

    @Test
    fun epicBaseComesFromThePluginStoreAndUnknownAnswersNull() {
        val runtime = FakeRuntime()
        val h = BoardBuild.handlers(runtime, BoardFromRuntime.build(runtime))
        val access = PluginCoreAccess(BoardFromRuntime.build(runtime), h.epicStore)
        runtime.attachCore(access)
        assertSame(access, runtime.attached)
        assertEquals("release/1", access.epicBaseBranchOf("e1"))
        assertNull(access.epicBaseBranchOf("nope"))
        assertNull(access.ticket("nope"))
        runtime.detachCore()
        assertNull(runtime.attached)
    }
}
