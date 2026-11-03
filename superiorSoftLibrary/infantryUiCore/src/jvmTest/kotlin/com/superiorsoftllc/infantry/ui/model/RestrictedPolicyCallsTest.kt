package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.plugin.config.ConfigTree
import com.superiorsoftllc.infantry.ui.plugin.config.NodeConfigApi
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.ui.plugin.config.NodeConfigLoad
import com.superiorsoftllc.infantry.ui.plugin.config.NodeConfigState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEMO-FIX-1: under a restricted (read-only) daemon policy the window makes neither refused call (POST /node-config/seed, GET /update/status). */
class RestrictedPolicyCallsTest {
    private class CountingConfig : NodeConfigApi {
        var calls = 0
        override suspend fun seed(entries: List<NodeConfigEntry>): Result<List<NodeConfigEntry>> { calls++; return Result.success(emptyList()) }
        override suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>> { calls++; return Result.success(emptyList()) }
    }

    private class CountingUpdate : ServerUpdateApi {
        var calls = 0
        override suspend fun status(): UpdateApiResult<ServerUpdateStatusWire> { calls++; return UpdateApiResult.Unsupported }
        override suspend fun check(): UpdateApiResult<ServerUpdateStatusWire> { calls++; return UpdateApiResult.Unsupported }
        override suspend fun apply(confirm: Boolean): UpdateApiResult<UpdateApplyReplyWire> { calls++; return UpdateApiResult.Unsupported }
    }

    @Test fun `node config makes no seed call when the daemon rows are closed, and loads the defaults`() = runBlocking {
        val api = CountingConfig()
        val state = NodeConfigState(ConfigTree.EMPTY, api).also { it.daemonRowsClosed = true }
        state.load()
        assertEquals(0, api.calls)
        assertEquals(NodeConfigLoad.LOADED, state.load)
        assertTrue(state.stored.isEmpty())
    }

    @Test fun `node config still seeds on an unrestricted daemon`() = runBlocking {
        val api = CountingConfig()
        NodeConfigState(ConfigTree.EMPTY, api).load()
        assertTrue(api.calls >= 1)
    }

    @Test fun `server update asks no status when unavailable, and asks on an unrestricted daemon`() = runBlocking {
        val api = CountingUpdate()
        val state = ServerUpdateState(api, CoroutineScope(Dispatchers.Unconfined))
        state.available = false
        state.refresh(); state.onReconnected()
        assertEquals(0, api.calls)
        state.available = true
        state.refresh()
        assertEquals(1, api.calls)
    }
}
