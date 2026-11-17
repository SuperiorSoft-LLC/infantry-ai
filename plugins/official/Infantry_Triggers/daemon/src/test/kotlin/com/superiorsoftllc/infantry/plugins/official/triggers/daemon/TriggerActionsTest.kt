// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.triggers.daemon

import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerActionResult
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerFire
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.TriggerAction
import com.superiorsoftllc.infantry.protocol.TriggerSave
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** TRIG-ACTIONS-1: plugin-defined action kinds, below the wire on a real store. */
class TriggerActionsTest {
    private val path = Files.createTempFile("infantry-actions-test-", ".db").toFile().also { it.delete() }.absolutePath
    private val stores = mutableListOf<SqlInfantryStore>()
    private val services = mutableListOf<TriggerService>()

    @AfterTest
    fun cleanUp() { services.forEach { it.close() }; stores.forEach { it.close() } }

    private fun service(): TriggerService {
        val store = SqlInfantryStore.open(path).also { stores += it }
        return TriggerService(repo = CoreTriggerStore(store.triggers), attention = MemAttention(), startNext = { null }, sessions = { null }, pumpMs = 3_600_000)
            .also { services += it }
    }

    private fun save(s: TriggerService, kind: String = "acme.log") =
        s.save(TriggerSave(name = "t", kind = "manual", coalesceMs = 0, action = TriggerAction(kind = kind, prompt = "hi")))

    private fun await(c: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!c()) { check(System.currentTimeMillis() < end) { "timeout" }; Thread.sleep(10) }
    }

    private fun settled(s: TriggerService, id: String) = await { s.events(id).size == 1 && s.events(id).single().disposition != "started" && s.list().single().state != "running" }

    @Test
    fun `a registered kind is fired with the trigger and its result is recorded`() {
        val s = service()
        val fires = mutableListOf<TriggerFire>()
        s.registerAction("acme.log", "acme") { f -> fires += f; TriggerActionResult.Done }
        val t = save(s)
        s.fire(t.id, source = "manual")
        settled(s, t.id)
        assertEquals("delivered", s.events(t.id).single().disposition)
        assertEquals(t.id, fires.single().trigger.id)
        assertEquals("manual", fires.single().source)
        assertEquals("armed", s.list().single().state)
    }

    @Test
    fun `a refused or throwing handler is recorded refused and never trips`() {
        val s = service()
        s.registerAction("acme.log", "acme") { throw IllegalStateException("boom") }
        val t = save(s)
        s.fire(t.id)
        settled(s, t.id)
        assertEquals("refused", s.events(t.id).single().disposition)
        assertEquals(true, s.events(t.id).single().digest!!.contains("boom"))
        assertEquals("armed", s.list().single().state)
    }

    @Test
    fun `closing the handle or unloading the module removes the kind`() {
        val s = service()
        val h = s.registerAction("acme.log", "acme") { TriggerActionResult.Done }
        h.close()
        val t = save(s)
        s.fire(t.id)
        settled(s, t.id)
        assertEquals("refused_unknown_action", s.events(t.id).single().disposition)
        s.registerAction("acme.log", "acme") { TriggerActionResult.Done } // free again
        s.pruneActions(setOf("other"))
        val u = save(s, "acme.log")
        s.fire(u.id)
        await { s.events(u.id).size == 1 && s.events(u.id).single().disposition == "refused_unknown_action" }
    }

    @Test
    fun `a duplicate or built-in or malformed kind is refused at registration`() {
        val s = service()
        s.registerAction("acme.log", "acme") { TriggerActionResult.Done }
        assertFailsWith<IllegalStateException> { s.registerAction("acme.log", "other") { TriggerActionResult.Done } }
        assertFailsWith<IllegalArgumentException> { s.registerAction("prompt", "acme") { TriggerActionResult.Done } }
        assertFailsWith<IllegalArgumentException> { s.registerAction("start_next", "acme") { TriggerActionResult.Done } }
    }
}
