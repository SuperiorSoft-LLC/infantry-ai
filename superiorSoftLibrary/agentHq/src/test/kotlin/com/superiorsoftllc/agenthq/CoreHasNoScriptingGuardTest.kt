package com.superiorsoftllc.agenthq

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** SCRIPT-2 / D20: core is generic; the script contract and the Lua engine live in plugins, so core names neither. */
class CoreHasNoScriptingGuardTest {
    private val banned = Regex("""luaj|LuaScript|ScriptEngine|ScriptLimits|ScriptResult|script\.maxSteps|scriptLimits""", RegexOption.IGNORE_CASE)

    private fun module(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "src/main/kotlin").isDirectory && File(it, "build.gradle.kts").isFile && it.name == "agentHq" }

    @Test fun `core build file has no luaj dependency`() {
        assertTrue(!File(module(), "build.gradle.kts").readText().contains("luaj", ignoreCase = true))
    }

    @Test fun `core main sources have no script engine or lua identifiers`() {
        val hits = File(module(), "src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { banned.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertTrue(hits.isEmpty(), "core must not name the script contract or an engine: $hits")
    }
}
