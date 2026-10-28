package com.superiorsoftllc.agenthq.ui.shell.blocks

import com.superiorsoftllc.agenthq.ui.ConfigAgentScopeSection
import com.superiorsoftllc.agenthq.ui.ConfigBlurSection
import com.superiorsoftllc.agenthq.ui.ConfigCliLocationsSection
import com.superiorsoftllc.agenthq.ui.ConfigEntry
import com.superiorsoftllc.agenthq.ui.ConfigIncomingSoundSection
import com.superiorsoftllc.agenthq.ui.ConfigLifecycleSection
import com.superiorsoftllc.agenthq.ui.ConfigLimitsSection
import com.superiorsoftllc.agenthq.ui.ConfigMarkdownEngineSection
import com.superiorsoftllc.agenthq.ui.ConfigProgressStaleSection
import com.superiorsoftllc.agenthq.ui.ConfigQuestionsSection
import com.superiorsoftllc.agenthq.ui.ConfigReasoningSection
import com.superiorsoftllc.agenthq.ui.ConfigRolesSection
import com.superiorsoftllc.agenthq.ui.ConfigTabGroupsSection
import com.superiorsoftllc.agenthq.ui.ConfigThemeSection
import com.superiorsoftllc.agenthq.ui.ConfigToolApprovalSection
import com.superiorsoftllc.agenthq.ui.ConfigUserPortraitSection
import com.superiorsoftllc.agenthq.ui.ConfigVoiceSection
import com.superiorsoftllc.agenthq.ui.block.layout.GroupBlock

/**
 * The settings: app UI, open to a module (replace, wrap, and inject into this group). A group is transparent, so its
 * children are drawn as the container's own. S4c: Infantry_AppUi fills the shell's empty slot of the same id with it
 * ([CoreShell.slots]); without the app UI the dialog shows its trust sections only.
 */
val ShellConfigSettings = GroupBlock(
    "infantry.shell.config.settings",
    listOf(
        ConfigSectionBlock(ConfigEntry.THEME, "theme") { c, m -> ConfigThemeSection(c, m) },
        ConfigSectionBlock(ConfigEntry.BLUR, "blur") { c, m -> ConfigBlurSection(c, m) },
        ConfigSectionBlock(ConfigEntry.MARKDOWN_ENGINE, "markdownEngine") { c, m -> ConfigMarkdownEngineSection(c, m) },
        ConfigSectionBlock(ConfigEntry.REASONING, "reasoning") { c, m -> ConfigReasoningSection(c, m) },
        ConfigSectionBlock(ConfigEntry.TAB_GROUPS, "tabGroups") { c, m -> ConfigTabGroupsSection(c, m) },
        ConfigSectionBlock(ConfigEntry.USER_PORTRAIT, "userPortrait") { c, m -> ConfigUserPortraitSection(c, m) },
        ConfigSectionBlock(ConfigEntry.AGENT_SCOPE, "agentScope") { c, m -> ConfigAgentScopeSection(c, m) },
        ConfigSectionBlock(ConfigEntry.PROGRESS_STALE, "progressStale") { c, m -> ConfigProgressStaleSection(c, m) },
        ConfigSectionBlock(ConfigEntry.LIFECYCLE, "lifecycle") { c, m -> ConfigLifecycleSection(c, m) },
        ConfigSectionBlock(ConfigEntry.CLI_LOCATIONS, "cliLocations") { c, m -> ConfigCliLocationsSection(c, m) },
        ConfigSectionBlock(ConfigEntry.TOOL_APPROVAL, "toolApproval") { c, m -> ConfigToolApprovalSection(c, m) },
        ConfigSectionBlock(ConfigEntry.ROLES, "roles") { c, m -> ConfigRolesSection(c, m) },
        ConfigSectionBlock(ConfigEntry.LIMITS, "limits") { c, m -> ConfigLimitsSection(c, m) },
        ConfigSectionBlock(ConfigEntry.VOICE, "voice") { c, m -> ConfigVoiceSection(c, m) },
        ConfigSectionBlock(ConfigEntry.INCOMING_SOUND, "incomingSound") { c, m -> ConfigIncomingSoundSection(c, m) },
        ConfigSectionBlock(ConfigEntry.QUESTIONS, "questions") { c, m -> ConfigQuestionsSection(c, m) },
    ),
)
