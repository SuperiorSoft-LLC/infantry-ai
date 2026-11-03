// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.unit.dp

import com.superiorsoftllc.infantry.ui.model.REASONING_APPLIES_TO
import com.superiorsoftllc.infantry.ui.model.REASONING_TITLE

import com.superiorsoftllc.infantry.ui.model.AGENT_SCOPE_APPLIES_TO
import com.superiorsoftllc.infantry.ui.model.CHAT_MARKDOWN_APPLIES_TO
import com.superiorsoftllc.infantry.ui.model.CHAT_MARKDOWN_TITLE
import com.superiorsoftllc.infantry.ui.model.CliLocationsSetting
import com.superiorsoftllc.infantry.ui.model.LifecycleSetting
import com.superiorsoftllc.infantry.ui.model.PROGRESS_STALE_APPLIES_TO
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import com.superiorsoftllc.infantry.ui.model.ToolApprovalSetting
import com.superiorsoftllc.infantry.ui.model.UserPortrait

const val TEST_QUESTION_TITLE = "Test question"
const val TEST_QUESTION_APPLIES_TO = "Applies to: the session you have open. Raises a sample option menu there so you can see and try the popup (click, keys 1-9 + Enter, or type your own answer) without waiting for an agent to ask. Your pick is mailed to that session, marked TEST."
const val TEST_QUESTION_TAG = "test-question-send"

/**
 * CONFIG-TABS-1: the tabs of the configuration modal, in display order. Appearance comes first.
 * A tab with no section to show (e.g. Secrets on an older daemon) is hidden by [visibleConfigTabs].
 */
enum class ConfigTab(val label: String) {
    APPEARANCE("Appearance"),
    GENERAL("General"),
    AGENTS("Agents"),
    LIMITS("Limits"),
    NOTIFICATIONS("Notifications"),
    PLUGINS("Plugins"),
    SECRETS("Secrets"),
    DEVICES("Devices"),
    ABOUT("About"),
}

/**
 * CONFIG-TABS-1: every section of the configuration modal, each in exactly one tab, in the order it shows there.
 * [title] and [applies] are the copy the section shows; [keywords] are extra words a later search may match
 * (ticket 3463d1b2: search + jump/highlight builds on this registry).
 */
