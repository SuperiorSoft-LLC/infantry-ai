package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.backlog.TicketBoard
import com.superiorsoftllc.agenthq.mailbox.FakeAgentProvider
import com.superiorsoftllc.agenthq.mailbox.ScriptedRunner
import com.superiorsoftllc.agenthq.mailbox.tempSessionStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.SpawnOptions
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.role.RoleBinding
import com.superiorsoftllc.agenthq.session.SessionManager
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * ROLE-1b: spawn and `role:X` assignment resolve provider / model / effort through the role binding.
 * Order, first non-null wins: SpawnOptions > AgentProfile > role binding > daemon default.
 * A running session is a snapshot of what it started on; `/model` and `/effort` change the SESSION only.
 */
class RoleBindingSpawnTest {

    private val dir: File = Files.createTempDirectory("agenthq-rolespawn").toFile()
    private val opened = mutableListOf<SqlAgentHqStore>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private class Rig(val store: SqlAgentHqStore, val manager: SessionManager, val profiles: AgentProfiles)

    private fun rig(): Rig {
        val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath).also { opened += it }
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir)
        return Rig(store, manager, AgentProfiles(store.agents, manager, roleBindings = store.roleBindings))
    }

    private fun Rig.bind(model: String?, effort: String? = null, at: Long = 1) =
        store.roleBindings.set(RoleBinding("backend", provider = null, model = model, effort = effort), at)

    private fun Rig.start(options: SpawnOptions): String {
        val r = profiles.resolveSpawn(options)
        return manager.startNew(
            prompt = "work", providerId = r.providerId, model = r.model, agentId = r.agentId, role = r.role,
            effort = r.effort,
        ).id
    }

    @Test
    fun `role bound to A starts its session on A`() {
        val rig = rig()
        val worker = rig.profiles.save(AgentProfile(name = "Backend", role = "backend"))
        rig.bind("model-A", effort = "high")

        val id = rig.start(SpawnOptions(agentId = worker.id))

        assertEquals("model-A", rig.manager.get(id)!!.model)
        assertEquals("high", rig.manager.get(id)!!.effort)
    }

    @Test
    fun `rebinding to B leaves the running session on A and the next session starts on B`() {
        val rig = rig()
        val worker = rig.profiles.save(AgentProfile(name = "Backend", role = "backend"))
        val other = rig.profiles.save(AgentProfile(name = "Backend 2", role = "backend"))
        rig.bind("model-A")
        val running = rig.start(SpawnOptions(agentId = worker.id))

        rig.bind("model-B", at = 2)

        assertEquals("model-A", rig.manager.get(running)!!.model, "a running session is a snapshot")
        assertEquals("model-B", rig.manager.get(rig.start(SpawnOptions(agentId = other.id)))!!.model)

        val overridden = rig.start(SpawnOptions(agentId = other.id, model = "model-S"))
        assertEquals("model-S", rig.manager.get(overridden)!!.model)
        assertEquals("model-B", rig.store.roleBindings.get("backend")!!.model, "spawning never writes back to the binding")
    }

    @Test
    fun `model and effort on a live session never edit the role binding`() {
        val rig = rig()
        val worker = rig.profiles.save(AgentProfile(name = "Backend", role = "backend"))
        rig.bind("model-A", effort = "high")
        val id = rig.start(SpawnOptions(agentId = worker.id))
        val before = rig.store.roleBindings.get("backend")

        rig.manager.setModel(id, "model-Z")
        rig.manager.setEffort(id, "low")

        assertEquals("model-Z", rig.manager.get(id)!!.model)
        assertEquals(before, rig.store.roleBindings.get("backend"), "the binding is untouched by session settings")
    }

    @Test
    fun `spawn options beat the profile and the profile beats the binding`() {
        val rig = rig()
        val pinned = rig.profiles.save(AgentProfile(name = "Pinned", role = "backend", model = "model-P"))
        val open = rig.profiles.save(AgentProfile(name = "Open", role = "backend"))
        rig.bind("model-A")

        assertEquals("model-S", rig.profiles.resolveSpawn(SpawnOptions(agentId = pinned.id, model = "model-S")).model)
        assertEquals("model-P", rig.profiles.resolveSpawn(SpawnOptions(agentId = pinned.id)).model)
        assertEquals("model-A", rig.profiles.resolveSpawn(SpawnOptions(agentId = open.id)).model)
    }

    @Test
    fun `role-first S2 - effort resolves spawn then profile then binding`() {
        val rig = rig()
        val pinned = rig.profiles.save(AgentProfile(name = "Pinned", role = "backend", effort = "max"))
        val open = rig.profiles.save(AgentProfile(name = "Open", role = "backend"))
        rig.bind(null, effort = "low")

        assertEquals("medium", rig.profiles.resolveSpawn(SpawnOptions(agentId = pinned.id, effort = "medium")).effort)
        assertEquals("max", rig.profiles.resolveSpawn(SpawnOptions(agentId = pinned.id)).effort)
        assertEquals("low", rig.profiles.resolveSpawn(SpawnOptions(agentId = open.id)).effort)
        assertEquals("max", rig.store.agents.get(pinned.id!!)!!.effort, "the profile's effort is stored")
    }

    @Test
    fun `no binding keeps today's behaviour`() {
        val rig = rig()
        val worker = rig.profiles.save(AgentProfile(name = "Backend", role = "backend"))

        val r = rig.profiles.resolveSpawn(SpawnOptions(agentId = worker.id))

        assertNull(r.model)
        assertNull(r.providerId)
        assertNull(r.effort)
    }

    @Test
    fun `a role-X ticket starts on the binding when the profile has none`() {
        val rig = rig()
        rig.profiles.save(AgentProfile(name = "Backend", role = "backend"))
        rig.bind("model-A")
        val board = TicketBoard(rig.store, agents = rig.profiles, sessions = rig.manager)
        val ticket = board.save(TicketSave(title = "Add login", agentId = "role:backend")).ticket

        val started = board.move(TicketMove(ticket.id, TicketStatus.IN_PROGRESS, null, null))

        assertEquals("started", started.ticket.start?.reason)
        assertEquals("model-A", rig.manager.get(started.ticket.sessionId!!)!!.model)
    }

    @Test
    fun `spawn by role alone runs on the binding and carries the role`() {
        val rig = rig()
        rig.bind("model-A", effort = "high")

        val r = rig.profiles.resolveSpawn(SpawnOptions(role = "backend"))

        assertEquals("backend", r.role)
        assertNull(r.agentId)
        assertEquals("model-A", r.model)
        assertEquals("high", r.effort)
        assertEquals("model-S", rig.profiles.resolveSpawn(SpawnOptions(role = "backend", model = "model-S")).model)
    }

    @Test
    fun `unknown role is refused`() {
        val rig = rig()
        val e = assertFailsWith<IllegalArgumentException> { rig.profiles.resolveSpawn(SpawnOptions(role = "nonesuch")) }
        assertEquals("unknown role 'nonesuch'", e.message)
    }

    @Test
    fun `role that disagrees with the agent profile is refused`() {
        val rig = rig()
        val worker = rig.profiles.save(AgentProfile(name = "Backend", role = "backend"))
        assertFailsWith<IllegalArgumentException> { rig.profiles.resolveSpawn(SpawnOptions(agentId = worker.id, role = "qa")) }
        assertEquals("backend", rig.profiles.resolveSpawn(SpawnOptions(agentId = worker.id, role = "backend")).role)
    }

    @Test
    fun `role-only spawn binds a session at creation with the role`() {
        val rig = rig()
        val rec = com.superiorsoftllc.agenthq.daemon.bindSessionOnSpawn(rig.manager, rig.profiles, SpawnOptions(role = "qa"), null)
        assertEquals("qa", rec!!.role)
        assertEquals(null, com.superiorsoftllc.agenthq.daemon.bindSessionOnSpawn(rig.manager, rig.profiles, SpawnOptions(), null))
    }

    @Test
    fun `role round-trips through the new payload decoder`() {
        assertEquals("backend", SpawnOptions.decode("""{"role":"backend"}""").role)
    }
}
