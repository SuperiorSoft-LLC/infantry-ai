package com.superiorsoftllc.agenthq.runner

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * STACK-NEUTRAL-1: core main code names no build stack in behaviour code. What a stack is (its commands, daemons, log
 * patterns, env) lives in the tool profiles (`watch/tool-profiles.json`, settings, plugins); comments and docs may name one.
 * [PENDING] is the list of files a follow-up still has to clear (docs/STACK-NEUTRAL.md, section 4): it may only shrink.
 */
class StackNeutralGuardTest {
    private val roots = listOf(
        File("src/main/kotlin"),
        File("../agentHqProtocol/src/main/kotlin"),
        File("../agentHqRunner/src/main/kotlin"),
        File("../agentHqDaemonApi/src/main/kotlin"),
        File("../agentHqBoard/src/main/kotlin"),
    )

    private val stack = Regex("""(?i)\bgradle|kotlincompile|kotlin-daemon|\bmvnw?\b|\bmaven\b|\bcargo\b|\bpytest\b|\bdotnet\b""")
    private val trailingComment = Regex("""\s//\s.*$""")

    /** File (path suffix) -> lines it may still have, and why. */
    private val PENDING = mapOf(
        "daemon/WebBundle.kt" to 1, // a dev-only hint naming Infantry AI's own build (this repo's build files: allowed)
    )

    private fun hits(): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (root in roots) {
            assertTrue(root.isDirectory, "missing source root ${root.path} (run from the agentHq module)")
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                f.readLines().forEachIndexed { i, raw ->
                    val t = raw.trim()
                    if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return@forEachIndexed
                    if (stack.containsMatchIn(trailingComment.replace(t, ""))) {
                        out.getOrPut(f.invariantSeparatorsPath) { mutableListOf() } += "${i + 1}: ${t.take(120)}"
                    }
                }
            }
        }
        return out
    }

    @Test fun `core behaviour code names no build stack outside the pending follow-ups`() {
        val found = hits()
        val unexpected = found.filterKeys { path -> PENDING.keys.none { path.endsWith(it) } }
        if (unexpected.isNotEmpty()) {
            fail(
                "core code names a build stack; move it to a tool profile (watch/tool-profiles.json) or a plugin:\n" +
                    unexpected.entries.joinToString("\n") { (f, l) -> "$f\n  " + l.joinToString("\n  ") },
            )
        }
        for ((suffix, max) in PENDING) {
            val n = found.entries.firstOrNull { it.key.endsWith(suffix) }?.value?.size ?: 0
            assertTrue(n <= max, "$suffix grew to $n stack-naming lines (allowed $max): ${found.entries.firstOrNull { it.key.endsWith(suffix) }?.value}")
        }
    }

    @Test fun `the guard sees a stack name in code but not in a comment`() {
        assertTrue(stack.containsMatchIn("""val x = Regex("GradleDaemon")"""))
        assertTrue(stack.containsMatchIn("""put("GRADLE_OPTS", v)"""))
        assertEquals(false, stack.containsMatchIn(trailingComment.replace("""val x = 1 // a gradle note""", "")))
        assertEquals(false, stack.containsMatchIn("""import kotlinx.serialization.Serializable"""))
    }

    @Test fun `the bundled profiles carry what core used to hard-code`() {
        val d = ToolProfiles.DEFAULTS
        d.forEach(ToolProfiles::validate)
        assertTrue(ToolProfiles.sharedDaemonRegex(d).containsMatchIn("java org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.5"))
        assertTrue(ToolProfiles.sharedDaemonRegex(d).containsMatchIn("java KotlinCompileDaemon"))
        assertTrue(ToolProfiles.clientRegex(d).containsMatchIn("cmd /c gradlew test"))
        assertTrue(ToolProfiles.heavyImageRegex(d).matches("java.exe"))
        assertTrue(ToolProfiles.heavyImageRegex(d).matches("gradle-wrapper"))
        assertEquals(false, ToolProfiles.heavyImageRegex(d).matches("notepad.exe"))
        assertTrue(ToolProfiles.heavyCommandRegex(d).containsMatchIn("npx @playwright/mcp"))
        assertEquals("gradle", ToolProfiles.daemonProfile("java GradleDaemon", d))
        assertEquals("kotlin", ToolProfiles.daemonProfile("java KotlinCompileDaemon", d))
        for (name in listOf("gradle", "maven", "npm", "pnpm", "yarn", "cargo", "go", "pytest", "make", "dotnet")) {
            assertTrue(d.any { it.name == name }, "bundled profile '$name'")
        }
    }

    @Test fun `with no profiles nothing is a daemon, a client or heavy`() {
        val none = emptyList<ToolProfile>()
        assertEquals(false, ToolProfiles.daemonRegex(none).containsMatchIn("java GradleDaemon"))
        assertEquals(false, ToolProfiles.clientRegex(none).containsMatchIn("gradlew test"))
        assertEquals(false, ToolProfiles.heavyImageRegex(none).matches("java"))
        assertEquals(false, ToolProfiles.heavyCommandRegex(none).containsMatchIn("java -jar x"))
        assertTrue(ToolProfiles.sessionEnv(6, 2, list = none).isEmpty())
    }

    @Test fun `a bad env template is refused`() {
        val bad = ToolProfile(name = "x", env = listOf(EnvTemplate("1BAD", "v")))
        assertTrue(runCatching { ToolProfiles.validate(bad) }.isFailure)
        val badIf = ToolProfile(name = "x", env = listOf(EnvTemplate("OK", "v", onlyIf = "cores")))
        assertTrue(runCatching { ToolProfiles.validate(badIf) }.isFailure)
    }
}
