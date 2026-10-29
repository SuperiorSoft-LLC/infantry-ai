package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugin.api.ActionKind
import com.superiorsoftllc.agenthq.plugin.api.FrameAction
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.PolicyWire
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * GAP-AUDIT-1 (h): a control that asks no gate cannot slip in again.
 * 1. Every non-read action a plugin declares in module.json is asked through a gate helper in that plugin's own sources
 *    (`gate("id"`, `Gated("id"`, `ActionButton("id"`, `rememberGate("id"`, a pane's `can("id"`), so a declaration
 *    without a dimmed control fails here.
 * 2. The send side ([PolicyWire.refusesFrame], which Controller.send asks for every command): a frame the policy turns
 *    off is refused before it is sent, with the daemon's refusal text, whether or not its control asked a gate.
 */
class PolicyGateGuardTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `every declared write, destructive or spawn action is gated in its plugin`() {
        val roots = listOf(File("../../plugins/official"), File("../../plugins/demo"))
        assertTrue(roots.all { it.isDirectory }, "plugins not found from ${File(".").absolutePath}")
        val missing = mutableListOf<String>()
        var checked = 0
        for (dir in roots.flatMap { it.listFiles().orEmpty().toList() }.filter { File(it, "module.json").isFile }.sortedBy { it.name }) {
            val manifest = json.decodeFromString(ModuleManifest.serializer(), File(dir, "module.json").readText())
            val src = File(dir, "src").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
            for (a in manifest.actions.filter { it.kind != ActionKind.READ }) {
                checked++
                val asked = Regex("""\b(gate|Gated|ActionButton|rememberGate|can)\(\s*"${Regex.escape(a.id)}"""").containsMatchIn(src)
                if (!asked) missing += "${dir.name}: action ${a.id} (${a.kind}) is declared but no control asks its gate"
            }
        }
        assertTrue(checked > 20, "too few actions checked ($checked)")
        if (missing.isNotEmpty()) fail(missing.joinToString("\n"))
    }

    @Test fun `the send gate refuses what the policy turns off, with the daemon's text, and nothing under production`() {
        val frames = mapOf(
            "ticket_save" to FrameAction("board.ticket.save", ActionKind.WRITE),
            "ticket_list" to FrameAction("board.read", ActionKind.READ),
        )
        val readOnly = PolicyWire(preset = "read-only", disable = listOf("*"), allow = listOf("kind:read"), reason = "RO", frames = frames,
            refusal = "policy: '{frame}' ({action}) is disabled: {reason}")
        assertEquals("policy: 'ticket_save' (board.ticket.save) is disabled: RO", readOnly.refusesFrame("ticket_save"))
        assertNull(readOnly.refusesFrame("ticket_list"), "a read goes")
        assertNull(readOnly.refusesFrame("something_unmapped"), "an unmapped frame is the daemon's to refuse")
        assertNull(PolicyWire.Production.refusesFrame("ticket_save"))
        assertNull(readOnly.copy(frames = emptyMap()).refusesFrame("ticket_save"), "an older daemon sends no catalog: nothing is refused here")
    }
}
