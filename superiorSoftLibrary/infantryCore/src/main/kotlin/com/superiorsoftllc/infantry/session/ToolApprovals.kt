// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.harness.ToolApproval
import com.superiorsoftllc.infantry.persistence.InfantryStore
import com.superiorsoftllc.infantry.persistence.SettingRow
import com.superiorsoftllc.infantry.protocol.ToolApprovalInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * BUG-PERM-1 (contract 1.76.0): which [ToolApproval] a run gets. Resolved per run, in this order:
 *  1. the session's own override, chosen at spawn (`new` `{toolApproval}`);
 *  2. the global Settings value (Settings > "Tool approval"; the daemon's `settings` table, [KEY]);
 *  3. [ENV] through [infantrySetting] (JVM system property, then the environment variable);
 *  4. [ToolApproval.DENY].
 *
 * A harness-specific legacy variable (Claude's `INFANTRY_CLAUDE_PERMISSION_MODE`) is applied by that harness's own
 * mapping, after this resolution, and only for that harness.
 */
object ToolApprovals {
    const val ENV = "INFANTRY_TOOL_APPROVAL"
    const val KEY = "toolApproval.mode"

    /** The resolved [mode] and where it came from: `session` | `setting` | `env` | `default`. */
    data class Resolution(val mode: ToolApproval, val source: String)

    /** The daemon installs its store-backed reader here (see [install]); none = no global setting. */
    @Volatile
    private var stored: () -> ToolApproval? = { null }

    /** Install [reader]; the returned function puts the previous reader back (only if [reader] is still installed). */
    fun install(reader: () -> ToolApproval?): () -> Unit {
        val previous = stored
        stored = reader
        return { if (stored === reader) stored = previous }
    }

    /** What a run with [sessionOverride] gets right now. */
    fun current(sessionOverride: ToolApproval? = null): Resolution =
        resolve(sessionOverride, runCatching { stored() }.getOrNull())

    /** Pure resolution, so the order can be asserted without touching the store or the environment. */
    fun resolve(
        sessionOverride: ToolApproval?,
        setting: ToolApproval?,
        getProperty: (String) -> String? = System::getProperty,
        getenv: (String) -> String? = InfantryEnv::get,
    ): Resolution {
        sessionOverride?.let { return Resolution(it, "session") }
        setting?.let { return Resolution(it, "setting") }
        ToolApproval.parse(infantrySetting(ENV, getProperty, getenv))?.let { return Resolution(it, "env") }
        return Resolution(ToolApproval.DENY, "default")
    }
}

/**
 * BUG-PERM-1: the global Settings value, durable in the daemon's `settings` table under [ToolApprovals.KEY] (a JSON
 * string; `""` = cleared, since the table has no delete). Like the CLI locations, it lives outside
 * [com.superiorsoftllc.infantry.settings.SettingsService]'s closed key set and is read and written only through the
 * `tool_approval` / `tool_approval_set` frames.
 */
class ToolApprovalService(private val store: InfantryStore) {
    /** The stored value, null when none (or cleared, or unreadable). */
    fun stored(): ToolApproval? {
        val raw = store.settings.get(ToolApprovals.KEY)?.value ?: return null
        val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonPrimitive }.getOrNull()
        return ToolApproval.parse(parsed?.takeIf { it.isString }?.content)
    }

    /** The `tool_approval` frame: the stored value and what a run without a session override gets now. */
    fun describe(): ToolApprovalInfo {
        val setting = stored()
        val resolution = ToolApprovals.resolve(null, setting)
        return ToolApprovalInfo(
            mode = setting?.wire,
            effective = resolution.mode.wire,
            source = resolution.source,
            choices = ToolApproval.WIRE,
        )
    }

    /** Store [mode] (null or blank CLEARS it). An unknown mode is an [IllegalArgumentException]; nothing is stored then. */
    fun set(mode: String?): ToolApprovalInfo {
        val approval = ToolApproval.parseStrict(mode)
        store.settings.put(SettingRow(ToolApprovals.KEY, JsonPrimitive(approval?.wire.orEmpty()).toString(), System.currentTimeMillis()))
        return describe()
    }
}

/** BUG-PERM-1: the one-line chat notice for a denied tool, shared by every harness. */
object ToolApprovalNotice {
    fun text(harness: String, tool: String, mode: ToolApproval, prompted: Boolean = false): String =
        if (prompted) "$harness denied tool $tool (Tool approval = ${mode.wire}: $NOT_APPROVED)."
        else "$harness denied tool $tool (Tool approval = ${mode.wire})" +
            (if (mode == ToolApproval.ASK) " $CANNOT_PROMPT" else "") +
            ". Change it in Settings > Tool approval."

    /** PROMPT-1: added when the mode is `ask` but the harness that denied the call has no prompt relay. */
    const val CANNOT_PROMPT = "(this harness can't prompt)"

    /** PROMPT-4: the owner was asked and did not approve (deny, timeout, session end). */
    const val NOT_APPROVED = "not approved at the prompt"
}
