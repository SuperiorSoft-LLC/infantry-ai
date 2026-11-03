// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.descriptor

import com.superiorsoftllc.infantry.harness.HarnessRequest
import com.superiorsoftllc.infantry.harness.ToolApproval
import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * BUG-PERM-3: a descriptor's optional `toolApproval` argv map. One argv test per audited CLI per mode (codex, gemini),
 * and a descriptor with no map keeps today's argv in every mode and logs that it is unmapped.
 */
class DescriptorToolApprovalTest {
    @BeforeTest
    @AfterTest
    fun forgetLoggedUnmapped() = resetUnmappedLog()

    private fun parse(text: String) = HarnessDescriptor.parse(Json.parseToJsonElement(text))

    private fun argv(descriptor: HarnessDescriptor, mode: ToolApproval) =
        descriptor.spec(HarnessRequest("hi", descriptor.id, "p", toolApproval = mode)).command

    // The command names are made up on purpose: [HarnessDescriptor.spec] never resolves them, but no real CLI is implied.
    private val codex = parse(
        """{"id": "codex-t", "command": "codex-t", "args": ["exec", "--json", "{toolApproval}", "-"],
            "output": {"format": "text"},
            "toolApproval": {"deny": [], "allowEdits": ["--sandbox", "workspace-write"],
                             "allowAll": ["--dangerously-bypass-approvals-and-sandbox"]}}""",
    )

    private val gemini = parse(
        """{"id": "gemini-t", "command": "gemini-t", "args": ["--output-format", "stream-json", ["-p", "{prompt}"]],
            "output": {"format": "text"},
            "toolApproval": {"allowEdits": ["--approval-mode", "auto_edit"], "allowAll": ["--approval-mode", "yolo"]}}""",
    )

    private val unmapped = parse("""{"id": "plain-t", "command": "plain-t", "args": ["run", "{prompt}"], "output": {"format": "text"}}""")

    @Test
    fun codexDenyAddsNothingAtTheSlot() =
        assertEquals(listOf("codex-t", "exec", "--json", "-"), argv(codex, ToolApproval.DENY))

    @Test
    fun codexAllowEditsIsWorkspaceWriteSandboxAfterTheSubcommand() =
        assertEquals(listOf("codex-t", "exec", "--json", "--sandbox", "workspace-write", "-"), argv(codex, ToolApproval.ALLOW_EDITS))

    @Test
    fun codexAllowAllBypassesApprovalsAndSandbox() =
        assertEquals(listOf("codex-t", "exec", "--json", "--dangerously-bypass-approvals-and-sandbox", "-"), argv(codex, ToolApproval.ALLOW_ALL))

    @Test
    fun geminiDenyIsUnmappedAndAddsNothing() {
        assertEquals(listOf("gemini-t", "--output-format", "stream-json", "-p", "hi"), argv(gemini, ToolApproval.DENY))
        assertTrue(gemini.unmappedToolApproval(ToolApproval.DENY))
    }

    @Test
    fun geminiAllowEditsIsAutoEditAppendedAfterTheTemplate() =
        assertEquals(listOf("gemini-t", "--output-format", "stream-json", "-p", "hi", "--approval-mode", "auto_edit"), argv(gemini, ToolApproval.ALLOW_EDITS))

    @Test
    fun geminiAllowAllIsYolo() =
        assertEquals(listOf("gemini-t", "--output-format", "stream-json", "-p", "hi", "--approval-mode", "yolo"), argv(gemini, ToolApproval.ALLOW_ALL))

    @Test
    fun aDescriptorWithoutAMapKeepsTodaysArgvInEveryModeAndLogsItIsUnmapped() {
        val lines = ArrayList<String>()
        val harness = DescriptorHarness(unmapped, PipedProcessRunner(), log = { lines += it })
        for (mode in ToolApproval.entries) {
            assertTrue(unmapped.unmappedToolApproval(mode), "${mode.wire} must be unmapped")
            assertEquals(listOf("run", "hi"), harness.processSpec(HarnessRequest("hi", "plain-t", "p", toolApproval = mode)).command.drop(1))
        }
        // A second run in the same mode logs nothing new: once per descriptor and mode, not once per run.
        harness.processSpec(HarnessRequest("hi", "plain-t", "p", toolApproval = ToolApproval.ALLOW_ALL))
        assertEquals(ToolApproval.entries.size, lines.size, "lines: $lines")
        assertTrue(lines.all { "plain-t" in it && "unmapped" in it }, "lines: $lines")
        assertTrue(lines.any { "'allowAll'" in it })
    }

    @Test
    fun aMappedModeLogsNothing() {
        val lines = ArrayList<String>()
        val harness = DescriptorHarness(codex, PipedProcessRunner(), log = { lines += it })
        ToolApproval.entries.forEach { harness.processSpec(HarnessRequest("hi", "codex-t", "p", toolApproval = it)) }
        assertEquals(emptyList(), lines)
    }

    @Test
    fun anInvalidToolApprovalMapIsRefused() {
        val bad = listOf(
            """"toolApproval": []""",
            """"toolApproval": {"sometimes": []}""",
            """"toolApproval": {"allowAll": "--yolo"}""",
            """"toolApproval": {"allowAll": [1]}""",
            """"toolApproval": {"allowAll": ["--model={model}"]}""",
        )
        for (field in bad) {
            assertFailsWith<InvalidDescriptor>(field) {
                parse("""{"id": "x", "command": "x", "output": {"format": "text"}, $field}""")
            }
        }
        assertFailsWith<InvalidDescriptor> {
            parse("""{"id": "x", "command": "x", "args": ["{toolApproval}", "{toolApproval}"], "output": {"format": "text"}}""")
        }
    }
}
