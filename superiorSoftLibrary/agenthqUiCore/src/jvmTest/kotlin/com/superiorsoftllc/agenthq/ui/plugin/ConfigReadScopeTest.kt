package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.ConfigDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ModuleSetting
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.ui.shell.ConfigContext
import com.superiorsoftllc.agenthq.ui.shell.ConfigValues
import com.superiorsoftllc.agenthq.ui.shell.GatedConfig
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CONFIG-FIELDS-4 (G8 wiring): the factory answers each module's reader rule; community default deny, core/official unchanged. */
class ConfigReadScopeTest {
    private fun module(name: String, tier: Tier, services: List<String> = emptyList()) =
        AgentHqModule(ModuleManifest(name = name, version = "1.0.0", services = services, config = listOf(ConfigDeclaration(node = "$name.panel"))), tier = tier)

    private fun factory(cfg: ModulesConfig = ModulesConfig()) =
        TabFactory(listOf(module("acme", Tier.COMMUNITY), module("big", Tier.OFFICIAL), module("core1", Tier.CORE)), modulesConfig = cfg)

    private val inner = object : ConfigContext {
        override fun values(nodeId: String): ConfigValues = object : ConfigValues {
            override val keys = setOf("title")
            override fun string(key: String): String? = "from-$nodeId"
            override fun int(key: String): Int? = null
            override fun double(key: String): Double? = null
            override fun boolean(key: String): Boolean? = null
        }
        override fun <T : Any> implementation(type: KClass<T>, key: String): T? = null
    }

    @Test fun `a community module reads its own nodes but not another node`() {
        val f = factory()
        assertTrue(f.configGated("acme"))
        val view = GatedConfig(inner) { f.configReadAllows("acme", it) }
        assertEquals("from-acme.panel", view.values("acme.panel").string("title"))
        assertEquals("from-acme", view.values("acme").string("title"))
        assertNull(view.values("infantry.shell.board").string("title"))
        assertEquals(emptySet(), view.values("infantry.shell.board").keys)
    }

    @Test fun `core and official modules are not gated`() {
        val f = factory()
        assertFalse(f.configGated("big"))
        assertFalse(f.configGated("core1"))
        assertTrue(f.configReadAllows("big", "infantry.shell.board"))
        assertTrue(f.configReadAllows("core1", "infantry.shell.board"))
        assertTrue(f.configReadAllows("nobody", "infantry.shell.board"))
    }

    @Test fun `the config read grant and the dev switch open another node`() {
        val withGrant = TabFactory(listOf(module("acme", Tier.COMMUNITY, listOf("config.read"))), modulesConfig = ModulesConfig(modules = mapOf("acme" to ModuleSetting(allow = listOf("config.read")))))
        assertTrue(withGrant.configReadAllows("acme", "infantry.shell.board"))
        val off = factory(ModulesConfig(configReadGate = false))
        assertFalse(off.configGated("acme"))
        assertTrue(off.configReadAllows("acme", "infantry.shell.board"))
    }
}
