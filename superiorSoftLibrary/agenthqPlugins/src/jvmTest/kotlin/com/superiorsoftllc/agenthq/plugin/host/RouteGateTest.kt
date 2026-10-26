package com.superiorsoftllc.agenthq.plugin.host

import com.superiorsoftllc.agenthq.plugin.api.DaemonReply
import com.superiorsoftllc.agenthq.plugin.api.DemoLockConfig
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLUGINS-1: the daemon access a module gets is limited to its declared routes, and under the demo lock to the lock's. */
class RouteGateTest {
    private val manifest = ModuleManifest("Acme_Hello", "1.0.0", routes = listOf("GET /health", "POST /tickets"))

    @Test
    fun `daemon access needs a declared route, and under the demo lock a lock-allowed one`() = runBlocking {
        val config = ModulesConfig(demoLock = DemoLockConfig(allowRoutes = listOf("GET /health")))
        val sent = mutableListOf<String>()
        fun access(lock: Boolean) = RouteGate.gated(manifest, lock, config) { route, _ -> sent += route; DaemonReply.Ok(200, "ok") }

        assertEquals(DaemonReply.Ok(200, "ok"), access(false).call("POST /tickets", "{}"))
        assertTrue(access(false).call("DELETE /x", null) is DaemonReply.Refused)
        assertEquals(DaemonReply.Ok(200, "ok"), access(true).call("GET /health", null))
        val locked = access(true).call("POST /tickets", "{}")
        assertTrue(locked is DaemonReply.Refused && "demo lock" in locked.reason)
        assertEquals(listOf("POST /tickets", "GET /health"), sent, "a refused call never reaches the daemon")
    }

}
