package com.superiorsoftllc.agenthq.executables

import java.io.File

/**
 * HARNESS-INSTALL-1: where `npm install -g --prefix <dir>` (the built-in installer, `<AGENTHQ_HOME>/harness-tools`) leaves
 * each harness CLI. [ExecutableResolver] lists these FIRST among a tool's default locations, since a CLI exists there
 * only because the user asked the app for it. A null prefix contributes nothing.
 */
object HarnessPrefix {
    /**
     * Windows: the package's native bin, then `<dir>\<cmd>.cmd`; elsewhere `<dir>/bin/<cmd>`. A tool that carries its own
     * [CliTool.prefixBin] (a harness module's manifest) uses that; the others the built-in table.
     */
    fun locations(e: ExecutableEnvironment, tool: CliTool, prefix: String?): List<String> {
        val rel = tool.prefixBin ?: return locations(e, tool.id, prefix)
        prefix ?: return emptyList()
        val command = tool.commandNames.firstOrNull() ?: tool.id
        return if (e.os == HostOs.WINDOWS) {
            listOf(e.join(prefix, rel), e.join(prefix, "$command.cmd"))
        } else {
            listOf(e.join(prefix, "bin", command))
        }
    }

    /** The built-in table: the three harness CLIs the daemon still ships (H6-H8 move them into their modules). */
    fun locations(e: ExecutableEnvironment, toolId: String, prefix: String?): List<String> {
        prefix ?: return emptyList()
        val nativeRel = when (toolId) {
            "claude" -> "node_modules/@anthropic-ai/claude-code/bin/claude.exe"
            "opencode" -> "node_modules/opencode-ai/bin/opencode.exe"
            "pi" -> "node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js"
            else -> return emptyList()
        }
        return if (e.os == HostOs.WINDOWS) {
            listOf(e.join(prefix, nativeRel), e.join(prefix, "$toolId.cmd"))
        } else {
            listOf(e.join(prefix, "bin", toolId))
        }
    }

    /** Is [path] inside [prefix]? */
    fun contains(prefix: File, path: String): Boolean =
        runCatching { File(path).canonicalPath.startsWith(prefix.canonicalPath) }.getOrDefault(false)
}
