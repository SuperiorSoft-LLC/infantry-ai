// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** TODO-PROGRESS-1 (1.130.0): the agent's to-do list drives the bar as `count`, below its own report and above the sleep hint. */
class TodoProgressTest {
    private object Store : SessionStore {
        override fun load() = emptyList<SessionRecord>()
        override fun saveAll(sessions: Collection<SessionRecord>) = Unit
    }

    private object Provider : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) = ProcessSpec(listOf("fake"))
        override fun sessionIdFromLine(line: String) = null
    }

    private class Proc(private val l: ProcessListener) : RunningProcess {
        override val pid = 1L
        override val isAlive = true
        override fun writeInput(text: String) = Unit
        override fun closeInput() = Unit
        override fun killTree() = l.onExit(1)
        override fun awaitExit() = 0
    }

    private val runner = object : ProcessRunner {
        override val id = "fake"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = Proc(listener)
    }

    private var now = 1_000L
    private val m = SessionManager(Provider, runner, Store, killGraceMs = 50, clock = { now }, backgroundPollMs = 0, idleSweepMs = 0)

    private fun list(vararg s: String) = s.mapIndexed { i, st -> "item$i" to st }

    @Test fun `of counts N of M and names the current item`() {
        assertEquals(TodoProgress.Counts(1, 3, "item1"), TodoProgress.of(list("done", "in_progress", "pending")))
        assertEquals(TodoProgress.Counts(1, 2, "item1"), TodoProgress.of(list("done", "pending")))
        assertEquals(true, TodoProgress.of(list("done", "done")).finished)
        assertEquals(TodoProgress.Counts(0, 0, null), TodoProgress.of(emptyList()))
    }

    @Test fun `a list sets a count bar that moves as items are checked and finishes when all are done`() {
        val id = m.startNew("go").id
        m.setTodoProgress(id, list("in_progress", "pending", "pending"))
        val p = assertNotNull(m.get(id)?.progress)
        assertEquals("count", p.mode)
        assertEquals(SessionProgress.ORIGIN_TODO, p.origin)
        assertEquals(0L to 3L, p.done to p.total)
        assertEquals("item0", p.step)
        assertEquals(SessionProgress.RUNNING, p.state)
        now += 5_000
        m.setTodoProgress(id, list("done", "in_progress", "pending"))
        val q = assertNotNull(m.get(id)?.progress)
        assertEquals(1L, q.done)
        assertEquals("item1", q.step)
        assertEquals(p.startedAt, q.startedAt, "the same job keeps its clock")
        assertEquals(100.0 / 3, q.percent!!, 0.001)
        m.setTodoProgress(id, list("done", "done", "done"))
        val r = assertNotNull(m.get(id)?.progress)
        assertEquals(SessionProgress.DONE, r.state)
        assertNull(r.step)
        m.setTodoProgress(id, emptyList())
        assertNull(m.get(id)?.progress, "an empty list clears the bar it set")
    }

    @Test fun `the agent's own running report holds the bar and its next report replaces a todo bar`() {
        val id = m.startNew("go").id
        m.setTodoProgress(id, list("done", "pending"))
        m.reportProgress(id, ProgressReport("plan", steps = listOf("compile", "test"), label = "gradle"))
        val own = assertNotNull(m.get(id)?.progress)
        assertEquals("steps", own.mode)
        assertNull(own.origin)
        assertEquals(0, own.stepsDone, "a fresh job, nothing inherited from the to-do bar")
        m.setTodoProgress(id, list("done", "done"))
        assertEquals("gradle", m.get(id)?.progress?.label, "the list never overwrites a running own report")
        m.setTodoProgress(id, emptyList())
        assertEquals("gradle", m.get(id)?.progress?.label, "nor does clearing the list")
        m.reportProgress(id, ProgressReport("done"))
        m.setTodoProgress(id, list("done", "in_progress", "pending"))
        val back = assertNotNull(m.get(id)?.progress)
        assertEquals(SessionProgress.ORIGIN_TODO, back.origin, "once the own job finished, the list takes the bar back")
        assertEquals(1L to 3L, back.done to back.total)
    }

    @Test fun `the list outranks the sleep hint both ways`() {
        val id = m.startNew("go").id
        m.setWait(id, WaitHint(60, now))
        m.setTodoProgress(id, list("in_progress"))
        assertEquals(SessionProgress.ORIGIN_TODO, m.get(id)?.progress?.origin, "a list replaces a wait")
        m.setWait(id, WaitHint(60, now))
        assertEquals(SessionProgress.ORIGIN_TODO, m.get(id)?.progress?.origin, "a wait never replaces a list")
        m.clearWait(id)
        assertEquals(SessionProgress.ORIGIN_TODO, m.get(id)?.progress?.origin)
    }

    @Test fun `a list never resurrects a bar the agent did not have and clearing one with no bar changes nothing`() {
        val id = m.startNew("go").id
        m.setTodoProgress(id, emptyList())
        assertNull(m.get(id)?.progress)
        m.reportProgress(id, ProgressReport("percent", percent = 10.0, label = "dl"))
        m.reportProgress(id, ProgressReport("done"))
        m.setTodoProgress(id, emptyList())
        assertEquals("dl", m.get(id)?.progress?.label, "an empty list leaves the agent's own finished bar alone")
    }
}
