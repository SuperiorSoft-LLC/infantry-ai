package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.claude.ClaudeCommand
import com.superiorsoftllc.agenthq.opencode.OpenCodeCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 2 evidence: both providers compose argv and parse session ids behind one seam, with no
 * change to `ProcessRunner` or `Envelope`. Pure functions only -- no CLI needs to be installed.
 */
class ProviderSpecTest {

    // --- selection -----------------------------------------------------------

    @Test
    fun factoryDefaultsToOpenCode() {
        assertEquals("opencode", ProviderFactory.fromFlag(null).id)
        assertEquals("opencode", ProviderFactory.fromFlag("").id)
        assertEquals("opencode", ProviderFactory.fromFlag("opencode").id)
    }

    @Test
    fun factorySelectsClaude() {
        assertEquals("claude", ProviderFactory.fromFlag("claude").id)
        assertEquals("claude", ProviderFactory.fromFlag("Claude-Code").id)
    }

    @Test
    fun factoryRejectsUnknown() {
        assertTrue(runCatching { ProviderFactory.fromFlag("gemini") }.exceptionOrNull() is IllegalStateException)
    }

    // --- claude argv ---------------------------------------------------------

    @Test
    fun claudeBaselineArgv() {
        val argv = ClaudeCommand.command(
            executable = "claude.exe",
            model = null,
            permissionMode = null,
            mcpConfigs = emptyList(),
            strictMcp = false,
            resumeSessionId = null,
        )
        assertEquals(
            listOf("claude.exe", "-p", "--output-format", "stream-json", "--verbose", "--thinking", "adaptive", "--thinking-display", "summarized", "--allowedTools", "Bash(agenthq:*),PowerShell(agenthq:*),mcp__agenthq__*"), // BUG-PERM-5
            argv,
        )
    }

    @Test
    fun claudeFullArgvIncludingResume() {
        val argv = ClaudeCommand.command(
            executable = "claude.exe",
            model = "haiku",
            permissionMode = "default",
            mcpConfigs = listOf("mcp.json", "{\"mcpServers\":{}}"),
            strictMcp = true,
            resumeSessionId = "sess-1",
        )
        assertEquals(
            listOf(
                "claude.exe", "-p", "--output-format", "stream-json", "--verbose",
                "--thinking", "adaptive", "--thinking-display", "summarized", // 4b02c01: summarized thinking, else empty thinking blocks
                "--model", "haiku",
                "--permission-mode", "default",
                "--allowedTools", "Bash(agenthq:*),PowerShell(agenthq:*),mcp__agenthq__*", // BUG-PERM-5
                "--mcp-config", "mcp.json", "{\"mcpServers\":{}}", "--strict-mcp-config",
                "--resume", "sess-1",
            ),
            argv,
        )
    }

    @Test
    fun claudeOmitsBlankOptionalValues() {
        val argv = ClaudeCommand.command(
            executable = "claude.exe",
            model = "  ",
            permissionMode = "",
            mcpConfigs = emptyList(),
            strictMcp = true,
            resumeSessionId = "   ",
        )
        assertEquals(
            listOf("claude.exe", "-p", "--output-format", "stream-json", "--verbose", "--thinking", "adaptive", "--thinking-display", "summarized", "--allowedTools", "Bash(agenthq:*),PowerShell(agenthq:*),mcp__agenthq__*"), // BUG-PERM-5
            argv,
        )
    }

    // --- opencode argv -------------------------------------------------------

    @Test
    fun openCodeBaselineArgv() {
        val argv = OpenCodeCommand.command("opencode.exe", "opencode/space-bunny-free", null)
        assertEquals(
            listOf("opencode.exe", "run", "--format", "json", "-m", "opencode/space-bunny-free"),
            argv,
        )
    }

    @Test
    fun openCodeResumeAddsSession() {
        val argv = OpenCodeCommand.command("opencode.exe", "opencode/space-bunny-free", "ses_123")
        assertEquals(
            listOf(
                "opencode.exe", "run", "--format", "json",
                "-m", "opencode/space-bunny-free", "--session", "ses_123",
            ),
            argv,
        )
    }

    // --- session-id extraction ----------------------------------------------

    @Test
    fun sessionIdReadsBothKeyStyles() {
        assertEquals("camel", SessionId.fromLine("{\"type\":\"step_start\",\"sessionID\":\"camel\"}"))
        assertEquals("snake", SessionId.fromLine("{\"type\":\"system\",\"session_id\":\"snake\"}"))
    }

    @Test
    fun sessionIdToleratesNonSessionsAndGarbage() {
        assertNull(SessionId.fromLine("not json at all"))
        assertNull(SessionId.fromLine("{\"type\":\"text\",\"part\":{\"text\":\"hi\"}}"))
        assertNull(SessionId.fromLine("{\"session_id\":\"\"}"))
        assertNull(SessionId.fromLine("[]"))
    }

    // --- mcp config parsing ---------------------------------------------------

    @Test
    fun mcpConfigsSplitOnCommaAndNewline() {
        assertEquals(
            listOf("a.json", "b.json", "c.json"),
            ClaudeCommand.parseMcpConfigs("a.json, b.json\nc.json"),
        )
        assertEquals(emptyList(), ClaudeCommand.parseMcpConfigs(null))
        assertEquals(emptyList(), ClaudeCommand.parseMcpConfigs("  \n , "))
    }
}
