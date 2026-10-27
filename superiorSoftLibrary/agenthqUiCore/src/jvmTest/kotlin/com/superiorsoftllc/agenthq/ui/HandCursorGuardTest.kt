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
    private val forbidden = Regex(
        """^import androidx\.compose\.(foundation\.clickable|foundation\.combinedClickable|material3\.(Button|OutlinedButton|TextButton|IconButton|DropdownMenuItem|Checkbox|FilledTonalButton|ElevatedButton|RadioButton|Switch|FilterChip))(\s+as\s+\w+)?\s*$""",
    )

    @Test
    fun `no ui file imports a clickable that bypasses the hand cursor`() {
        // Recurse into every subpackage of ui (pair, shell, sound, voice/plugin, ...). The web entry point sits
        // outside the ui package but renders the same screens, so it is scanned too.
        val roots = listOf("src/commonMain", "src/jvmMain", "src/androidMain")
            .map { File(it, "kotlin/com/superiorsoftllc/agenthq/ui") }
            .plus(File("../agentHqWeb/src/wasmJsMain/kotlin"))
            .filter { it.isDirectory }
        assertTrue(roots.isNotEmpty(), "ui sources not found from ${File(".").absolutePath}")
        val offenders = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { it.name != "Clickables.kt" }
            .flatMap { f -> f.readLines().withIndex().filter { forbidden.matches(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value}" } }
        if (offenders.isNotEmpty()) fail("these imports bypass the M2-10 hand cursor (use the wrappers in Clickables.kt):\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `the board's drag handle takes its grab cursor from the Clickables wrapper`() {
        val ui = File("src/commonMain/kotlin/com/superiorsoftllc/agenthq/ui")
        assertTrue(ui.isDirectory, "ui sources not found from ${File(".").absolutePath}")
        val board = File(ui, "Board.kt").readText()
        assertTrue(Regex("""\.dragHandleCursor\(enabled, dragging\)""").containsMatchIn(board), "the drag handle must use Modifier.dragHandleCursor")
        // A cursor set by hand is one the semantics (and the tests reading them) cannot see.
        val raw = ui.listFiles { f -> f.extension == "kt" }.orEmpty()
            .filter { it.name !in setOf("Clickables.kt", "ResizeHandle.kt") && it.readText().contains("pointerHoverIcon") }
        if (raw.isNotEmpty()) fail("set the cursor through Clickables.kt, not pointerHoverIcon: ${raw.joinToString { it.name }}")
    }
}
