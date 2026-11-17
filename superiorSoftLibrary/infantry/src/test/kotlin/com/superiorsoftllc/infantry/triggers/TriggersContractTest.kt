// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.triggers

import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerActionHandler
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerEventRecord
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerEvents
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerFilter
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerOwnership
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerRecord
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerSaveRequest
import com.superiorsoftllc.infantry.daemon.api.triggers.Triggers
import com.superiorsoftllc.infantry.daemon.plugins.ScopedServices
import com.superiorsoftllc.infantry.daemon.plugins.ServiceRegistry
import com.superiorsoftllc.infantry.protocol.TriggerSave
import com.superiorsoftllc.infantry.protocol.TriggerView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TRIG-API-1: the `Triggers` SDK contract, proven on a fake in-memory provider published with `provideScoped` the way the
 * trigger plugin will (TRIG-2c). The owner comes from the host (the caller's module id), never from the request.
 */
class TriggersContractTest {
    /** The shared state of the fake service; each caller gets a [View] bound to its module id. */
    private class FakeService {
        val rows = linkedMapOf<String, TriggerRecord>()
        var next = 0

        inner class View(private val caller: String) : Triggers {
            override fun list(filter: TriggerFilter) = rows.values.filter {
                (filter.owner == null || it.owner == filter.owner) && (filter.ref == null || it.ref == filter.ref) &&
                    (filter.projectId == null || it.projectId == filter.projectId) && (filter.kind == null || it.kind == filter.kind)
            }
            override fun get(id: String) = rows[id]
            override fun save(request: TriggerSaveRequest): TriggerRecord {
                val r = TriggerOwnership.stamp(request, caller)
                r.id?.let { id -> TriggerOwnership.requireChange(rows[id] ?: throw IllegalArgumentException("no trigger $id"), caller) }
                val id = r.id ?: "trg-${++next}"
                return TriggerRecord(
                    id = id, name = r.name, kind = r.kind, action = r.action, enabled = r.enabled, state = "armed",
                    coalesceMs = r.coalesceMs, ceilingRuns = r.ceilingRuns, ceilingWindowMs = r.ceilingWindowMs, createdAt = 0,
                    projectId = r.projectId, fireAt = r.fireAt, deleteAfterFire = r.deleteAfterFire, owner = r.owner, ref = r.ref,
                ).also { rows[id] = it }
            }
            override fun delete(id: String): Boolean {
                val t = rows[id] ?: return false
                TriggerOwnership.requireChange(t, caller)
                return rows.remove(id) != null
            }
            private fun change(id: String, f: (TriggerRecord) -> TriggerRecord): TriggerRecord {
                val t = rows[id] ?: throw IllegalArgumentException("no trigger $id")
                TriggerOwnership.requireChange(t, caller)
                return f(t).also { rows[id] = it }
            }
            override fun fire(id: String, source: String?) = change(id) { it }
            override fun arm(id: String) = change(id) { it.copy(state = "armed") }
            override fun disable(id: String) = change(id) { it.copy(state = "disabled", enabled = false) }
            override fun events(id: String) = emptyList<TriggerEventRecord>()
            override fun registerAction(kind: String, handler: TriggerActionHandler): AutoCloseable = throw UnsupportedOperationException("TRIG-ACTIONS-1")
        }
    }

    private val registry = ServiceRegistry()
    private val service = FakeService()
    private fun module(id: String) = ScopedServices(registry, id, { false }) {}

    init {
        ScopedServices(registry, "Infantry_Triggers", { true }) {}.provideScoped(Triggers::class) { service.View(it) }
    }

    private val calendar = module("Infantry_Calendar").find(Triggers::class)!!
    private val pods = module("Acme_Pods").find(Triggers::class)!!

    private fun reminder(owner: String? = null) = TriggerSaveRequest(name = "Stand-up", kind = "at", fireAt = 1_000, deleteAfterFire = true, owner = owner, ref = "calendar:cal-1")

    @Test fun `the owner is stamped from the calling module`() {
        val t = calendar.save(reminder())
        assertEquals("Infantry_Calendar", t.owner)
        assertEquals("calendar:cal-1", t.ref)
        assertEquals(listOf(t), pods.list(TriggerFilter(owner = "Infantry_Calendar")))
        assertTrue(pods.list(TriggerFilter(owner = "Acme_Pods")).isEmpty())
        assertEquals("Infantry_Calendar", calendar.save(reminder(owner = "Infantry_Calendar")).owner, "naming yourself is allowed")
    }

    @Test fun `a forged owner is refused and nothing is stored`() {
        val e = assertFailsWith<IllegalArgumentException> { pods.save(reminder(owner = "Infantry_Calendar")) }
        assertTrue("Acme_Pods" in e.message.orEmpty())
        assertTrue(service.rows.isEmpty())
    }

    @Test fun `only the owner may change an owned trigger`() {
        val t = calendar.save(reminder())
        assertFailsWith<IllegalArgumentException> { pods.save(reminder().copy(id = t.id, name = "hijacked")) }
        assertFailsWith<IllegalArgumentException> { pods.delete(t.id) }
        assertFailsWith<IllegalArgumentException> { pods.disable(t.id) }
        assertFailsWith<IllegalArgumentException> { pods.fire(t.id) }
        assertEquals("Stand-up", calendar.get(t.id)!!.name)
        assertEquals("disabled", calendar.disable(t.id).state)
        assertTrue(calendar.delete(t.id))
        assertFalse(calendar.delete(t.id))
        assertNull(calendar.get(t.id))
    }

    @Test fun `a person's trigger is changed in the app, never by a module`() {
        val person = TriggerRecord(id = "p1", name = "Mine", kind = "manual", action = com.superiorsoftllc.infantry.daemon.api.triggers.TriggerActionRecord(),
            enabled = true, state = "armed", coalesceMs = 0, ceilingRuns = 6, ceilingWindowMs = 1, createdAt = 0)
        assertFalse(TriggerOwnership.mayChange(person, "Infantry_Calendar"))
        assertFailsWith<IllegalArgumentException> { TriggerOwnership.stamp(TriggerSaveRequest(name = "x"), " ") }
    }

    @Test fun `a daemon without the provider answers null`() {
        assertNull(ScopedServices(ServiceRegistry(), "Infantry_Calendar", { false }) {}.find(Triggers::class))
    }

    @Test fun `the API DTOs mirror the wire DTOs field for field and the defaults are today's`() {
        fun names(k: kotlin.reflect.KClass<*>) = k.java.declaredFields.map { it.name }.filter { it != "Companion" && !it.startsWith("$") }.toSet()
        assertEquals(names(TriggerSave::class), names(TriggerSaveRequest::class))
        assertEquals(names(TriggerView::class), names(TriggerRecord::class))
        val wire = TriggerSave(name = "n")
        val api = TriggerSaveRequest(name = "n")
        assertEquals(listOf(wire.kind, wire.coalesceMs, wire.ceilingRuns, wire.ceilingWindowMs, wire.action.kind, wire.fireAt, wire.deleteAfterFire, wire.owner, wire.ref),
            listOf(api.kind, api.coalesceMs, api.ceilingRuns, api.ceilingWindowMs, api.action.kind, api.fireAt, api.deleteAfterFire, api.owner, api.ref))
        assertEquals(TriggerSave(name = "n"), TriggerSave.decode("""{"name":"n"}"""), "an old payload decodes to today's defaults")
        assertEquals(listOf("trigger.fired", "trigger.delivered", "trigger.refused", "trigger.target_gone"),
            listOf(TriggerEvents.FIRED, TriggerEvents.DELIVERED, TriggerEvents.REFUSED, TriggerEvents.TARGET_GONE))
    }
}
