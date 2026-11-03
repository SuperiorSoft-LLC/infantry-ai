package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.DemoLockConfig
import com.superiorsoftllc.infantry.plugin.api.EndpointDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLUGIN-ENDPOINTS-1: D1 (declared id, method, path pattern; valid declaration) and D5 (lockdown closes endpoints). */
class EndpointGateTest {
    private val manifest = ModuleManifest("Acme_Hello", "1.0.0", services = listOf("endpoints"),
        endpoints = listOf(EndpointDeclaration("api", "https://api.example.com/v1/**", listOf("GET", "POST"))))

    @Test
    fun `D1 a declared endpoint, method and path pass and anything else is refused`() {
        val c = ModulesConfig()
        assertNull(EndpointGate.invalid(manifest.endpoints))
        assertNull(EndpointGate.refusal("api", "GET", "/v1/items?x=1", manifest, Tier.CORE, c, false))
        assertTrue(EndpointGate.refusal("other", "GET", "/v1/a", manifest, Tier.CORE, c, false) != null)
        assertTrue(EndpointGate.refusal("api", "DELETE", "/v1/a", manifest, Tier.CORE, c, false) != null)
        assertTrue(EndpointGate.refusal("api", "GET", "/v1/../admin", manifest, Tier.CORE, c, false) != null)
        assertTrue(EndpointGate.invalid(listOf(EndpointDeclaration("x", "http://evil.com/**"))) != null)
        assertEquals(mapOf("A" to "1"), EndpointGate.stripHeaders(mapOf("A" to "1", "authorization" to "x", "X-Infantry-Token" to "y")))
    }

    @Test
    fun `D5 under the lockdown only a core module's listed endpoint passes`() {
        val c = ModulesConfig(demoLock = DemoLockConfig(allowEndpoints = listOf("api")))
        assertNull(EndpointGate.refusal("api", "GET", "/v1/a", manifest, Tier.CORE, c, true))
        assertTrue(EndpointGate.refusal("api", "GET", "/v1/a", manifest, Tier.CORE, ModulesConfig(), true)!!.contains("lockdown"))
    }
}
