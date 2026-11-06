// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.McpState
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import java.io.File

/**
 * HARNESS-GAP G12: watches the pid file the daemon's MCP server writes ([com.superiorsoftllc.infantry.harness.api.MCP_PID_FILE_ENV]) and
 * reports changes with [SessionOutput.mcpState]. No file yet = not started: nothing to say. A running pid = connected; the same pid gone =
 * disconnected; a new running pid = connected again. [check] is rate limited to [MCP_CHECK_MS]; the headless session polls it on a thread.
 */
internal class McpPidWatch(
    private val file: File,
    private val out: SessionOutput,
    private val pidAlive: (Long) -> Boolean = ::processAlive,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var checkedAt: Long? = null
    private var seen: Pair<Long, Boolean>? = null

    @Synchronized
    fun check() {
        val now = clock()
        checkedAt?.let { if (now - it < MCP_CHECK_MS) return }
        checkedAt = now
        val pid = runCatching { file.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull() ?: return
        val alive = pidAlive(pid)
        if (seen == pid to alive) return
        val wasUp = seen?.second
        seen = pid to alive
        if (alive && wasUp != true) out.mcpState(McpState.CONNECTED, "MCP server pid $pid runs")
        else if (!alive && wasUp != false) out.mcpState(McpState.DISCONNECTED, "MCP server pid $pid exited")
    }

    companion object {
        const val MCP_CHECK_MS = 2_000L
    }
}
