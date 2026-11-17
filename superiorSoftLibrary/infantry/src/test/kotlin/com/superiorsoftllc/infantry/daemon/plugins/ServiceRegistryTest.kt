// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.NoServices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** AR-5: provide / find, tier refusal, unload removes the provider. TRIG-API-1: caller-scoped providers. */
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

    // TRIG-API-1: provideScoped
    private class Who(val caller: String) : Greeter, AutoCloseable {
        var closed = false
        override fun hi() = "hi $caller"
        override fun close() { closed = true }
    }

    @Test fun `a scoped provider sees each caller's module id and caches one instance per caller`() {
        val r = ServiceRegistry()
        val made = mutableListOf<String>()
        ScopedServices(r, "Infantry_Triggers", { true }) {}.provideScoped(Greeter::class) { made += it; Who(it) }
        val a = ScopedServices(r, "Infantry_Calendar", { false }) {}
        val b = ScopedServices(r, "Acme_Pods", { false }) {}
        assertEquals("hi Infantry_Calendar", a.find(Greeter::class)!!.hi())
        assertEquals("hi Acme_Pods", b.find(Greeter::class)!!.hi())
        assertSame(a.find(Greeter::class), a.find(Greeter::class))
        assertNotSame(a.find(Greeter::class), b.find(Greeter::class))
        assertEquals("hi core", r.find(Greeter::class)!!.hi())
        assertEquals(listOf("Infantry_Calendar", "Acme_Pods", "core"), made)
        assertEquals("Infantry_Triggers", r.ownerOf(Greeter::class))
    }

    @Test fun `a caller cannot choose the id the factory sees`() {
        val r = ServiceRegistry()
        r.provideScoped("P", Greeter::class) { Who(it) }
        // The only way in is the caller's own ScopedServices, built by the host with the module's id.
        val forger = ScopedServices(r, "Acme_Evil", { false }) {}
        assertEquals("hi Acme_Evil", forger.find(Greeter::class)!!.hi())
    }

    @Test fun `unloading a caller drops and closes only its instance`() {
        val r = ServiceRegistry()
        r.provideScoped("P", Greeter::class) { Who(it) }
        val tracked = mutableListOf<AutoCloseable>()
        val gone = ScopedServices(r, "Gone", { false }) { tracked += it }
        val stays = ScopedServices(r, "Stays", { false }) {}
        val first = gone.find(Greeter::class) as Who
        val kept = stays.find(Greeter::class) as Who
        assertEquals(1, tracked.size, "the first lookup hands the host one eviction handle")
        gone.find(Greeter::class)
        assertEquals(1, tracked.size, "a cached lookup hands none")
        tracked.forEach { it.close() }
        assertTrue(first.closed)
        assertTrue(!kept.closed)
        assertSame(kept, stays.find(Greeter::class))
        assertNotSame(first, gone.find(Greeter::class), "a reloaded caller gets a fresh instance")
    }

    @Test fun `closing the scoped provider removes it and closes every cached instance`() {
        val r = ServiceRegistry()
        val h = r.provideScoped("P", Greeter::class) { Who(it) }
        val x = ScopedServices(r, "X", { false }) {}.find(Greeter::class) as Who
        h.close()
        assertTrue(x.closed)
        assertNull(r.find(Greeter::class))
        r.provide("Q", Greeter::class, impl) // slot is free again
    }

    @Test fun `a plain and a scoped provider of one type clash and community needs the grant`() {
        val r = ServiceRegistry()
        r.provide("A", Greeter::class, impl)
        assertFailsWith<IllegalStateException> { r.provideScoped("B", Greeter::class) { Who(it) } }
        assertFailsWith<IllegalStateException> { ScopedServices(ServiceRegistry(), "C_Mod", { false }) {}.provideScoped(Greeter::class) { Who(it) } }
        assertFailsWith<IllegalStateException> { NoServices.provideScoped(Greeter::class) { Who(it) } }
    }
}
