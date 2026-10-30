package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.runner.ProcessSpec

/**
 * H2 (docs/HARNESS-PLUGINS.md 3.4): the default provider of a daemon with NO harness registered. It lets the daemon start
 * and serve health; it is never listed as a harness and refuses every run with [NOT_INSTALLED], so a ticket start or a
 * spawn that names no provider says what to do instead of failing deep in a CLI launch.
 */
object NoHarnessProvider : AgentProvider {
    const val ID = "none"

    /** The one sentence every harness-needing action refuses with when nothing is installed. */
    const val NOT_INSTALLED = "no harness installed: install one (agenthq harness install ...)"

    override val id: String = ID

    override fun spec(request: RunRequest): ProcessSpec = throw IllegalStateException(NOT_INSTALLED)
}
