// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.roles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.Chip
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.protocol.Role

/** ROLE-PLUGIN-1: the role editor's preferred cards / tools fields. */
const val ROLE_EDITOR_CARDS_TAG = "role-editor-cards"
const val ROLE_EDITOR_TOOLS_TAG = "role-editor-tools"

/** ROLE-PLUGIN-1: the chip of a role a plugin declared, and of one whose plugin is not loaded (test tag prefixes + role id). */
const val ROLE_PLUGIN_CHIP_TAG_PREFIX = "role-plugin-chip-"
const val ROLE_DISABLED_CHIP_TAG_PREFIX = "role-disabled-chip-"

/**
 * ROLE-PLUGIN-1: the chips of a plugin role: "plugin" (the module named in its tooltip-free label), "edited" once the owner saved
 * it, and "disabled" while its plugin is not loaded. Nothing for a role no plugin declared.
 */
@Composable
fun RolePluginChips(role: Role) {
    if (role.plugin.isEmpty()) return
    Chip("plugin: ${role.plugin}", Palette.Accent, modifier = Modifier.testTag(ROLE_PLUGIN_CHIP_TAG_PREFIX + role.id))
    if (role.edited) Chip("edited", Palette.Info)
    if (role.disabled) Chip("disabled", Palette.Muted, modifier = Modifier.testTag(ROLE_DISABLED_CHIP_TAG_PREFIX + role.id))
}

/** ROLE-PLUGIN-1: one line under a plugin role's title: where it came from and what an edit or an uninstall does. */
@Composable
fun RolePluginNote(role: Role?) {
    if (role == null || role.plugin.isEmpty()) return
    val text = when {
        role.disabled -> "Disabled: the plugin ${role.plugin} is not installed or not enabled. The role is kept; reinstalling the plugin enables it again."
        role.edited -> "From the plugin ${role.plugin}. You edited it, so plugin updates never change it."
        else -> "From the plugin ${role.plugin}. Plugin updates refresh it until you edit it; your edit is never overwritten."
    }
    Text(text, color = Palette.Muted, fontSize = 10.sp)
}

/** `a, b ,c` -> [a, b, c]. */
fun preferenceIds(text: String): List<String> = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * ROLE-PLUGIN-1: a role's preferred card types and tool names (ids from any plugin, comma separated). They shape what a new
 * session of the role is told at start; they never gate a tool or a card. Disabled against a daemon below contract 1.153.0.
 */
@Composable
fun RolePreferenceFields(cards: String, tools: String, enabled: Boolean, onCards: (String) -> Unit, onTools: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (enabled) "Preferred cards and tools, by id from any plugin (comma separated). Named at session start while their plugin is loaded; they never restrict anything."
            else "Preferred cards and tools need a daemon on contract 1.153.0 or newer.",
            color = Palette.Muted, fontSize = 10.sp,
        )
        OutlinedTextField(cards, onCards, label = { Text("Cards (e.g. news)") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(ROLE_EDITOR_CARDS_TAG))
        OutlinedTextField(tools, onTools, label = { Text("Tools (e.g. infantry_ticket_get, news.headlines)") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(ROLE_EDITOR_TOOLS_TAG))
    }
}
