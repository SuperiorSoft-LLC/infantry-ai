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
data class UiFlags(
    val agentScope: AgentScope = AgentScope.PROJECT,
    /** VOICE-1: spoken attention alerts. */
    val voice: com.superiorsoftllc.agenthq.ui.voice.VoiceSettings = com.superiorsoftllc.agenthq.ui.voice.VoiceSettings(),
    /** The incoming-comms sound (on by default, volume, optional own mp3). Persisted by the host. */
    val commsSound: com.superiorsoftllc.agenthq.ui.sound.IncomingCommsSoundSettings = com.superiorsoftllc.agenthq.ui.sound.IncomingCommsSoundSettings(),
    /** MODAL-BLUR: blur the app behind dialogs (default on); off keeps the dark scrim. Persisted by the host. */
    val blurBehindDialogs: Boolean = true,
    /** Contract 1.117.0: a running progress bar turns amber with "no update for N min" after this many minutes. Persisted by the host. */
    val progressStaleMin: Int = PROGRESS_STALE_DEFAULT_MIN,
    /** CHAT-MD-ENGINE: id of the chosen chat markdown engine (client-side, per device); unknown ids fall back to the built-in one. Persisted by the host. */
    val chatMarkdownEngine: String = BuiltInChatMarkdownEngine.ID,
)

const val PROGRESS_STALE_APPLIES_TO = "Applies to: the progress bars agents report on their card, their session header and their ticket's board card. A running bar with no update for this long turns amber."

/** What the modal says each flag applies to, shown in the label rather than a tooltip. */
const val AGENT_SCOPE_APPLIES_TO = "Applies to: the sidebar agent list and the top-bar avatars. Agents with no project are listed under every project. Header counters, the roster and the board are not affected."

/** Shown once in the modal: how long a change lasts and whether it needs a restart. */
const val FLAGS_LIFETIME_NOTE = "Changes apply at once. A setting that says \"Remembered across launches\", or that the daemon stores, survives a restart; the others last for this window only."
