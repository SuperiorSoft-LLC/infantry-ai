package com.superiorsoftllc.agenthq.plugin.host

import com.superiorsoftllc.agenthq.plugin.api.Events
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ModuleSetting
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.Notifications
import com.superiorsoftllc.agenthq.plugin.api.PluginEvent
import com.superiorsoftllc.agenthq.plugin.api.Services
import com.superiorsoftllc.agenthq.plugin.api.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLUGINS-1: services, routes and events are each gated by the module's declarations and the owner's say. */
class ServiceGateTest {
    private val manifest = ModuleManifest(
        "Acme_Hello", "1.0.0", author = "Acme", license = "NOASSERTION",
        services = listOf("notifications", "events", "daemon"), events = listOf(Events.TAB_OPENED), routes = listOf("GET /health", "POST /tickets"),
    )

    private fun status(tier: Tier) = ModuleStatus(manifest.name, tier, ModuleState.LOADED, manifest = manifest)

    @Test
    fun `a service must be declared, not denied, and for community allowed`() {
        val config = ModulesConfig(modules = mapOf("Acme_Hello" to ModuleSetting(allow = listOf("notifications"), deny = listOf("events"))))
        assertNull(ServiceGate.refusal("notifications", manifest, Tier.COMMUNITY, config))
        assertEquals("not declared in module.json", ServiceGate.refusal("storage", manifest, Tier.COMMUNITY, config))
        assertEquals("denied in modules.json", ServiceGate.refusal("events", manifest, Tier.CORE, config))
        assertEquals("a community module needs it allowed in modules.json", ServiceGate.refusal("daemon", manifest, Tier.COMMUNITY, config))
        assertNull(ServiceGate.refusal("daemon", manifest, Tier.CORE, config), "core has what it declares")
    }

    @Test
    fun `the registry hands out only gated services`() {
        val log = mutableListOf<String>()
        val registry = ServiceRegistry(ModulesConfig(modules = mapOf("Acme_Hello" to ModuleSetting(allow = listOf("notifications")))), log::add)
        registry.provide(Services.NOTIFICATIONS) { Notifications { _, _ -> } }
        registry.provideEvents(SimpleEventBus())
        val services = registry.forModule(status(Tier.COMMUNITY))
        assertNotNull(services.get(Services.NOTIFICATIONS))
        assertNull(services.get(Services.EVENTS))
        assertTrue(log.any { "service events refused" in it })
    }

    @Test
    fun `a module sees only the events it declared`() {
        val bus = SimpleEventBus()
        val gated = GatedEventBus(bus, "Acme_Hello", listOf(Events.TAB_OPENED))
        val got = mutableListOf<String>()
        gated.subscribe(Events.TAB_OPENED) { got += it.data.getValue("id") }
        gated.subscribe(Events.SESSION_SELECTED) { got += "never" }
        bus.publish(PluginEvent(Events.TAB_OPENED, mapOf("id" to "infantry.git")))
        bus.publish(PluginEvent(Events.SESSION_SELECTED))
        assertEquals(listOf("infantry.git"), got)
    }

    @Test
    fun `an observer that throws does not stop the others`() {
        val bus = SimpleEventBus()
        var reached = false
        bus.subscribe("x") { error("boom") }
        bus.subscribe("x") { reached = true }
        bus.publish(PluginEvent("x"))
        assertTrue(reached)
    }
}
