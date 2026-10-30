package com.superiorsoftllc.agenthq.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OWNER RULE 2026-10-25: no hard-coded limits. Fails when a limit-like numeric constant appears in src/main that is not
 * on `limits-allowlist.txt` (route it through [LimitRegistry] instead), and when an allowlist entry no longer exists
 * (delete it: the list only shrinks). Also checks every registry entry is documented and listed by the settings API.
 */
class LimitsGuardTest {
    /** No test here may hang a fork (a spinning test burned a core for 25 min once). */
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    // CORE-SPLIT-1/2: protocol and runner live in leaf modules; the rule still covers them (relative paths are per module root).
    private val roots = listOf(File("src/main/kotlin"), File("../agentHqProtocol/src/main/kotlin"), File("../agentHqRunner/src/main/kotlin"))
    private val constant = Regex("""^\s*(?:(?:private|internal|public) )?(?:const )?val ([A-Z][A-Z0-9_]*)\s*(?::\s*[A-Za-z]+)?\s*=\s*[0-9]""")
    private val limitLike = Regex("[A-Z0-9_]*(MAX|LIMIT|CAP|TIMEOUT|DEADLINE|GRACE|FLOOR|THRESHOLD|STALL|QUIET|TTL|RETENTION|BUDGET|WINDOW|INTERVAL|DEFAULT_|_MS$|_MIN$|_MB$|_SEC$|_MILLIS$|KEEP|FOLD|CAPACITY|LINES|BYTES|CHARS|WIDTH)[A-Z0-9_]*")

    private fun found(): Set<String> = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { root to it }.toList() }.flatMap { (root, file) ->
        val rel = file.relativeTo(root).invariantSeparatorsPath
        file.readLines().mapNotNull { line ->
            constant.find(line)?.groupValues?.get(1)?.takeIf { limitLike.matches(it) }?.let { "$rel:$it" }
        }
    }.toSet()

    private fun allowed(): Map<String, String> = File("src/test/resources/limits-allowlist.txt").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { line -> line.substringBefore('|').trim() to line.substringAfter('|', "").trim() }

    @Test
    fun noNewLimitLikeConstantWithoutTheSettingsModel() {
        val allow = allowed()
        val new = found() - allow.keys
        assertTrue(
            new.isEmpty(),
            "New limit-like constant(s) ${new.sorted()}: make each one a LimitRegistry entry (a documented, overridable " +
                "setting) instead of a hard-coded value. Only a protocol/layout constant may go on limits-allowlist.txt, with a reason.",
        )
    }

    @Test
    fun theAllowlistOnlyShrinks_everyEntryHasAReason() {
        val allow = allowed()
        val stale = allow.keys - found()
        assertTrue(stale.isEmpty(), "Allowlist entries that no longer exist in the sources (delete them): ${stale.sorted()}")
        assertTrue(allow.filterValues { it.isEmpty() }.isEmpty(), "every allowlist entry needs a reason after '|'")
    }

    @Test
    fun everyRegistryEntryIsDocumentedUniqueAndListedBySettings() {
        val keys = LimitRegistry.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys.size, LimitRegistry.ALL.map { it.env }.toSet().size)
        for (def in LimitRegistry.ALL) {
            assertTrue(def.key.startsWith(LimitDef.PREFIX), def.key)
            assertTrue(def.description.length > 20, "${def.key} needs a description")
            assertTrue(def.accepts(def.default), "${def.key} default is outside its own range")
        }
    }
}
