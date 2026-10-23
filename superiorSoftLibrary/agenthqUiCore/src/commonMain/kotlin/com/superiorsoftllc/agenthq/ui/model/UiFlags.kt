package com.superiorsoftllc.agenthq.ui.model

/**
 * CONFIG flags surface, first flag: how the agent lists are scoped. The labels and effects below are the
 * copy the configuration modal shows; the default is [PROJECT] (BUG-M2-15): the lists follow the selected project.
 */
enum class AgentScope(val label: String, val effect: String) {
    GLOBAL(
        "Global Agents",
        "Every agent is listed, whatever project it belongs to.",
    ),
    PROJECT(
        "Project Scoped Agents",
        "The default. Only the selected project's agents are listed: an agent created under another project is hidden until that project is selected. Agents with no project stay visible, and with no project selected every agent is listed.",
    ),
    ;

    /**
     * Whether an agent bound to [sessionProjectId] is listed while [activeProjectId] is the selected project.
     * Scoping hides only an agent bound to a DIFFERENT project. An unbound agent (null) stays listed, and so
     * does every agent while no project is selected.
     */
    fun admits(sessionProjectId: String?, activeProjectId: String?): Boolean = when (this) {
        GLOBAL -> true
        PROJECT -> activeProjectId == null || sessionProjectId == null || sessionProjectId == activeProjectId
    }
}

/** The flag values the window holds. Presentation state only: nothing here is written to disk. */
data class UiFlags(val agentScope: AgentScope = AgentScope.PROJECT)

/** What the modal says each flag applies to, shown in the label rather than a tooltip. */
const val AGENT_SCOPE_APPLIES_TO = "Applies to: the sidebar agent list and the top-bar avatars. Agents with no project are listed under every project. Header counters, the roster and the board are not affected."

/** Shown once in the modal: how long a change lasts and whether it needs a restart. */
const val FLAGS_LIFETIME_NOTE = "Changes apply at once and last for this window only. They reset when AgentHQ restarts."
