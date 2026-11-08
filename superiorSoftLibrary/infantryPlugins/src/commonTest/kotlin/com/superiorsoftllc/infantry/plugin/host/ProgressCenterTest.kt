// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.ProgressSpec
import com.superiorsoftllc.infantry.plugin.api.ProgressState
import com.superiorsoftllc.infantry.plugin.api.ProgressTask
import com.superiorsoftllc.infantry.plugin.api.ProgressWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CORE-PROGRESS-DOWNLOAD-1: the core progress system: tasks, cancel / retry, and a plugin presenter over the core default. */
class ProgressCenterTest {
    private var now = 1_000L
    private val faults = mutableListOf<String>()
    private val failed = mutableListOf<ProgressTask>()
    private var setting = ""
    private val center = ProgressCenter(clock = { now }, presenterSetting = { setting }, minIntervalMs = { 0 }, onFault = { id, _ -> faults += id }, onFailed = { failed += it })
    private val core = mutableListOf<ProgressTask>()

    init { center.setDefault { core += it } }

    @Test fun `a task moves through its steps to done`() {
        val h = center.forSource("plugins").start(ProgressSpec("Installing x", steps = listOf("Downloading", "Verifying"), unit = "bytes"))
        h.step("Downloading"); h.amount(5, 10); h.step("Verifying"); h.done()
        val t = center.task(h.id)!!
        assertEquals(ProgressState.DONE, t.state)
        assertEquals("plugins", t.source)
        assertEquals(1, core.single { it.step == "Verifying" && !it.finished }.stepIndex)
        assertEquals(listOf(ProgressState.RUNNING, ProgressState.DONE), core.map { it.state }.distinct())
    }

    @Test fun `a plugin presenter replaces the core default, and the default draws again when it throws`() {
        val shown = mutableListOf<String>()
        val reg = center.forSource("Acme_Fancy").present("", { shown += it.step })
        assertEquals("Acme_Fancy", center.activePresenterId())
        val h = center.forSource("plugins").start(ProgressSpec("t"))
        h.step("one")
        assertEquals(listOf("", "one"), shown)
        assertTrue(core.isEmpty(), "the core default is not drawn while a plugin presenter works")

        center.forSource("Acme_Broken").present("", { throw IllegalStateException("boom") })
        h.step("two")
        assertEquals(listOf("Acme_Broken"), faults, "the throwing presenter is reported as its fault")
        assertEquals("two", core.single().step, "the core default draws the change the plugin could not")

        reg.close()
        setting = "Acme_Fancy"
        h.step("three")
        assertEquals("three", core.last().step, "a closed presenter named by the setting falls back to the default")
    }

    @Test fun `the presenter setting picks one presenter among several`() {
        val a = mutableListOf<String>(); val b = mutableListOf<String>()
        center.present("a", { a += it.step }); center.present("b", { b += it.step })
        setting = "a"
        center.forSource("x").start(ProgressSpec("t")).step("s")
        assertEquals(listOf("", "s"), a)
        assertTrue(b.isEmpty())
    }

    @Test fun `cancel reaches the task's listener, a failure is reported and Retry runs the task's retry`() {
        var retried = 0
        var stopped = 0
        val h = center.forSource("x").start(ProgressSpec("t", cancellable = true, retry = { retried++ }))
        h.onCancel { stopped++ }
        assertTrue(center.cancel(h.id))
        assertTrue(h.cancelRequested)
        assertEquals(1, stopped)
        assertFalse(center.retry(h.id), "a live task is not retried")
        h.fail("sha256 mismatch for x")
        assertEquals("sha256 mismatch for x", failed.single().error)
        assertTrue(center.task(h.id)!!.retryable)
        assertTrue(center.retry(h.id))
        assertEquals(1, retried)
    }

    @Test fun `finished tasks are kept up to the limit, and the wire round-trips a task`() {
        val c = ProgressCenter(clock = { now }, keepFinished = { 2 })
        repeat(3) { c.forSource("x").start(ProgressSpec("t$it", id = "id$it")).done() }
        assertEquals(listOf("id2", "id1"), c.tasks().map { it.id })
        val t = c.task("id2")!!
        assertEquals(t, ProgressWire.task(ProgressWire.encode(t)))
        c.clearFinished()
        assertTrue(c.tasks().isEmpty())
    }
}
