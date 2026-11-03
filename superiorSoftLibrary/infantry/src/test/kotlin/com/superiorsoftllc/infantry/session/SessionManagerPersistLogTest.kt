// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The write side of a store that refuses writes, pinned at the caller: a refusal that lasts for the life of the
 * process is reported once, not once per write, and its recovery is reported once. The assertion counts the lines
 * the manager actually prints, so it checks the runtime signal rather than reading the code that prints it.
 */
class SessionManagerPersistLogTest {

    /** A store that refuses every write while [refusing] holds, as a store that could not be read at startup does. */
    private class RefusingStore : SessionStore {
        @Volatile var refusing = true
        val attempts = AtomicInteger(0)

        override fun load(): List<SessionRecord> = emptyList()

        override fun saveAll(sessions: Collection<SessionRecord>) {
            attempts.incrementAndGet()
            if (refusing) {
                throw SessionStoreUnreadableException(
                    "refusing to overwrite session store: it could not be read when it was loaded",
                )
            }
        }
    }

    private class PersistProvider : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) =
            ProcessSpec(listOf("fake", request.resumeSessionId ?: "FRESH"))

        override fun sessionIdFromLine(line: String): String? =
            if (line.contains("session_id")) "prov-123" else null
    }

    private class PersistProcess(private val listener: ProcessListener) : RunningProcess {
        @Volatile private var alive = true
        override val pid = 4242L
        override val isAlive: Boolean get() = alive
        override fun writeInput(text: String) = Unit
        override fun closeInput() = Unit
        override fun killTree() {
            alive = false
            listener.onExit(1)
        }
        override fun awaitExit(): Int = 0
    }

    private class PersistRunner : ProcessRunner {
        override val id = "fake"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            val process = PersistProcess(listener)
            listener.onStdout("{\"session_id\":\"prov-123\"}")
            return process
        }
    }

    @Test
    fun aRefusalThatLastsIsReportedOnceAndItsRecoveryOnce() {
        val store = RefusingStore()
        val manager = SessionManager(PersistProvider(), PersistRunner(), store, killGraceMs = 50)

        val refused = captureStderr { repeat(WRITES) { manager.startNew("write $it") } }

        assertTrue(store.attempts.get() >= WRITES, "vacuous: only ${store.attempts.get()} writes were attempted")
        assertEquals(
            1,
            refused.count { it.contains("session persist failed") },
            "a refusal that lasts for the life of the process must be reported once, not once per write",
        )

        store.refusing = false
        val recovered = captureStderr { manager.startNew("after the store accepts writes again") }

        assertEquals(
            1,
            recovered.count { it.contains("session persist recovered") },
            "the recovery of a refused store must be reported once",
        )
    }

    /** Runs [action] and returns the non-blank lines it printed to stderr. */
    private fun captureStderr(action: () -> Unit): List<String> {
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
        try {
            action()
        } finally {
            System.setErr(original)
        }
        return captured.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
    }

    private companion object {
        const val WRITES = 5
    }
}
