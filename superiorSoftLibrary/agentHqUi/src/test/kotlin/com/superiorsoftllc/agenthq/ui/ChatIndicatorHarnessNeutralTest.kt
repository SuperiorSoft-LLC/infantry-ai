package com.superiorsoftllc.agenthq.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 1c24fdc4: the chat's working indicator is one universal layer: its code names no harness. It reads the session
 * state and the daemon's harness-neutral run activity only, so a new CLI needs no UI change.
 */
class ChatIndicatorHarnessNeutralTest {
    private val core = File("../agenthqUiCore/src/commonMain/kotlin/com/superiorsoftllc/agenthq/ui")
    // S4d: the chat tree (ChatSurface.kt) is the official Infantry_Chat's (RUNTIME-22); a file is read from there.
    private val app = File("../../plugins/official/Infantry_Chat/src")

    /** The text of [file] from the line containing [from] up to (not including) the line containing [to]. */
    private fun region(file: String, from: String, to: String): String {
        val text = (File(core, file).takeIf { it.exists() } ?: File(app, file)).readText()
        val start = text.indexOf(from)
        val end = text.indexOf(to, start + 1)
        assertTrue(start >= 0 && end > start, "markers '$from' .. '$to' must exist in $file; the indicator code moved")
        return text.substring(start, end)
    }

    @Test
    fun theIndicatorCodeNamesNoHarness() {
        val code = listOf(
            region("model/Model.kt", "sealed interface ChatStatus", "data class AgentActivity("),
            region("model/Model.kt", "data class RunActivityView(", "\n}"),
            region("model/Model.kt", "fun chatStatusOf(", "const val STALE_AFTER_MS"),
            region("ChatSurface.kt", "fun ChatStatusRow(", "/** Renders parsed assistant markdown"),
        ).joinToString("\n")
        // Comments may cite history; only code counts.
        val codeOnly = code.lineSequence().map { it.substringBefore("//") }.filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }.joinToString("\n")
        val harness = Regex("""\b(claude|opencode|pi|codex|gemini|ollama|omp)\b""", RegexOption.IGNORE_CASE)
        val hits = harness.findAll(codeOnly).map { it.value }.toList()
        assertTrue(hits.isEmpty(), "the chat indicator must name no harness, found $hits")
        assertTrue(codeOnly.contains("isRunning(state)"), "the indicator must key on the session state (the header badge's rule)")
    }
}
