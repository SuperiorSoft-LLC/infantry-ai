// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.persistence

import com.superiorsoftllc.infantry.session.SessionState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 5 storage: nodes, edges and attempts behind the repository seam. Real SQLite, real reopen. */
class NodeRepositoryTest {

    private fun newDatabasePath(): String {
        val file = Files.createTempFile("infantry-nodes-test-", ".db")
        file.toFile().delete()
        return file.toAbsolutePath().toString()
    }

    private fun pod(id: String = "pod-1") =
        PodRecord(id = id, goal = "goal", state = "RUNNING", maxConcurrency = 2, createdAt = 1L)

    private fun node(id: String, name: String = id, index: Int = 0, podId: String = "pod-1") = NodeRecord(
        id = id, podId = podId, name = name, specIndex = index, prompt = "do $name",
        state = "PENDING", maxAttempts = 2, createdAt = 2L,
    )

    private fun run(id: String, podId: String = "pod-1") = RunRecord(
        id = id, sessionId = "sess-$id", provider = "stub", workingDir = "w", prompt = "p",
        state = SessionState.STARTING, startedAt = 3L, podId = podId,
    )

    @Test
    fun `nodes, edges and attempts round-trip across a reopen`() {
        val path = newDatabasePath()
        SqlInfantryStore.open(path).use { s ->
            s.transaction {
                s.pods.create(pod())
                s.nodes.create(node("n-a", "a", 0))
                s.nodes.create(node("n-b", "b", 1))
                s.nodes.create(node("n-c", "c", 2))
                s.nodes.addDependency(NodeDependency("n-c", "n-a"))
                s.nodes.addDependency(NodeDependency("n-c", "n-b"))
            }
            s.runs.upsert(run("r-1"))
            s.nodes.recordAttempt(NodeAttempt(runId = "r-1", nodeId = "n-a", attempt = 1))
        }
        SqlInfantryStore.open(path).use { s ->
            assertEquals(listOf("a", "b", "c"), s.nodes.byPod("pod-1").map { it.name })
            assertEquals(node("n-b", "b", 1), s.nodes.get("n-b"))
            assertEquals(
                setOf(NodeDependency("n-c", "n-a"), NodeDependency("n-c", "n-b")),
                s.nodes.dependencies("pod-1").toSet(),
            )
            assertEquals(listOf(NodeAttempt("r-1", "n-a", 1)), s.nodes.attempts("n-a"))
            assertTrue(s.nodes.attempts("n-b").isEmpty())
        }
    }

    @Test
    fun `update rewrites only the mutable columns`() {
        SqlInfantryStore.open(newDatabasePath()).use { s ->
            s.pods.create(pod())
            s.nodes.create(node("n-a", "a", 0))
            val expected = node("n-a", "a", 0).copy(
                state = "SUCCEEDED", attempts = 2, reviewState = "PENDING", detail = "ok", endedAt = 9L,
            )
            // Identity, prompt and budget changes in the argument must be IGNORED by update().
            s.nodes.update(expected.copy(name = "renamed", prompt = "rewritten", maxAttempts = 5, specIndex = 7))
            assertEquals(expected, s.nodes.get("n-a"))
        }
    }

    @Test
    fun `the schema refuses a self edge, a dangling edge, a duplicate name and a duplicate attempt number`() {
        SqlInfantryStore.open(newDatabasePath()).use { s ->
            s.pods.create(pod())
            s.nodes.create(node("n-a", "a", 0))
            s.nodes.create(node("n-b", "b", 1))
            assertFails("self edge") { s.nodes.addDependency(NodeDependency("n-a", "n-a")) }
            assertFails("dangling edge (FK)") { s.nodes.addDependency(NodeDependency("n-a", "missing")) }
            assertFails("duplicate name in one pod") { s.nodes.create(node("n-dup", "a", 2)) }
            s.runs.upsert(run("r-1"))
            s.runs.upsert(run("r-2"))
            s.nodes.recordAttempt(NodeAttempt("r-1", "n-a", 1))
            assertFails("duplicate attempt number") { s.nodes.recordAttempt(NodeAttempt("r-2", "n-a", 1)) }
        }
    }

    @Test
    fun `a node record refuses impossible counts`() {
        assertFailsWith<IllegalArgumentException> { node("x").copy(maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { node("x").copy(attempts = -1) }
    }

    @Test
    fun `deleting a pod cascades through nodes, edges, attempts, runs and events`() {
        SqlInfantryStore.open(newDatabasePath()).use { s ->
            s.pods.create(pod())
            s.nodes.create(node("n-a", "a", 0))
            s.nodes.create(node("n-b", "b", 1))
            s.nodes.addDependency(NodeDependency("n-b", "n-a"))
            s.runs.upsert(run("r-1"))
            s.nodes.recordAttempt(NodeAttempt("r-1", "n-a", 1))
            s.events.append("r-1", RunEvent.TextDelta("hi"), 4L)

            s.pods.delete("pod-1")

            assertTrue(s.nodes.byPod("pod-1").isEmpty())
            assertNull(s.nodes.get("n-a"))
            assertTrue(s.nodes.dependencies("pod-1").isEmpty())
            assertTrue(s.nodes.attempts("n-a").isEmpty())
            assertNull(s.runs.get("r-1"))
            assertEquals(0L, s.events.count("r-1"))
        }
    }

    @Test
    fun `deleting a run drops its attempt row and keeps the node`() {
        SqlInfantryStore.open(newDatabasePath()).use { s ->
            s.pods.create(pod())
            s.nodes.create(node("n-a", "a", 0))
            s.runs.upsert(run("r-1"))
            s.nodes.recordAttempt(NodeAttempt("r-1", "n-a", 1))
            s.runs.delete("r-1")
            assertTrue(s.nodes.attempts("n-a").isEmpty())
            assertNotNull(s.nodes.get("n-a"))
        }
    }

    @Test
    fun `the review queue holds only review-PENDING nodes, oldest first`() {
        SqlInfantryStore.open(newDatabasePath()).use { s ->
            s.pods.create(pod())
            s.nodes.create(node("n-a", "a", 0).copy(state = "SUCCEEDED", reviewState = "PENDING", endedAt = 5L))
            s.nodes.create(node("n-b", "b", 1).copy(state = "SUCCEEDED", reviewState = "PENDING", endedAt = 3L))
            s.nodes.create(node("n-c", "c", 2).copy(state = "SUCCEEDED", reviewState = "APPROVED", endedAt = 1L))
            s.nodes.create(node("n-d", "d", 3))
            assertEquals(listOf("b", "a"), s.nodes.reviewQueue().map { it.name })
        }
    }
}
