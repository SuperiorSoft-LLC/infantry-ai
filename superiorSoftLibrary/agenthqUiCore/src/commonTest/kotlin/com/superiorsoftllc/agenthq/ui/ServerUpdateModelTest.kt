package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.ServerUpdateApi
import com.superiorsoftllc.agenthq.ui.model.ServerUpdateOfferWire
import com.superiorsoftllc.agenthq.ui.model.ServerUpdateState
import com.superiorsoftllc.agenthq.ui.model.ServerUpdateStatusWire
import com.superiorsoftllc.agenthq.ui.model.ServerUpdateView
import com.superiorsoftllc.agenthq.ui.model.ServerVersionCheck
import com.superiorsoftllc.agenthq.ui.model.UpdateApiResult
import com.superiorsoftllc.agenthq.ui.model.UpdateApplyReplyWire
import com.superiorsoftllc.agenthq.ui.net.HttpServerUpdateApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerUpdateModelTest {
    private class FakeApi(
        var status: UpdateApiResult<ServerUpdateStatusWire>,
        var apply: MutableList<UpdateApiResult<UpdateApplyReplyWire>> = mutableListOf(),
    ) : ServerUpdateApi {
        val applied = mutableListOf<Boolean>()
        override suspend fun status() = status
        override suspend fun check() = status
        override suspend fun apply(confirm: Boolean): UpdateApiResult<UpdateApplyReplyWire> { applied += confirm; return apply.removeAt(0) }
    }

    private fun wire(version: String = "1.0.0", available: String? = "1.1.0", canSelfUpdate: Boolean = true, running: Int = 0) =
        ServerUpdateStatusWire(
            version = version, contract = "1.110.0", distribution = "archive", canSelfUpdate = canSelfUpdate, enabled = true,
            available = available?.let { ServerUpdateOfferWire(it, "notes") }, runningAgents = running,
            instructions = if (canSelfUpdate) null else "docker pull x",
        )

    private fun state(api: ServerUpdateApi) = ServerUpdateState(api, CoroutineScope(Dispatchers.Unconfined))

    @Test fun `patch-only differences do not warn`() {
        assertNull(ServerVersionCheck.mismatch("1.4.2", "1.4.9", "1.110.0"))
        assertNull(ServerVersionCheck.mismatch(null, "1.4.9", "1.110.0"))
        assertNull(ServerVersionCheck.mismatch("1.4.2", null, null))
    }

    @Test fun `minor and contract differences warn in the right direction`() {
        assertTrue(ServerVersionCheck.mismatch("1.5.0", "1.4.0", "1.110.0")!!.contains("Update the server"))
        assertTrue(ServerVersionCheck.mismatch("1.4.0", "1.5.0", "1.110.0")!!.contains("Update the app"))
        assertTrue(ServerVersionCheck.mismatch(null, "1.0.0", "1.100.0")!!.contains("older than this app expects"))
        assertTrue(ServerVersionCheck.mismatch(null, "2.0.0", "2.0.0")!!.contains("not compatible"))
        assertNull(ServerVersionCheck.mismatch(null, "1.0.0", "1.111.0"), "a newer additive contract is fine for the app")
    }

    @Test fun `http base swaps the scheme and drops the ws path`() {
        assertEquals("http://h:8787", HttpServerUpdateApi.httpBase("ws://h:8787/ws"))
        assertEquals("https://h", HttpServerUpdateApi.httpBase("wss://h/ws/"))
    }

    @Test fun `check shows the offer, update asks first, confirm applies and waits for the restart`() {
        val api = FakeApi(UpdateApiResult.Ok(wire()), mutableListOf(UpdateApiResult.Ok(UpdateApplyReplyWire("staged", "1.1.0", message = "restarting into 1.1.0"))))
        val s = state(api)
        s.check()
        assertEquals("1.1.0", s.view.value.updateAvailable)
        assertTrue(s.view.value.canUpdate)
        s.requestUpdate()
        assertNotNull(s.view.value.confirm)
        assertTrue(api.applied.isEmpty(), "nothing is applied before the answer")
        s.confirmUpdate()
        assertEquals(listOf(true), api.applied)
        assertEquals(ServerUpdateView.Phase.RESTARTING, s.view.value.phase)
        api.status = UpdateApiResult.Ok(wire(version = "1.1.0", available = null))
        s.onReconnected()
        assertEquals(ServerUpdateView.Phase.IDLE, s.view.value.phase)
        assertEquals("1.1.0", s.view.value.status?.version)
        assertTrue(s.view.value.message!!.startsWith("Updated"))
    }

    @Test fun `cancel leaves everything alone and agents are named in the question`() {
        val api = FakeApi(UpdateApiResult.Ok(wire(running = 2)))
        val s = state(api)
        s.refresh()
        s.requestUpdate()
        assertEquals("2 agents running, update anyway?", s.view.value.confirm!!.question)
        s.cancelConfirm()
        assertNull(s.view.value.confirm)
        assertTrue(api.applied.isEmpty())
    }

    @Test fun `a server that cannot update itself offers no button`() {
        val s = state(FakeApi(UpdateApiResult.Ok(wire(canSelfUpdate = false))))
        s.check()
        assertFalse(s.view.value.canUpdate)
        s.requestUpdate()
        assertNull(s.view.value.confirm)
        assertEquals("docker pull x", s.view.value.status?.instructions)
    }

    @Test fun `an old daemon is reported as unsupported, a failure keeps the last status`() {
        val old = state(FakeApi(UpdateApiResult.Unsupported))
        old.refresh()
        assertFalse(old.view.value.supported)
        assertFalse(old.view.value.canUpdate)
        val api = FakeApi(UpdateApiResult.Ok(wire()))
        val s = state(api)
        s.refresh()
        api.status = UpdateApiResult.Failed("Could not reach the server")
        s.check()
        assertEquals("1.0.0", s.view.value.status?.version)
        assertEquals("Could not reach the server", s.view.value.message)
        assertEquals(ServerUpdateView.Phase.IDLE, s.view.value.phase)
    }

    @Test fun `a failed or rolled back update says so`() {
        val api = FakeApi(UpdateApiResult.Ok(wire()), mutableListOf(UpdateApiResult.Ok(UpdateApplyReplyWire("failed", message = "SHA-256 mismatch"))))
        val s = state(api)
        s.check(); s.requestUpdate(); s.confirmUpdate()
        assertEquals("SHA-256 mismatch", s.view.value.message)
        assertEquals(ServerUpdateView.Phase.IDLE, s.view.value.phase)

        val api2 = FakeApi(UpdateApiResult.Ok(wire()), mutableListOf(UpdateApiResult.Ok(UpdateApplyReplyWire("staged", "1.1.0", message = "restarting into 1.1.0"))))
        val s2 = state(api2)
        s2.check(); s2.requestUpdate(); s2.confirmUpdate()
        api2.status = UpdateApiResult.Ok(wire(version = "1.0.0"))
        s2.onReconnected()
        assertTrue(s2.view.value.message!!.contains("rolled back"))
    }

    @Test fun `an agents-running race asks again`() {
        val api = FakeApi(UpdateApiResult.Ok(wire()), mutableListOf(UpdateApiResult.Ok(UpdateApplyReplyWire("agents_running", running = 3))))
        val s = state(api)
        s.check(); s.requestUpdate(); s.confirmUpdate()
        assertEquals(3, s.view.value.confirm?.running)
    }
}
