package com.superiorsoftllc.infantry.orchestration

import java.util.PriorityQueue

/** In-memory validation of a [PodSpec], run before any write (ticket section 4.5). */
object PodValidator {

    /** Throws [InvalidPodException] with the first violation, in the order of the ticket. */
    fun validate(spec: PodSpec) {
        order(spec)
    }

    /** Node names in a deterministic topological order (Kahn, ready set by lowest spec index). Validates first. */
    fun topologicalOrder(spec: PodSpec): List<String> = order(spec)

    private fun order(spec: PodSpec): List<String> {
        if (spec.nodes.isEmpty()) throw InvalidPodException("a pod needs at least one node")
        if (spec.maxConcurrency < 1) {
            throw InvalidPodException("maxConcurrency must be >= 1, was ${spec.maxConcurrency}")
        }
        val seen = HashSet<String>()
        for (n in spec.nodes) {
            if (n.name.isBlank()) throw InvalidPodException("node name must not be blank")
            if (!seen.add(n.name)) throw InvalidPodException("duplicate node name '${n.name}'")
            if (n.prompt.isBlank()) throw InvalidPodException("node '${n.name}' has a blank prompt")
            if (n.maxAttempts < 1) {
                throw InvalidPodException("node '${n.name}' maxAttempts must be >= 1, was ${n.maxAttempts}")
            }
        }
        for (n in spec.nodes) {
            for (d in n.dependsOn.sorted()) {
                if (d == n.name) throw InvalidPodException("node '${n.name}' depends on itself")
                if (d !in seen) throw InvalidPodException("node '${n.name}' depends on unknown node '$d'")
            }
        }
        val indexOf = spec.nodes.withIndex().associate { it.value.name to it.index }
        val remaining = spec.nodes.associate { it.name to it.dependsOn.size }.toMutableMap()
        val dependents = HashMap<String, MutableList<String>>()
        for (n in spec.nodes) for (d in n.dependsOn) dependents.getOrPut(d) { mutableListOf() }.add(n.name)
        val ready = PriorityQueue<String>(compareBy { indexOf.getValue(it) })
        spec.nodes.filter { remaining.getValue(it.name) == 0 }.forEach { ready.add(it.name) }
        val out = ArrayList<String>()
        while (ready.isNotEmpty()) {
            val n = ready.poll()
            out += n
            for (m in dependents[n].orEmpty()) {
                val left = remaining.getValue(m) - 1
                remaining[m] = left
                if (left == 0) ready.add(m)
            }
        }
        if (out.size != spec.nodes.size) {
            val emitted = out.toSet()
            val stuck = spec.nodes.map { it.name }.filter { it !in emitted }.sorted()
            throw InvalidPodException("dependency cycle; unschedulable nodes: ${stuck.joinToString(", ")}")
        }
        return out
    }
}
