package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.daemon.api.NoServices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** AR-5: provide / find, tier refusal, unload removes the provider. */
class ServiceRegistryTest {
    interface Greeter { fun hi(): String }
    private val impl = object : Greeter { override fun hi() = "hi" }

    @Test fun `provide then find returns the provider and a missing one is null`() {
        val r = ServiceRegistry()
        assertNull(r.find(Greeter::class))
        r.provide("Acme_A", Greeter::class, impl)
        assertSame(impl, r.find(Greeter::class))
        assertEquals("Acme_A", r.ownerOf(Greeter::class))
    }

    @Test fun `a second provider of the same type is refused and the first stays`() {
        val r = ServiceRegistry()
        r.provide("A", Greeter::class, impl)
        assertFailsWith<IllegalStateException> { r.provide("B", Greeter::class, object : Greeter { override fun hi() = "x" }) }
        assertSame(impl, r.find(Greeter::class))
    }

    @Test fun `a community module without the grant cannot publish but can look up`() {
        val r = ServiceRegistry()
        val tracked = mutableListOf<AutoCloseable>()
        val community = ScopedServices(r, "C_Mod", { false }) { tracked += it }
        assertFailsWith<IllegalStateException> { community.provide(Greeter::class, impl) }
        assertNull(r.find(Greeter::class))
        val official = ScopedServices(r, "O_Mod", { true }) { tracked += it }
        official.provide(Greeter::class, impl)
        assertSame(impl, community.find(Greeter::class))
    }

    @Test fun `unloading the module closes its handles and removes the provider`() {
        val r = ServiceRegistry()
        val tracked = mutableListOf<AutoCloseable>()
        ScopedServices(r, "O_Mod", { true }) { tracked += it }.provide(Greeter::class, impl)
        tracked.forEach { it.close() }
        assertNull(r.find(Greeter::class))
        r.provide("O2", Greeter::class, impl) // slot is free again
    }

    @Test fun `a stale handle never removes a newer provider`() {
        val r = ServiceRegistry()
        val h = r.provide("A", Greeter::class, impl)
        h.close()
        val other = object : Greeter { override fun hi() = "o" }
        r.provide("B", Greeter::class, other)
        h.close()
        assertSame(other, r.find(Greeter::class))
    }

    @Test fun `NoServices finds nothing and refuses to publish`() {
        assertNull(NoServices.find(Greeter::class))
        assertFailsWith<IllegalStateException> { NoServices.provide(Greeter::class, impl) }
    }
}
