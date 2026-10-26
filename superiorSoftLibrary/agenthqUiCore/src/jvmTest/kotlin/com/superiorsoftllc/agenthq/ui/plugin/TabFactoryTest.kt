package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.Events
import com.superiorsoftllc.agenthq.plugin.api.InterceptorDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ModuleSetting
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.ObserverDeclaration
import com.superiorsoftllc.agenthq.plugin.api.PluginEvent
import com.superiorsoftllc.agenthq.plugin.api.PreferenceDeclaration
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.AcceptedPreference
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.plugin.host.ServiceRegistry
import com.superiorsoftllc.agenthq.plugin.host.SimpleEventBus
import com.superiorsoftllc.agenthq.plugin.host.provideEvents
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.plugins.core.InfantryGit
import com.superiorsoftllc.agenthq.ui.model.AppState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLUGINS-1: how the factory binds what a module registers to what its module.json declares. */
class TabFactoryTest {
    private val icon = InfantryGit.tabs.single().icon
    private val heard = mutableListOf<String>()

    private fun tab(id: String, container: String = TabContainers.CENTER, title: String = id, order: Int = 500) =
        TabProvider(id = id, container = container, order = order, title = { title }, icon = icon, testTag = "tag-$id") {}

    private fun acme(
        tabs: List<TabProvider>,
        declared: List<TabDeclaration> = tabs.map { TabDeclaration(it.id, it.container) },
        interceptors: Map<String, TabInterceptor> = emptyMap(),
        declaredInterceptors: List<InterceptorDeclaration> = emptyList(),
        observers: Map<String, (PluginEvent) -> Unit> = emptyMap(),
        declaredObservers: List<ObserverDeclaration> = emptyList(),
    ) = AgentHqModule(
        ModuleManifest(
            "Acme_Hello", "1.0.0", author = "Acme", license = "NOASSERTION", tabs = declared, interceptors = declaredInterceptors,
            observers = declaredObservers, events = listOf(Events.TAB_OPENED), services = listOf("events"),
        ),
        tabs, interceptors, observers = observers, tier = Tier.COMMUNITY,
    )

    @Test
    fun `a module's tab lands in its container by order and names the module`() {
        val f = TabFactory(CoreModules.all + acme(listOf(tab("acme.hello", order = 95))))
        val center = f.tabs(TabContainers.CENTER).map { it.id }
        assertEquals("acme.hello", center[center.indexOf("infantry.git") + 1], "order 95 sits between Git (90) and Stats (100)")
        assertEquals("Acme_Hello", f.find("acme.hello")?.module)
        assertEquals(Tier.COMMUNITY, f.moduleOf("acme.hello")?.tier)
    }

    @Test
    fun `an undeclared tab or one in another container is refused`() {
        val f = TabFactory(
            listOf(acme(listOf(tab("acme.sneak"), tab("acme.moved", TabContainers.RIGHT)), declared = listOf(TabDeclaration("acme.moved", "center")))),
        )
        assertNull(f.find("acme.sneak"))
        assertNull(f.find("acme.moved"))
        assertTrue(f.problems.any { "acme.sneak is not declared" in it })
        assertTrue(f.problems.any { "acme.moved is declared in center, not right" in it })
    }

    @Test
    fun `an accepted preference puts the replacement in the replaced tab's place and hides it under its own id`() {
        val f = TabFactory(
            CoreModules.all + acme(listOf(tab("acme.git", title = "Acme Git"))),
            preferences = listOf(AcceptedPreference("Acme_Hello", "infantry.git", "acme.git")),
        )
        val git = f.find("infantry.git")!!
        assertEquals("Acme Git", f.title(git, AppState()))
        assertEquals("Acme_Hello", git.module)
        assertEquals("center-tab-GIT", git.testTag)
        assertEquals(90, git.order)
        assertNull(f.find("acme.git"), "shown only as the replacement")
        assertEquals(CoreModules.factory.tabs(TabContainers.CENTER).map { it.id }, f.tabs(TabContainers.CENTER).map { it.id })
    }

    @Test
    fun `without an accepted preference the core tab stays and the would-be replacement is not shown`() {
        val offered = acme(listOf(tab("acme.git", title = "Acme Git"))).let { m ->
            AgentHqModule(m.manifest.copy(preferences = listOf(PreferenceDeclaration("infantry.git", "acme.git"))), m.tabs, tier = Tier.COMMUNITY)
        }
        val f = TabFactory(CoreModules.all + offered)
        assertEquals("Infantry_Git", f.find("infantry.git")?.module)
        assertNull(f.find("acme.git"))
        assertTrue(f.problems.any { "acme.git only replaces infantry.git, which was not accepted" in it })
        assertEquals(CoreModules.factory.tabs(TabContainers.CENTER).map { it.id }, f.tabs(TabContainers.CENTER).map { it.id })
    }

    @Test
    fun `a declared interceptor wraps the title, an undeclared one is refused`() {
        val hi = TabInterceptor(title = { _, proceed -> proceed() + " +hi" })
        val f = TabFactory(
            CoreModules.all + acme(
                emptyList(),
                interceptors = mapOf("hi" to hi, "sneak" to TabInterceptor(title = { _, _ -> "pwned" })),
                declaredInterceptors = listOf(InterceptorDeclaration("infantry.epics", "hi"), InterceptorDeclaration("infantry.nope", "hi")),
            ),
        )
        assertEquals("Epics +hi", f.title(f.find("infantry.epics")!!, AppState()))
        assertEquals("Git", f.title(f.find("infantry.git")!!, AppState()))
        assertTrue(f.problems.any { "interceptor sneak is not declared" in it })
        assertTrue(f.problems.any { "targets infantry.nope" in it })
    }

    @Test
    fun `a declared observer hears its event through the gated bus`() {
        val bus = SimpleEventBus()
        val module = acme(
            emptyList(),
            observers = mapOf("log" to { e: PluginEvent -> heard += e.data.getValue("id") }),
            declaredObservers = listOf(ObserverDeclaration(Events.TAB_OPENED, "log")),
        )
        val config = ModulesConfig(modules = mapOf("Acme_Hello" to ModuleSetting(enabled = true, allow = listOf("events"))))
        val registry = ServiceRegistry(config).apply { provideEvents(bus) }
        val status = ModuleStatus("Acme_Hello", Tier.COMMUNITY, ModuleState.LOADED, manifest = module.manifest)
        val f = TabFactory(listOf(module), services = { registry.forModule(status) }, events = bus)
        f.events.publish(PluginEvent(Events.TAB_OPENED, mapOf("id" to "infantry.git")))
        assertEquals(listOf("infantry.git"), heard)
        assertEquals(emptyList(), f.problems)
    }

    @Test
    fun `the badge line names the author and the license`() {
        assertEquals("Acme_Hello 1.0.0 by Acme - license NOASSERTION", communityByline(acme(emptyList()).manifest))
    }
}
