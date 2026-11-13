// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * RUNNER-UNIFY-GUARD-1 (docs/RUNNER-UNIFY.md): a ratchet on local-vs-remote divergence. Callers should reach a session
 * through `RunnerPool.linkFor(id)` and never branch on where it runs. Today's counts, per kind and file, live in
 * `runner-unify-baseline.txt` next to this class (test resources). A count that goes UP fails (new divergence); a count
 * that goes DOWN also fails until the baseline is lowered in the same commit, so the ratchet only tightens. When every
 * count is 0 this becomes the strict guard.
 */
class RunnerUnifyRatchetGuardTest {
    private val roots = listOf(
        "src/main/kotlin", "../infantryCore/src/main/kotlin", "../infantryDaemonApi/src/main/kotlin",
        "../infantryBoard/src/main/kotlin", "../infantryRunner/src/main/kotlin",
    ).map(::File)
    private val prefix = "superiorSoftLibrary/"

    /** Kind -> pattern. A line counts once per match. */
    private val kinds = linkedMapOf(
        "runnerOf" to Regex("""\brunnerOf\("""),
        "localCompare" to Regex("""[!=]=\s*(RunnerPool\.LOCAL|"local")\b|(RunnerPool\.LOCAL|"local")\s*[!=]="""),
        "remoteLookup" to Regex("""\bremoteMirror\.|\bremoteSession\("""),
        "listMerge" to Regex("""\.list\(\)\s*\+\s*remoteMirror"""),
        "managerCall" to Regex("""\b(manager|sessionManager)\.(kill|resume|interrupt|delete|startNew)\("""),
    )

    /** The runner itself may call SessionManager directly and define the placement helpers. */
    private val runnerFiles = listOf("daemon/LocalRunner.kt", "api/orchestrator/RunnerPool.kt")

    private fun counts(): Map<String, Int> {
        val out = sortedMapOf<String, Int>()
        for (root in roots) {
            check(root.isDirectory) { "missing source root ${root.path} (run from the infantry module)" }
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val path = prefix + f.canonicalFile.invariantSeparatorsPath.substringAfter(prefix)
                if (runnerFiles.any { path.endsWith(it) }) return@forEach
                f.readLines().forEach { raw ->
                    val t = raw.trim()
                    if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return@forEach
                    for ((kind, re) in kinds) {
                        val n = re.findAll(t).count()
                        if (n > 0) out.merge("$kind $path", n, Int::plus)
                    }
                }
            }
        }
        return out
    }

    private fun baseline(): Map<String, Int> {
        val text = javaClass.getResource("runner-unify-baseline.txt")?.readText() ?: fail("runner-unify-baseline.txt missing")
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.associate { line ->
            val parts = line.split(Regex("""\s+"""))
            "${parts[0]} ${parts[1]}" to parts[2].toInt()
        }
    }

    @Test fun `no new local-vs-remote branches or direct SessionManager calls outside the runner`() {
        val now = counts()
        val base = baseline()
        val up = now.filter { (k, n) -> n > (base[k] ?: 0) }
        val down = base.filter { (k, n) -> (now[k] ?: 0) < n }
        if (up.isEmpty() && down.isEmpty()) return
        val sb = StringBuilder()
        if (up.isNotEmpty()) {
            sb.appendLine("NEW runner divergence (count went UP). Do not branch on where a session runs and do not call")
            sb.appendLine("SessionManager directly: go through RunnerPool.linkFor(id) (the RunnerLink does kill/resume/interrupt/...):")
            up.forEach { (k, n) -> sb.appendLine("  $k: ${base[k] ?: 0} -> $n") }
        }
        if (down.isNotEmpty()) {
            sb.appendLine("Divergence went DOWN, well done: lower the baseline in runner-unify-baseline.txt in this same commit (ratchet):")
            down.forEach { (k, n) -> sb.appendLine("  $k: $n -> ${now[k] ?: 0}") }
        }
        sb.appendLine("Current counts (paste as the baseline only when lowering):")
        now.forEach { (k, n) -> sb.appendLine("$k $n") }
        fail(sb.toString())
    }
}
