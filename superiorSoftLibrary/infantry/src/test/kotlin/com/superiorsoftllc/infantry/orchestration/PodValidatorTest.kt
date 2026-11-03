package com.superiorsoftllc.infantry.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PodValidatorTest {

    private fun spec(vararg nodes: NodeSpec, maxConcurrency: Int = 1) =
        PodSpec(goal = "g", nodes = nodes.toList(), maxConcurrency = maxConcurrency)

    private fun rejected(spec: PodSpec): String =
        assertFailsWith<InvalidPodException> { PodValidator.validate(spec) }.message!!

    @Test
    fun `structural violations are named precisely`() {
        assertEquals("a pod needs at least one node", rejected(spec()))
        assertEquals("maxConcurrency must be >= 1, was 0", rejected(spec(NodeSpec("a", "p"), maxConcurrency = 0)))
        assertEquals("node name must not be blank", rejected(spec(NodeSpec(" ", "p"))))
        assertEquals("duplicate node name 'a'", rejected(spec(NodeSpec("a", "p"), NodeSpec("a", "q"))))
        assertEquals("node 'a' has a blank prompt", rejected(spec(NodeSpec("a", " "))))
        assertEquals("node 'a' maxAttempts must be >= 1, was 0", rejected(spec(NodeSpec("a", "p", maxAttempts = 0))))
    }

    @Test
    fun `self and unknown dependencies are refused`() {
        assertEquals("node 'a' depends on itself", rejected(spec(NodeSpec("a", "p", setOf("a")))))
        assertEquals(
            "node 'a' depends on unknown node 'ghost'",
            rejected(spec(NodeSpec("a", "p", setOf("ghost")))),
        )
    }

    @Test
    fun `a cycle names every unschedulable node, sorted, and leaves free nodes out`() {
        assertEquals(
            "dependency cycle; unschedulable nodes: a, b, c, tail",
            rejected(
                spec(
                    NodeSpec("b", "p", setOf("a")),
                    NodeSpec("a", "p", setOf("c")),
                    NodeSpec("c", "p", setOf("b")),
                    NodeSpec("tail", "p", setOf("a")),
                    NodeSpec("free", "p"),
                ),
            ),
        )
    }

    @Test
    fun `a diamond is valid and its order is deterministic by spec index`() {
        val diamond = spec(
            NodeSpec("d", "p", setOf("b", "c")),
            NodeSpec("c", "p", setOf("a")),
            NodeSpec("b", "p", setOf("a")),
            NodeSpec("a", "p"),
        )
        PodValidator.validate(diamond)
        assertEquals(listOf("a", "c", "b", "d"), PodValidator.topologicalOrder(diamond))
        assertEquals(listOf("a", "c", "b", "d"), PodValidator.topologicalOrder(diamond), "stable across calls")
    }
}
