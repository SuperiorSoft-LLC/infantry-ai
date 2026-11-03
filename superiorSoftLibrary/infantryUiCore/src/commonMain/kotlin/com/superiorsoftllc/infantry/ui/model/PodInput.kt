// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.NodeSpec

/**
 * Human-typed pod nodes, one per line, no JSON:
 *
 *     name: prompt
 *     name after a,b: prompt          (depends on nodes a and b)
 *     name x3 after a: prompt         (up to 3 attempts)
 *
 * Returns the nodes or a message naming the offending line. Full validation (cycles, unknown
 * dependencies) stays the daemon's job (`PodValidator`); this only turns text into [NodeSpec].
 */
fun parseNodes(text: String): Result<List<NodeSpec>> {
    val nodes = mutableListOf<NodeSpec>()
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEachIndexed { i, line ->
        val colon = line.indexOf(':')
        if (colon <= 0 || line.substring(colon + 1).isBlank()) {
            return Result.failure(IllegalArgumentException("line ${i + 1}: expected 'name: prompt'"))
        }
        val prompt = line.substring(colon + 1).trim()
        val tokens = line.substring(0, colon).trim().split(Regex("""\s+"""))
        val head = tokens.first()
        val deps = mutableSetOf<String>()
        var attempts = 1
        var inDeps = false
        for (t in tokens.drop(1)) {
            when {
                t == "after" -> inDeps = true
                Regex("""x\d+""").matches(t) -> { attempts = t.drop(1).toInt(); inDeps = false }
                inDeps -> deps += t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                else -> return Result.failure(IllegalArgumentException("line ${i + 1}: node name must be one word, got '${tokens.joinToString(" ")}'"))
            }
        }
        if (attempts < 1) return Result.failure(IllegalArgumentException("line ${i + 1}: attempts must be >= 1"))
        nodes += NodeSpec(head, prompt, deps, attempts)
    }
    return if (nodes.isEmpty()) Result.failure(IllegalArgumentException("add at least one node")) else Result.success(nodes)
}
