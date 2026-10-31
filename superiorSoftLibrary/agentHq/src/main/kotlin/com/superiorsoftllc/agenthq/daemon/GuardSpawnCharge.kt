package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.daemon.api.spawns.SpawnCharge
import com.superiorsoftllc.agenthq.session.SessionManager

/** DP-5h-3a: core's [SpawnCharge]: the [SpawnGuard] and the [SessionManager] behind the plugin API's minimal port. */
class GuardSpawnCharge(private val guard: SpawnGuard, private val manager: SessionManager) : SpawnCharge {
    override fun isSession(sessionId: String) = manager.get(sessionId) != null
    override fun <T> admitIf(origin: String?, charged: Boolean, create: (placed: Boolean) -> T): T =
        guard.admitIf(origin, charged) { placement -> create(placement != null) }
    override fun record(sessionId: String, origin: String?) = guard.record(sessionId, origin)
    override fun setSpawner(sessionId: String, spawnerId: String?) = manager.setSpawner(sessionId, spawnerId)
}