enum class ConfigEntry(val tab: ConfigTab, val title: String, val applies: String, val keywords: List<String> = emptyList()) {
    THEME(ConfigTab.APPEARANCE, ThemeSettings.TITLE, ThemeSettings.APPLIES_TO, listOf("color", "colour", "accent", "dark", "light")),
    BLUR(ConfigTab.APPEARANCE, BLUR_TITLE, BLUR_APPLIES_TO, listOf("dialog", "scrim", "modal")),
    USER_PORTRAIT(ConfigTab.APPEARANCE, UserPortrait.TITLE, UserPortrait.APPLIES_TO, listOf("avatar", "picture", "photo")),
    MARKDOWN_ENGINE(ConfigTab.APPEARANCE, CHAT_MARKDOWN_TITLE, CHAT_MARKDOWN_APPLIES_TO, listOf("markdown", "table", "chat", "format", "renderer")),
    REASONING(ConfigTab.APPEARANCE, REASONING_TITLE, REASONING_APPLIES_TO, listOf("reasoning", "thinking", "thought", "panel", "inline")),
    TAB_GROUPS(ConfigTab.APPEARANCE, com.superiorsoftllc.infantry.ui.plugin.TabGroupsSetting.TITLE, com.superiorsoftllc.infantry.ui.plugin.TabGroupsSetting.APPLIES_TO, listOf("settings", "tabs", "group", "sub-tabs", "strip")),
    AGENT_SCOPE(ConfigTab.GENERAL, AGENT_SCOPE_TITLE, AGENT_SCOPE_APPLIES_TO, listOf("project", "sidebar", "global")),
    PROGRESS_STALE(ConfigTab.GENERAL, PROGRESS_STALE_TITLE, PROGRESS_STALE_APPLIES_TO, listOf("progress", "stale", "minutes")),
    LIFECYCLE(ConfigTab.GENERAL, LifecycleSetting.TITLE, LifecycleSetting.APPLIES_TO, listOf("keep alive", "idle", "timeout")),
    POLICY(ConfigTab.GENERAL, POLICY_TITLE, POLICY_APPLIES_TO, listOf("policy", "preset", "read-only", "disabled", "actions", "production", "switch")),
    CLI_LOCATIONS(ConfigTab.AGENTS, CliLocationsSetting.TITLE, CliLocationsSetting.APPLIES_TO, listOf("harness", "install", "path", "cli")),
    TOOL_APPROVAL(ConfigTab.AGENTS, ToolApprovalSetting.TITLE, ToolApprovalSetting.APPLIES_TO, listOf("permission", "approve", "tools", "timer", "timeout", "prompt", "expiry", "indefinite")),
    ROLES(ConfigTab.AGENTS, ROLES_TITLE, ROLES_APPLIES_TO, listOf("role", "provider", "model", "traits")),
    LIMITS(ConfigTab.LIMITS, com.superiorsoftllc.infantry.ui.model.LimitsSetting.TITLE, com.superiorsoftllc.infantry.ui.model.LimitsSetting.APPLIES_TO, listOf("limit", "cap", "concurrency", "workers", "spawn", "depth", "timeout", "deadline", "watchdog", "threshold", "cpu", "build")),
    VOICE(ConfigTab.NOTIFICATIONS, VOICE_TITLE, com.superiorsoftllc.infantry.ui.voice.VOICE_APPLIES_TO, listOf("voice", "speech", "alert", "sound")),
    INCOMING_SOUND(ConfigTab.NOTIFICATIONS, com.superiorsoftllc.infantry.ui.sound.INCOMING_SOUND_TITLE, com.superiorsoftllc.infantry.ui.sound.INCOMING_SOUND_APPLIES_TO, listOf("sound", "volume", "mp3", "comms", "incoming", "audio")),
    QUESTIONS(ConfigTab.NOTIFICATIONS, TEST_QUESTION_TITLE, TEST_QUESTION_APPLIES_TO, listOf("question", "ask", "popup", "option", "menu", "test")),
    PLUGINS(ConfigTab.PLUGINS, com.superiorsoftllc.infantry.ui.voice.plugin.PluginsSetting.TITLE, com.superiorsoftllc.infantry.ui.voice.plugin.PluginsSetting.APPLIES_TO, listOf("plugin", "install", "voice", "piper", "natural", "download")),
    MODULES(ConfigTab.PLUGINS, com.superiorsoftllc.infantry.ui.plugin.ModulesSetting.TITLE, com.superiorsoftllc.infantry.ui.plugin.ModulesSetting.APPLIES_TO, listOf("plugin", "module", "community", "core", "extension")),
    REGISTRY(ConfigTab.PLUGINS, com.superiorsoftllc.infantry.ui.plugin.RegistrySetting.TITLE, com.superiorsoftllc.infantry.ui.plugin.RegistrySetting.APPLIES_TO, listOf("registered", "container", "node", "tab", "strip", "injection", "override")),
    SECRETS(ConfigTab.SECRETS, SECRETS_TITLE, SECRETS_APPLIES_TO, listOf("keystore", "token", "api key", "password")),
    PAIR(ConfigTab.DEVICES, PairEntry.TITLE, PairEntry.APPLIES_TO, listOf("qr", "phone", "android", "connect")),
    ABOUT(ConfigTab.ABOUT, AboutEntry.TITLE, AboutEntry.APPLIES_TO, listOf("build", "version", "commit")),
    UPDATES(ConfigTab.ABOUT, UpdatesEntry.TITLE, UpdatesEntry.APPLIES_TO, listOf("update", "version")),
    SERVER_UPDATE(ConfigTab.ABOUT, ServerUpdateEntry.TITLE, ServerUpdateEntry.APPLIES_TO, listOf("daemon", "server", "update")),
    LOGS(ConfigTab.ABOUT, LogsFolder.TITLE, LogsFolder.APPLIES_TO, listOf("log", "folder", "diagnostics")),
    ;

