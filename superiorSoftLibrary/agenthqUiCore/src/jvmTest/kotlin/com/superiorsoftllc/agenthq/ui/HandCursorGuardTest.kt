package com.superiorsoftllc.agenthq.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M2-10: the hand cursor comes from the same-package wrappers in Clickables.kt, which shadow
 * `androidx.compose.foundation.clickable` and the Material buttons / menu item / checkbox. An explicit import of an
 * original wins over the package and silently drops the hand, so no file of the `ui` package may import one.
 */
class HandCursorGuardTest {
    /** The app UI's sources (agenthqAppUi's commonMain): the panes moved out of core, same `ui` package. */
    private val appUi = File("../../plugins/official")

    private val forbidden = Regex(
        """^import androidx\.compose\.(foundation\.clickable|foundation\.combinedClickable|material3\.(Button|OutlinedButton|TextButton|IconButton|DropdownMenuItem|Checkbox|FilledTonalButton|ElevatedButton|RadioButton|Switch|FilterChip))(\s+as\s+\w+)?\s*$""",
    )

    @Test
    fun `no ui file imports a clickable that bypasses the hand cursor`() {
        // Recurse into every subpackage of ui (pair, shell, sound, voice/plugin, ...). The web entry point sits
        // outside the ui package but renders the same screens, so it is scanned too, and so are the panes that moved
        // to the app UI (S4b), so the guard and its ratchet keep covering the same files.
        val roots = listOf("src/commonMain", "src/jvmMain", "src/androidMain")
            .map { File(it, "kotlin/com/superiorsoftllc/agenthq/ui") }
            .plus(File("../agentHqWeb/src/wasmJsMain/kotlin"))
            .plus(appUi)
            .filter { it.isDirectory }
        assertTrue(roots.isNotEmpty(), "ui sources not found from ${File(".").absolutePath}")
        val offenders = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { it.name != "Clickables.kt" }
            .flatMap { f -> f.readLines().withIndex().filter { forbidden.matches(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value}" } }
        if (offenders.isNotEmpty()) fail("these imports bypass the M2-10 hand cursor (use the wrappers in Clickables.kt):\n" + offenders.joinToString("\n"))
    }

    private val wrappedKinds = listOf("clickable", "combinedClickable", "Button", "OutlinedButton", "TextButton", "Switch", "Checkbox", "RadioButton", "FilterChip", "DropdownMenuItem")

    private fun sourceFiles(): List<File> {
        val roots = listOf("src/commonMain", "src/jvmMain", "src/androidMain")
            .map { File(it, "kotlin/com/superiorsoftllc/agenthq/ui") }
            .plus(File("../agentHqWeb/src/wasmJsMain/kotlin"))
            .plus(appUi)
            .filter { it.isDirectory }
        assertTrue(roots.isNotEmpty(), "ui sources not found from ${File(".").absolutePath}")
        return roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { it.name != "Clickables.kt" }
    }

    private fun codeLines(f: File) = f.readLines().withIndex().filter { val t = it.value.trimStart(); !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") }

    @Test
    fun `no ui file calls a wrapped kind fully qualified`() {
        val qualified = Regex("""androidx\.compose\.(material3|foundation)\.(${wrappedKinds.joinToString("|")})\b""")
        val offenders = sourceFiles().flatMap { f ->
            codeLines(f).filter { !it.value.startsWith("import ") && qualified.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }
        if (offenders.isNotEmpty()) fail("fully qualified calls skip the hand cursor (use the wrappers in Clickables.kt):\n" + offenders.joinToString("\n"))
    }

    /** Kinds without a wrapper yet: raw call counts may only go down. Lower the number when you migrate a kind. */
    private val rawBaseline = mapOf(
        // S6: 1058 / 100 at the S6 base (S4b-S5 drift past 1049 / 99), plus the drop box, consent dialog and safe-mode banner's 23 Text.
        // FIX-CONFIG-1: 1140 / 104 after STATS-ACC-1 (usage meter) and CONFIG-3/9 (generated cards, compile-time config) added +18 Text, +2 OutlinedTextField.
        // CONFIG-11: +2 Text in the daemon-config section of the Settings dialog.
        "Text" to 1142,
        "DropdownMenu" to 9,
        "OutlinedTextField" to 104,
        "BasicTextField" to 2,
        "Icon" to 18,
        "HorizontalDivider" to 31,
    )

    @Test
    fun `raw calls of the kinds not wrapped yet never grow`() {
        val files = sourceFiles()
        val counts = rawBaseline.keys.associateWith { kind ->
            val call = Regex("""(?<![\w.])$kind\(|androidx\.compose\.(material3|foundation\.text)\.$kind\(""")
            files.sumOf { f -> codeLines(f).count { !it.value.contains("fun $kind(") && call.containsMatchIn(it.value) } }
        }
        val grown = counts.filter { (k, n) -> n > rawBaseline.getValue(k) }
        val shrunk = counts.filter { (k, n) -> n < rawBaseline.getValue(k) }
        if (grown.isNotEmpty()) fail("raw calls grew (wrap them or reuse a wrapper): " + grown.map { (k, n) -> "$k ${rawBaseline[k]} -> $n" })
        if (shrunk.isNotEmpty()) fail("raw calls shrank, lower rawBaseline to lock it in: " + shrunk.map { (k, n) -> "$k ${rawBaseline[k]} -> $n" })
    }

    @Test
    fun `the board's drag handle takes its grab cursor from the Clickables wrapper`() {
        val ui = File("src/commonMain/kotlin/com/superiorsoftllc/agenthq/ui")
        assertTrue(ui.isDirectory, "ui sources not found from ${File(".").absolutePath}")
        val board = File(appUi, "Infantry_Board/src/Board.kt").readText()
        assertTrue(Regex("""\.dragHandleCursor\(enabled, dragging\)""").containsMatchIn(board), "the drag handle must use Modifier.dragHandleCursor")
        // A cursor set by hand is one the semantics (and the tests reading them) cannot see.
        val raw = (ui.listFiles { f -> f.extension == "kt" }.orEmpty().toList() + appUi.walkTopDown().filter { it.isFile && it.extension == "kt" })
            .filter { it.name !in setOf("Clickables.kt", "ResizeHandle.kt") && it.readText().contains("pointerHoverIcon") }
        if (raw.isNotEmpty()) fail("set the cursor through Clickables.kt, not pointerHoverIcon: ${raw.joinToString { it.name }}")
    }
}
