package com.superiorsoftllc.agenthq.protocol

import kotlinx.serialization.Serializable

/**
 * Contract 1.76.0 (BUG-PERM-1): the `tool_approval` frame, Settings > "Tool approval".
 *
 *  - [mode]: the stored global value (`deny` | `allowEdits` | `allowAll`); null = none stored.
 *  - [effective]: what a run with no session override gets right now.
 *  - [source]: where [effective] came from: `setting`, `env` (`AGENTHQ_TOOL_APPROVAL`) or `default` (deny).
 *  - [choices]: every value Settings may set, in display order.
 */
@Serializable
data class ToolApprovalInfo(
    val mode: String? = null,
    val effective: String,
    val source: String,
    val choices: List<String> = emptyList(),
)

/**
 * The `tool_approval_set` payload. [mode] null or blank CLEARS the stored value; an unknown value is refused
 * (`error` "tool_approval_set refused: <reason>"), never stored.
 */
@Serializable
data class ToolApprovalSet(val mode: String? = null) {
    companion object {
        fun decode(text: String): ToolApprovalSet = StrictJson.decode(serializer(), text, "tool_approval_set payload")
    }
}
