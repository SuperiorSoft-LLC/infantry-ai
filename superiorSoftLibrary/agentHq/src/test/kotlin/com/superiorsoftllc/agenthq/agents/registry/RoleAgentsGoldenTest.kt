package com.superiorsoftllc.agenthq.agents.registry

import com.superiorsoftllc.agenthq.daemon.wiring.RoleAgents
import com.superiorsoftllc.agenthq.plugins.official.roles.daemon.ServiceRoles
import com.superiorsoftllc.agenthq.plugins.official.roles.daemon.RoleCatalog
import com.superiorsoftllc.agenthq.protocol.RoleIds
import com.superiorsoftllc.agenthq.plugins.official.roles.daemon.RoleService
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AGENT-REGISTRY-1 golden: today's built-in agents behind the registry are exactly the bundled role catalog, and a session's
 * persona resolves to the same text it did before the registry (the daemon's `rolePersona`: catalog first, registry after).
 */
class RoleAgentsGoldenTest {
    private val catalog = RoleCatalog.load()
    private val registry = AgentRegistry({ RoleAgents.allRecords(catalog.all().map(ServiceRoles::record)) }, { catalog.role(it)?.persona })

    @Test fun `the built-in agents are the bundled roles, in catalog order`() {
        assertEquals(RoleIds.BUILT_IN, registry.list().map { it.definition.id })
        assertEquals(17, registry.list().size)
        for (e in registry.list()) {
            val role = catalog.role(e.definition.id)!!
            assertEquals(role.name, e.definition.name, e.definition.id)
            assertEquals(role.color, e.definition.ui?.color, e.definition.id)
            assertEquals(role.id, e.definition.role, "the persona is referenced, not copied: ${e.definition.id}")
            assertEquals("", e.definition.instructions)
            assertEquals("roles", e.contributor)
            assertTrue(e.enabled)
        }
    }

    @Test fun `every built-in persona resolves to the same text as before`() {
        for (id in RoleIds.BUILT_IN) {
            val before = catalog.role(id)?.persona
            val after = catalog.role(id)?.persona ?: registry.instructionsOf(id)
            assertEquals(before, after, id)
            assertEquals(before.orEmpty(), registry.get(id)!!.effectiveInstructions, id)
            assertTrue(before!!.isNotBlank(), "bundled role $id has a persona")
            assertNull(registry.spawnRefusal(id), "no override = nothing refused: $id")
        }
    }

    @Test fun `a custom role is an agent too, and a role edit reaches the registry at once`() {
        val dir = Files.createTempDirectory("role-agents").toFile()
        val roles = RoleService(dir)
        val live = AgentRegistry({ RoleAgents.allRecords(roles.all().map(ServiceRoles::record)) }, { roles.role(it)?.persona })
        roles.save(com.superiorsoftllc.agenthq.protocol.RoleSave(id = "editor", name = "Editor", persona = "You edit copy."))
        assertEquals("You edit copy.", live.instructionsOf("editor"))
        roles.save(com.superiorsoftllc.agenthq.protocol.RoleSave(id = "editor", name = "Editor", persona = "You edit copy, tersely."))
        assertEquals("You edit copy, tersely.", live.instructionsOf("editor"))
    }
}
