// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents

import com.superiorsoftllc.infantry.daemon.api.knowledge.AgentBriefing
import com.superiorsoftllc.infantry.daemon.api.knowledge.BriefingBudget
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeBriefing
import com.superiorsoftllc.infantry.settings.LimitRegistry

/**
 * PK-1 (plan CAL-PLAN-2 B3.1): the core side of the plugin briefing. Asks the `AgentBriefing` provider (none = no section) for the
 * section of a session, with the budget from the settings `limits.knowledge.plugins.maxChars` / `summaryMaxChars`, and holds the
 * answer to that budget even if a provider overshoots it. A provider that throws gives no section, never a failed spawn.
 */
object PluginBriefingSection {
    fun of(provider: AgentBriefing?, role: String?, projectId: String?, maxChars: Long, summaryMaxChars: Long): String? {
        provider ?: return null
        val budget = BriefingBudget(
            maxChars = LimitRegistry.cap(maxChars, LimitRegistry.KNOWLEDGE_PLUGINS_MAX_CHARS),
            summaryMaxChars = LimitRegistry.cap(summaryMaxChars, LimitRegistry.KNOWLEDGE_PLUGINS_SUMMARY_MAX_CHARS),
        )
        val text = runCatching { provider.section(role, projectId, budget) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return KnowledgeBriefing.cut(text, budget.maxChars.takeIf { it != Int.MAX_VALUE } ?: 0)
    }
}
