package com.superiorsoftllc.agenthq.claude

import com.superiorsoftllc.agenthq.harness.ToolApproval
import com.superiorsoftllc.agenthq.session.agentHqSetting

/**
 * BUG-PERM-1: Claude's mapping of the harness-neutral [ToolApproval] onto `--permission-mode`.
 *
 * Claude runs in `-p` print mode, which cannot prompt: a tool outside the user's Claude allowlist is denied silently,
 * so the mode decides what a session may do.
 *
 *  - [ToolApproval.DENY] -> no flag (Claude's own default: its allowlist only);
 *  - [ToolApproval.ALLOW_EDITS] -> `--permission-mode acceptEdits`;
 *  - [ToolApproval.ALLOW_ALL] -> `--permission-mode bypassPermissions`.
 *
 * The legacy [LEGACY_ENV] (`AGENTHQ_CLAUDE_PERMISSION_MODE`, system property then env) still wins, for Claude only.
 */
object ClaudePermissionMode {
    const val LEGACY_ENV = "AGENTHQ_CLAUDE_PERMISSION_MODE"

    fun forApproval(
        approval: ToolApproval,
        getProperty: (String) -> String? = System::getProperty,
        getenv: (String) -> String? = System::getenv,
    ): String? {
        agentHqSetting(LEGACY_ENV, getProperty, getenv)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return when (approval) {
            // PROMPT-1/3: `ask` keeps Claude's default mode; the persistent adapter adds `--permission-prompt-tool stdio`
            // so a call outside the allowlist is relayed to the owner. The one-shot run cannot prompt: there it is `deny`.
            ToolApproval.DENY, ToolApproval.ASK -> null
            ToolApproval.ALLOW_EDITS -> "acceptEdits"
            ToolApproval.ALLOW_ALL -> "bypassPermissions"
        }
    }
}
