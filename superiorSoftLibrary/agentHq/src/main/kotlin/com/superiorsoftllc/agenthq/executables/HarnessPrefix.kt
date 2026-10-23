package com.superiorsoftllc.agenthq.executables

import java.io.File

/**
 * HARNESS-INSTALL-1: where `npm install -g --prefix <dir>` (the built-in installer, `<AGENTHQ_HOME>/harness-tools`) leaves
 * each harness CLI. [ExecutableResolver] lists these FIRST among a tool's default locations, since a CLI exists there
 * only because the user asked the app for it. A null prefix contributes nothing.
 */
object HarnessPrefix {
    /** Windows: the package's native bin, then `<dir>\<cmd>.cmd`; elsewhere `<dir>/bin/<cmd>`. */
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
