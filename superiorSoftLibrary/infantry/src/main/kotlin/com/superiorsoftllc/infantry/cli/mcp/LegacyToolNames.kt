package com.superiorsoftllc.infantry.cli.mcp

/**
 * RENAME-2: every tool is registered and listed as `infantry_*`; the pre-rename `agenthq_*` spelling stays an alias that
 * behaves identically (existing scripts, hooks, role notes and skills call it). Not listed, only accepted.
 */
object LegacyToolNames {
    private const val LEGACY = "agenthq_"
    private const val CURRENT = "infantry_"

    /** [name] with a leading `agenthq_` read as `infantry_`; any other name is returned unchanged. */
    fun canonical(name: String): String = if (name.startsWith(LEGACY)) CURRENT + name.removePrefix(LEGACY) else name
}
