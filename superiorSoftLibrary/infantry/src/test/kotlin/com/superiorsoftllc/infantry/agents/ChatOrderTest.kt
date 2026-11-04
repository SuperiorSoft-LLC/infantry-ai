// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CHAT-ORDER-1: an event raised under the lock (the owner's Turn) is delivered before one another thread raises meanwhile (the reply). */
class ChatOrderTest {
    private object P : AgentProvider {
        override val id = "p"
        override fun spec(request: RunRequest): ProcessSpec = error("no run")
        override fun defaultModel() = null
    }
    private object R : ProcessRunner {
        override val id = "r"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = error("no run")
    }

    @Test
    fun turnRaisedUnderLockIsDeliveredBeforeReplyRaisedByAnotherThread() {
        val dir = Files.createTempDirectory("chat-order").toFile()
        try {
            val m = SessionManager(P, R, JsonSessionStore(File(dir, "s.json")))
            val seen = CopyOnWriteArrayList<String>()
            m.eventSink = { e -> seen += when (e) { is SessionEvent.Turn -> "turn"; is SessionEvent.Stdout -> "reply"; else -> "other" } }
            val cls = SessionManager::class.java
            val emit = cls.getDeclaredMethod("emit", SessionEvent::class.java).apply { isAccessible = true }
            val flush = cls.getDeclaredMethod("flushEvents").apply { isAccessible = true }
            val turnRaised = CountDownLatch(1)
            val replyDone = CountDownLatch(1)
            val t = thread {
                synchronized(m) {
                    emit.invoke(m, SessionEvent.Turn("s", "hi"))
                    turnRaised.countDown()
                    replyDone.await()
                }
                flush.invoke(m)
            }
            turnRaised.await()
            emit.invoke(m, SessionEvent.Stdout("s", "reply"))
            replyDone.countDown()
            t.join()
            assertEquals(listOf("turn", "reply"), seen.toList())
            assertTrue(seen.size == 2)
        } finally { dir.deleteRecursively() }
    }
}