    companion object {
        /** The sections of [tab], in display order. */
        fun of(tab: ConfigTab): List<ConfigEntry> = entries.filter { it.tab == tab }
    }
}

/** CONFIG-5: the id of [entry]'s section block, `infantry.shell.config.<entry in lowerCamelCase>`; a `block` card names it. */
fun configSectionNode(entry: ConfigEntry): String =
    "infantry.shell.config." + entry.name.lowercase().split('_').mapIndexed { i, w -> if (i == 0) w else w.replaceFirstChar { it.uppercase() } }.joinToString("")

/** The tabs that have at least one of the [present] sections, in display order. */
fun visibleConfigTabs(present: Set<ConfigEntry>): List<ConfigTab> =
    ConfigTab.entries.filter { tab -> present.any { it.tab == tab } }

/** The tab to show: [wanted] when it has something to show, else the first visible tab (null when nothing shows). */
fun pickConfigTab(wanted: ConfigTab?, present: Set<ConfigEntry>): ConfigTab? {
    val visible = visibleConfigTabs(present)
    return if (wanted != null && wanted in visible) wanted else visible.firstOrNull()
}

/**
 * CONFIG-SEARCH-1 (ticket 3463d1b2, merged here): the [present] sections that match [query], in display order.
 * Every word of the query must occur, case-insensitively, in the section's title, applies-to copy, keywords or
 * tab label. A blank query matches nothing (the dialog shows its tabs instead).
 */
fun searchConfig(query: String, present: Set<ConfigEntry>): List<ConfigEntry> {
    val words = query.trim().lowercase().split(' ', '\t').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return ConfigEntry.entries.filter { it in present }.filter { e ->
        val hay = (listOf(e.title, e.applies, e.tab.label) + e.keywords).joinToString(" | ").lowercase()
        words.all { it in hay }
    }
}

const val AGENT_SCOPE_TITLE = "Agent scope"
const val BLUR_TITLE = "Blur behind dialogs"
const val PROGRESS_STALE_TITLE = "Progress bars: stale after"
const val VOICE_TITLE = "Spoken alerts"
const val ROLES_TITLE = "Roles"
const val ROLES_APPLIES_TO = "Define and edit roles: notes, default provider and model, traits. Applies to new sessions."

/** Test tags: a tab is CONFIG_TAB_TAG_PREFIX + [ConfigTab.name], a section CONFIG_SECTION_TAG_PREFIX + [ConfigEntry.name]. */
const val CONFIG_TAB_TAG_PREFIX = "config-tab-"
const val CONFIG_SECTION_TAG_PREFIX = "config-section-"
const val CONFIG_TAB_ROW_TAG = "config-tab-row"
const val CONFIG_TAB_BODY_TAG = "config-tab-body"
const val CONFIG_DIALOG_TAG = "config-dialog"
const val CONFIG_HEADER_ICON_TAG = "config-header-icon"
/** Ticket cb1ceb93: the configuration modal takes this share of the window's width, up to [CONFIG_DIALOG_MAX_WIDTH]. */
const val CONFIG_DIALOG_WIDTH_FRACTION = 0.92f
val CONFIG_DIALOG_MAX_WIDTH = 1040.dp
const val CONFIG_SEARCH_TAG = "config-search"
const val CONFIG_SEARCH_RESULTS_TAG = "config-search-results"
const val CONFIG_SEARCH_EMPTY_TAG = "config-search-empty"
/** A search result row is CONFIG_RESULT_TAG_PREFIX + [ConfigEntry.name]. */
const val CONFIG_RESULT_TAG_PREFIX = "config-result-"

/** CONFIG-SEARCH-1: how long a picked search result stays outlined, and the state it reports meanwhile. */
const val CONFIG_HIGHLIGHT_MS = 1800L
const val CONFIG_HIGHLIGHT_STATE = "highlighted"
