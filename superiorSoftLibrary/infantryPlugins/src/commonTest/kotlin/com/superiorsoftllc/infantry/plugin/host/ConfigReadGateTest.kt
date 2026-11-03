// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ModuleSetting
import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.Tier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CONFIG-FIELDS-3 (G8): a community module reads another node's config only with the `config.read` grant. */
class ConfigReadGateTest {
    private val other = "infantry.board.list"
    private fun manifest(services: List<String> = emptyList()) =
        ModuleManifest(name = "acme", version = "1.0.0", services = services, config = listOf(ConfigDeclaration(node = "acme.panel")))
    private fun allows(m: ModuleManifest, tier: Tier, cfg: ModulesConfig, node: String) = ConfigReadGate.allows("acme", m, tier, cfg, node)
    private fun granted(vararg allow: String) = ModulesConfig(modules = mapOf("acme" to ModuleSetting(allow = allow.toList())))

    @Test fun communityReadsItsOwnNodesWithoutAGrant() {
        assertTrue(allows(manifest(), Tier.COMMUNITY, ModulesConfig(), "acme"))
        assertTrue(allows(manifest(), Tier.COMMUNITY, ModulesConfig(), "acme.panel.x"))
        assertTrue(allows(manifest(), Tier.COMMUNITY, ModulesConfig(), "acme.panel"))
    }

    @Test fun communityIsDeniedAnotherNodeByDefault() {
        assertFalse(allows(manifest(), Tier.COMMUNITY, ModulesConfig(), other))
        // declared but not allowed by the owner, and allowed but not declared: both still deny
        assertFalse(allows(manifest(listOf("config.read")), Tier.COMMUNITY, ModulesConfig(), other))
        assertFalse(allows(manifest(), Tier.COMMUNITY, granted("config.read"), other))
    }

    @Test fun communityReadsAnotherNodeWhenDeclaredAndAllowed() {
        assertTrue(allows(manifest(listOf("config.read")), Tier.COMMUNITY, granted("config.read"), other))
    }

    @Test fun ownerDenyBeatsTheGrant() {
        val cfg = ModulesConfig(modules = mapOf("acme" to ModuleSetting(allow = listOf("config.read"), deny = listOf("config.read"))))
        assertFalse(allows(manifest(listOf("config.read")), Tier.COMMUNITY, cfg, other))
    }

    @Test fun coreAndOfficialAreTrustedUnchanged() {
        assertTrue(allows(manifest(), Tier.CORE, ModulesConfig(), other))
        assertTrue(allows(manifest(), Tier.OFFICIAL, ModulesConfig(), other))
    }

    @Test fun theDevSwitchTurnsTheGateOff() {
        assertTrue(allows(manifest(), Tier.COMMUNITY, ModulesConfig(configReadGate = false), other))
    }
}
