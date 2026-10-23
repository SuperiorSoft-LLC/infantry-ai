package com.superiorsoftllc.agenthq.harness

/**
 * BUG-PERM-1 (harness contract 1.5.0, daemon contract 1.76.0): how freely a session's tools may run without a human
 * approving each call. It is HARNESS-NEUTRAL: the daemon resolves one value per run and hands it to every harness
 * ([HarnessRequest.toolApproval], [com.superiorsoftllc.agenthq.provider.RunRequest.toolApproval]); each harness maps
 * it to its own mechanism (Claude: `--permission-mode`; OpenCode: its `permission.asked` reply; ...).
 *
 * No AgentHQ harness can relay an approval prompt to the UI yet, so a call the mode does not allow is DENIED, and the
 * harness should report it as a denial (see `EventNormalizer.Line.Denial`) so the chat can say so.
 *
 *  - [DENY]: today's behaviour. Only what the CLI's own configuration already allows runs; everything else is denied.
 *  - [ALLOW_EDITS]: file edits run without approval; other tools (shell, network...) follow [DENY].
 *  - [ALLOW_ALL]: every tool runs without approval.
 *  - [ASK] (PROMPT-1, daemon contract 1.77.0): a call the CLI would ask about is relayed to the owner as a
 *    `prompt_request` card (see [com.superiorsoftllc.agenthq.prompts.PendingPrompts]). NOT the default. A harness that
 *    cannot relay a prompt treats it exactly as [DENY] ([withoutPrompt]) and its denial notice says
 *    "(this harness can't prompt)".
 */
enum class ToolApproval(val wire: String) {
    DENY("deny"),
    ALLOW_EDITS("allowEdits"),
    ALLOW_ALL("allowAll"),
    ASK("ask");

    /** PROMPT-1: what a harness that cannot prompt runs with: [ASK] is [DENY], every other mode is itself. */
    fun withoutPrompt(): ToolApproval = if (this == ASK) DENY else this

    companion object {
        /** The wire values, in display order. */
        val WIRE: List<String> = entries.map { it.wire }

        /** [text] as a mode, or null when it is null, blank or not a known wire value. */
        fun parse(text: String?): ToolApproval? = text?.trim()?.let { t -> entries.firstOrNull { it.wire == t } }

        /** Like [parse], but an unknown non-blank value is an [IllegalArgumentException] naming the known values. */
        fun parseStrict(text: String?): ToolApproval? {
            val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return parse(trimmed) ?: throw IllegalArgumentException("unknown tool approval '${trimmed.take(40)}' (known: ${WIRE.joinToString()})")
        }
    }
}
