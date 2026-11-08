// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * WASM-GUARD-1: `src/commonMain` code under superiorSoftLibrary/ and plugins/ compiles for wasm, so it uses no JVM-only
 * API. A JVM-only call there passes every JVM test and breaks `:infantryUiCore:compileKotlinWasmJs` hours later.
 * A line is allowed only with a trailing `// jvm-ok: <reason>` comment.
 */
class WasmCommonMainGuardTest {
    private val roots = listOf(File(".."), File("../../plugins"))

    private val rules = listOf(
        Regex("""(?<![.\w])System\.""") to "kotlin.time TimeSource / Clock.System, println, or expect/actual",
        Regex("""\bsynchronized\s*\(""") to "a copy-on-write map/list, or expect/actual",
        Regex("""^\s*import\s+java\.|\bjava\.[a-z]+\.""") to "a kotlin.* / kotlinx.* type, or expect/actual",
        Regex("""\.stackTrace\b""") to "stackTraceToString()",
        Regex("""\bStackTraceElement\b""") to "stackTraceToString()",
        Regex("""\bThread\.""") to "kotlinx.coroutines, or expect/actual",
        Regex("""@Synchronized\b""") to "a copy-on-write structure, or expect/actual",
        Regex("""^\s*import\s+javax\.|\bjavax\.[a-z]+\.""") to "a kotlin.* / kotlinx.* type, or expect/actual",
    )

    private fun commonMainFiles(): Sequence<File> = roots.asSequence().filter { it.isDirectory }.flatMap { r ->
        r.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && it.name != "node_modules" && it.name != ".worktrees" }
            .filter { it.isFile && it.extension == "kt" && "/src/commonMain/" in it.invariantSeparatorsPath }
    }.distinctBy { it.canonicalPath }

    @Test fun `commonMain code uses no JVM-only API`() {
        assertTrue(File("../infantryUiCore/src/commonMain").isDirectory, "run from the infantry module")
        val bad = mutableListOf<String>()
        for (f in commonMainFiles()) {
            f.readLines().forEachIndexed { i, raw ->
                val t = raw.trim()
                if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return@forEachIndexed
                if ("// jvm-ok:" in raw) return@forEachIndexed
                val code = raw.replace(Regex("""\s//\s.*$"""), "").replace(Regex("\"([^\"\\\\]|\\\\.)*\""), "\"\"")
                for ((re, fix) in rules) if (re.containsMatchIn(code)) {
                    bad += "${f.invariantSeparatorsPath}:${i + 1}: ${t.take(100)}\n    use $fix"
                    break
                }
            }
        }
        if (bad.isNotEmpty()) fail("JVM-only API in commonMain (wasm would not compile):\n" + bad.joinToString("\n"))
    }
}
