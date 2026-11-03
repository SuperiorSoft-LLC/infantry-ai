package com.superiorsoftllc.infantry

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SHARED-DOCS-1: product facts live once in `docs/shared/product.md` (front matter). A page that states one wraps it in
 * `<!-- shared:KEY -->text<!-- /shared:KEY -->`; this test fails when that text differs from the shared value, or when a
 * shared one-line fact (tagline, pitch, license line) appears in README or the wiki outside such a marker.
 */
class SharedDocsGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "AGENTS.md").isFile }

    private fun facts(): Map<String, String> {
        val front = File(root, "docs/shared/product.md").readText().split("\n---")[0].removePrefix("---")
        return Regex("""^(\w+):\s+(\S.*)$""", RegexOption.MULTILINE).findAll(front).associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    private fun pages(): List<File> =
        listOf(File(root, "README.md")) + File(root, "docs/wiki").walkTopDown().filter { it.isFile && it.extension == "md" }.toList()

    private fun norm(s: String) = s.replace(Regex("""\s+"""), " ").trim()

    @Test
    fun markedFactsMatchTheSharedSource() {
        val facts = facts()
        assertTrue(facts.containsKey("tagline") && facts.containsKey("license_line"), "docs/shared/product.md lost a fact")
        val marker = Regex("""<!-- shared:(\w+) -->(.*?)<!-- /shared:\1 -->""", RegexOption.DOT_MATCHES_ALL)
        var used = 0
        for (page in pages()) for (m in marker.findAll(page.readText())) {
            val key = m.groupValues[1]
            assertTrue(facts.containsKey(key), "${page.name}: unknown shared key '$key'")
            assertEquals(norm(facts.getValue(key)), norm(m.groupValues[2]), "${page.name}: shared:$key differs from docs/shared/product.md; edit it there")
            used++
        }
        assertTrue(used >= 4, "README, Home and licensing must read the shared facts")
    }

    @Test
    fun sharedSentencesAreNotCopiedOutsideMarkers() {
        val facts = facts()
        val marker = Regex("""<!-- shared:(\w+) -->.*?<!-- /shared:\1 -->""", RegexOption.DOT_MATCHES_ALL)
        for (page in pages()) {
            val bare = norm(marker.replace(page.readText(), ""))
            for (key in listOf("tagline", "pitch", "license_line", "license_footer")) {
                val v = norm(facts.getValue(key))
                assertTrue(!bare.contains(v), "${page.name} copies shared:$key by hand; wrap it in <!-- shared:$key --> markers")
            }
        }
    }
}
