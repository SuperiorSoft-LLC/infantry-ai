package com.superiorsoftllc.agenthq

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * DOCS-4: the docs cannot name what is not there. Every repository path and every public type that `AGENTS.md`,
 * `llms.txt` and every page under `docs/wiki` name in inline code (or as a relative link target) must exist, so a rename or a delete
 * fails here with the doc line that still names the old thing.
 *
 * Rules (also in AGENTS.md section 6):
 * - Fenced code blocks are skipped: they hold examples the reader is meant to create or run.
 * - A path counts when it has a `/` and either a known file extension or a trailing `/`. Paths with `<`, `*`, `{`, `...`,
 *   a leading `~`, `/` or `http` are not repository paths and are skipped.
 * - A type counts when it is a CamelCase token in inline code (`HarnessPlugin`); it must be declared somewhere in the
 *   repository's Kotlin or Java sources (class, interface, object, typealias, fun or val). [externalTypes] lists the few
 *   library types the docs mention.
 * - A line that carries the marker `docs-freshness: ignore` in an HTML comment is skipped.
 */
class DocsFreshnessGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "AGENTS.md").isFile }

    private val skipDirs = setOf("build", ".git", ".gradle", ".worktrees", "node_modules", ".idea", "out")
    private val extensions = setOf("kt", "kts", "java", "md", "json", "ps1", "sh", "txt", "yml", "yaml", "toml", "lua", "svg", "properties", "sqm")

    /** Library and platform types the docs mention by name; they are not declared in this repository. */
    private val externalTypes = setOf(
        "Composable", "CoroutineScope", "StateFlow", "MutableStateFlow", "JsonObject", "JsonElement", "JsonPrimitive",
        "ServiceLoader", "ClassLoader", "WebSocket", "LuaJ", "JediTerm", "AutoCloseable", "IllegalStateException",
        "CamelCase", "GitHub", "JavaScript", "TypeScript", "OpenCode", "MkDocs", "PowerShell", "JetBrains", "SQLite",
        "Kotlin", "Gradle", "Android", "Compose", "WebAssembly", "InfantryAI", "AgentHQ", "SecurityException", "ColumnScope",
    )

    /** Every file and folder of the repository, as slash paths relative to the root. */
    private val allPaths: List<String> by lazy {
        root.walkTopDown().onEnter { it.name !in skipDirs }.map { it.relativeTo(root).path.replace('\\', '/') }.filter { it.isNotEmpty() }.toList()
    }

    /**
     * A path exists when it is under the root, or when it is a shorter path the doc gives relative to a folder it has
     * just named (it then matches the tail of a real path). A module under `plugins/community` that is not in the
     * repository is an example module the doc tells the reader to create; the folder is a drop-in place, not a source.
     */
    private fun pathExists(token: String): Boolean {
        val t = token.trimEnd('/')
        if (File(root, t).exists()) return true
        if (allPaths.any { it.endsWith("/" + t) }) return true
        val parts = t.split('/')
        return parts.size >= 3 && parts[0] == "plugins" && parts[1] == "community" && !File(root, "plugins/community/" + parts[2]).exists()
    }

    private val docs: List<File> by lazy {
        val wiki = File(root, "docs/wiki").walkTopDown().filter { it.isFile && it.extension == "md" }.toList()
        listOf(File(root, "AGENTS.md"), File(root, "llms.txt")).filter { it.isFile } + wiki
    }

    private val declared: Set<String> by lazy {
        val decl = Regex("""\b(?:class|interface|object|typealias|fun|val|var)\s+(?:<[^>]+>\s*)?(?:[A-Za-z0-9_.<>?]+\.)?([A-Z][A-Za-z0-9_]*)""")
        val names = HashSet<String>()
        root.walkTopDown().onEnter { it.name !in skipDirs }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { f -> decl.findAll(f.readText()).forEach { names += it.groupValues[1] } }
        names
    }

    /** Inline code tokens and relative link targets of one line, outside fences (the caller tracks fences). */
    private fun inlineCode(line: String): List<String> = Regex("`([^`]+)`").findAll(line).map { it.groupValues[1].trim() }.toList()
    private fun linkTargets(line: String): List<String> = Regex("""\]\(([^)\s]+)\)""").findAll(line).map { it.groupValues[1] }.toList()

    private fun isRepoPath(token: String): Boolean {
        if (token.isEmpty() || token.any { it.isWhitespace() }) return false
        if (token.startsWith("~") || token.startsWith(".") || token.startsWith("/") || token.startsWith("http") || token.startsWith("-")) return false
        if (listOf("<", ">", "*", "{", "}", "...", "$", "(", ")", "=", ",", ":", "|").any { it in token }) return false
        if (!token.contains('/')) return false
        if (!Regex("""[A-Za-z0-9_./\-]+""").matches(token)) return false
        return token.endsWith("/") || token.substringAfterLast('.', "") in extensions
    }

    private fun isTypeName(token: String): Boolean =
        Regex("""[A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+""").matches(token) && token.length >= 6 && token !in externalTypes

    @Test
    fun `every file path and public type named in the docs exists`() {
        val problems = mutableListOf<String>()
        for (doc in docs) {
            val rel = doc.relativeTo(root).path.replace('\\', '/')
            var fenced = false
            doc.readLines().forEachIndexed { index, line ->
                if (line.trimStart().startsWith("```")) { fenced = !fenced; return@forEachIndexed }
                if (fenced || "docs-freshness: ignore" in line) return@forEachIndexed
                for (raw in inlineCode(line)) {
                    val token = raw.removePrefix("./")
                    val bare = token.removeSuffix("()")
                    if (isRepoPath(token)) {
                        if (!pathExists(token)) problems += "$rel:${index + 1}: path `$token` does not exist  | ${line.trim().take(140)}"
                    } else if (isTypeName(bare) && bare !in declared) {
                        problems += "$rel:${index + 1}: type `$bare` is not declared in any .kt or .java file  | ${line.trim().take(140)}"
                    }
                }
                for (link in linkTargets(line)) {
                    if (link.startsWith("http") || link.startsWith("#") || link.startsWith("mailto:")) continue
                    val target = File(doc.parentFile, link.substringBefore('#'))
                    if (!target.exists()) problems += "$rel:${index + 1}: link target `$link` does not exist  | ${line.trim().take(140)}"
                }
            }
        }
        assertTrue(problems.isEmpty(), "docs name things that are not in the repository (${problems.size}):\n" + problems.joinToString("\n"))
    }

    @Test
    fun `the docs this guard reads are present`() {
        assertTrue(File(root, "AGENTS.md").isFile, "AGENTS.md is missing")
        assertTrue(File(root, "llms.txt").isFile, "llms.txt is missing")
        assertTrue(docs.size >= 12, "expected AGENTS.md, llms.txt and the wiki pages, found ${docs.size} files")
    }
}
