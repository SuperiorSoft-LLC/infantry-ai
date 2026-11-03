// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.superiorsoftllc.infantry.plugin.host.PluginTree
import com.superiorsoftllc.infantry.ui.plugin.config.NodeConfigLoad
import com.superiorsoftllc.infantry.ui.plugin.config.NodeFieldGroups
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.DependencyGraph
import com.superiorsoftllc.infantry.plugin.host.ModuleChange
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.ui.Checkbox
import com.superiorsoftllc.infantry.ui.Chip
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton

/** PLUGINS-1: Settings > Plugins > Modules. */
object ModulesSetting {
    const val TITLE = "Modules"
    const val APPLIES_TO = "This computer. Every module found under the plugins root, and whether it loaded. Community modules " +
        "stay off until modules.json enables them; the Community label is a trust label, not a sandbox: a loaded module runs " +
        "inside the app with its full access. Enable or disable a module here (or edit plugins/modules.json) and restart."
}

/** PLUGINS-2: enable ([enable] true) or disable [module]; [cascade] also disables the enabled modules that need it. */
data class ModuleRequest(val module: String, val enable: Boolean, val cascade: Boolean = false)

/** PLUGINS-2: the host's way to change modules.json; the dependency rules decide, the answer says what happened. */
fun interface ModuleChanges {
    fun request(request: ModuleRequest): ModuleChange
}

/** One module's card (the tag kept its PLUGINS-1 "row" name). */
const val MODULE_ROW_TAG_PREFIX = "plugin-module-"
const val MODULE_TIER_TAG_PREFIX = "plugin-module-tier-"
const val MODULE_STATE_TAG_PREFIX = "plugin-module-state-"

/** HARNESS-PLUGIN-4 H5a: a module card's harness row (icon, ids, the daemon's state of it). */
const val MODULE_HARNESS_TAG_PREFIX = "plugin-module-harness-"
const val MODULE_BYLINE_TAG_PREFIX = "plugin-module-byline-"
const val MODULE_REASON_TAG_PREFIX = "plugin-module-reason-"
const val MODULE_NEEDS_TAG_PREFIX = "plugin-module-needs-"
const val MODULE_REQUIRED_BY_TAG_PREFIX = "plugin-module-required-by-"
const val MODULE_TOGGLE_TAG_PREFIX = "plugin-module-toggle-"
const val MODULE_MESSAGE_TAG_PREFIX = "plugin-module-message-"
/** S7: Remove on an installed community module (outside safe mode; the soft restart follows). */
const val MODULE_REMOVE_TAG_PREFIX = "plugin-module-remove-"
const val MODULE_EDIT_TAG_PREFIX = "plugin-module-edit-"
const val MODULE_USED_BY_TAG_PREFIX = "plugin-module-used-by-"
const val MODULE_CRUMBS_TAG = "plugin-crumbs"
const val MODULE_CRUMB_TAG_PREFIX = "plugin-crumb-"
const val MODULE_BACK_TAG = "plugin-crumb-back"
const val MODULE_CASCADE_TAG = "plugin-modules-cascade"
const val MODULE_CHILDREN_TAG_PREFIX = "plugin-module-children-"
const val MODULE_NO_SETTINGS_TAG = "plugin-no-settings"
const val MODULE_CHILD_TAG_PREFIX = "plugin-module-child-"
const val MODULE_EMPTY_TAG = "plugin-modules-empty"

/** A card is never narrower than this; the grid takes as many columns as fit, up to [MaxColumns]. */
private val CardMinWidth = 260.dp
private val CardGap = 8.dp
private const val MaxColumns = 3

/**
 * One card per module (ticket cb1ceb93), in as many columns as the width allows: name, version, tier, author and
 * license, what it needs and what needs it, why it is not loaded, and its state. With [TabFactory.changes] each card
 * can be enabled or disabled; a refusal is shown on the card, in the rules' own words.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModulesSection(
    factory: TabFactory,
    /** HARNESS-PLUGIN-4 H5a: the daemon's harness state by module (`GET /plugins` `harness`); null = not read. */
    harnessStatus: Map<String, String>? = null,
) {
    val graph = remember(factory) { DependencyGraph.of(factory.statuses) }
    // What this session changed in modules.json (it applies at the next start), and the last answer per module.
    val enabledNow = remember(factory) { mutableStateMapOf<String, Boolean>() }
    val messages = remember(factory) { mutableStateMapOf<String, ModuleChange>() }
    var cascade by remember(factory) { mutableStateOf(false) }
    val changes = factory.changes
    // S7: a community module installed from a .ihq can be removed here, in a normal run: its jar is closed first.
    val dropIn = com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost.current.dropIn?.takeIf { !it.lockdown }
    val restart = com.superiorsoftllc.infantry.ui.shell.LocalSoftRestart.current
    val removeNotes = remember(factory) { mutableStateMapOf<String, String>() }

    val tree = remember(factory) { PluginTree(factory.statuses.mapNotNull { it.manifest }) }
    // The path of plugins stepped into; empty = the top level. The same view is drawn at every level.
    var path by remember(factory) { mutableStateOf(emptyList<String>()) }
    val here = path.lastOrNull()
    val shown = if (here == null) tree.roots() else tree.childrenOf(here)
    val visible = factory.statuses.filter { it.name in shown }
    val ready = factory.config.load == NodeConfigLoad.LOADED
    Column(
        Modifier.onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && path.isNotEmpty()) { path = path.dropLast(1); true } else false
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (path.isNotEmpty()) {
            // Stepping in must be seen: the section may be scrolled far below its top, so bring the crumbs into view.
            val crumbsView = remember { BringIntoViewRequester() }
            LaunchedEffect(path) { crumbsView.bringIntoView() }
            Row(Modifier.bringIntoViewRequester(crumbsView).testTag(MODULE_CRUMBS_TAG), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ path = emptyList() }, Modifier.testTag(MODULE_CRUMB_TAG_PREFIX + "root")) { Text("Plugins") }
                path.forEachIndexed { i, n ->
                    Text(">", color = Palette.Muted, fontSize = 12.sp)
                    TextButton({ path = path.take(i + 1) }, Modifier.testTag(MODULE_CRUMB_TAG_PREFIX + n), enabled = i < path.lastIndex) { Text(n) }
                }
                Spacer(Modifier.weight(1f))
                TextButton({ path = path.dropLast(1) }, Modifier.testTag(MODULE_BACK_TAG)) { Text("Back") }
            }
            // This plugin's own configuration: its module.json config fields, through the same field view at every level.
            val nodes = factory.statuses.firstOrNull { it.name == here }?.manifest?.config?.map { it.node }?.distinct().orEmpty()
            var anyFields = false
            for (node in nodes) {
                val fields = factory.configTree.fields[node].orEmpty()
                if (fields.isNotEmpty()) { anyFields = true; NodeFieldGroups(factory, node, fields, editable = ready, locked = false) }
            }
            if (!anyFields) Text("No settings", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag(MODULE_NO_SETTINGS_TAG))
        }
        if (factory.statuses.isEmpty()) {
            Text("No modules were found under the plugins root.", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag(MODULE_EMPTY_TAG))
        } else if (changes != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(cascade, { cascade = it }, Modifier.testTag(MODULE_CASCADE_TAG))
                Text("When disabling, also disable the modules that need it (cascade)", color = Palette.Text, fontSize = 12.sp)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = columnsFor(maxWidth)
            Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
                for (row in visible.chunked(columns)) {
                    // Intrinsic height: the cards of one row stretch to the tallest, so their footers line up.
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                        for (s in row) {
                            ModuleCard(
                                s, graph,
                                enabled = enabledNow[s.name] ?: (s.state != ModuleState.DISABLED),
                                message = messages[s.name],
                                removeNote = removeNotes[s.name],
                                harnessStatus = harnessStatus?.get(s.name),
                                onRemove = dropIn?.takeIf { s.tier == Tier.COMMUNITY && s.name in it.installed() }?.let { d ->
                                    { val e = d.remove(s.name); if (e != null) removeNotes[s.name] = e; restart?.invoke() }
                                },
                                parents = tree.parentsOf(s.name),
                                children = tree.childrenOf(s.name).toList().sorted(),
                                onOpenChild = { c -> path = path + s.name + c },
                                onEdit = { path = path + s.name },
                                onToggle = changes?.let { c ->
                                    { on: Boolean ->
                                        val refusal = if (on) null else tree.disableRefusal(s.name) { p ->
                                            enabledNow[p] ?: (factory.statuses.firstOrNull { it.name == p }?.state != ModuleState.DISABLED)
                                        }
                                        if (refusal != null) { messages[s.name] = ModuleChange.Refused(refusal); return@let }
                                        val answer = c.request(ModuleRequest(s.name, on, cascade))
                                        messages[s.name] = answer
                                        if (answer is ModuleChange.Applied) for (n in answer.changed) enabledNow[n] = on
                                    }
                                },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        for (p in factory.problems) Text(p, color = Palette.Muted, fontSize = 11.sp)
    }
}

private fun columnsFor(width: Dp): Int = ((width + CardGap) / (CardMinWidth + CardGap)).toInt().coerceIn(1, MaxColumns)

/** The module's state in plain words. */
private fun stateLabel(state: ModuleState): String = when (state) {
    ModuleState.LOADED -> "Loaded"
    ModuleState.DISABLED -> "Disabled"
    ModuleState.LOCKED -> "Locked"
    ModuleState.REFUSED -> "Refused"
}

/** H5a: `Harness: echo · loaded` (the daemon's state), or just the ids when it was not read. */
internal fun harnessLine(ids: List<String>, status: String?): String =
    "Harness: " + ids.joinToString(", ") + (status?.let { " · daemon: $it" }.orEmpty())

/** "By <author> · License <license>", from whichever of the two the manifest gives; null when it gives neither. */
internal fun moduleByline(m: ModuleManifest): String? =
    listOfNotNull(m.author?.takeIf { it.isNotBlank() }?.let { "By $it" }, m.license?.takeIf { it.isNotBlank() }?.let { "License $it" })
        .takeIf { it.isNotEmpty() }?.joinToString(" · ")

@Composable
private fun ModuleCard(s: ModuleStatus, graph: DependencyGraph, enabled: Boolean, message: ModuleChange?, removeNote: String?, onRemove: (() -> Unit)?, onToggle: ((Boolean) -> Unit)?, modifier: Modifier, harnessStatus: String? = null, parents: List<String> = emptyList(), children: List<String> = emptyList(), onOpenChild: (String) -> Unit = {}, onEdit: () -> Unit = {}) {
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp).testTag(MODULE_ROW_TAG_PREFIX + s.name),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Name and version take the free width; only a name too long for the whole card is cut.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    s.name, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                s.manifest?.version?.let { Text(it, color = Palette.Muted, fontSize = 11.sp, maxLines = 1) }
            }
            val community = s.tier == Tier.COMMUNITY
            Chip(if (community) "Community" else "Core", if (community) Palette.Accent else Palette.Muted, textModifier = Modifier.testTag(MODULE_TIER_TAG_PREFIX + s.name))
        }
        s.manifest?.let(::moduleByline)?.let { Text(it, Modifier.testTag(MODULE_BYLINE_TAG_PREFIX + s.name), color = Palette.Muted, fontSize = 11.sp) }
        s.manifest?.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = Palette.Text, fontSize = 11.sp) }
        // HARNESS-PLUGIN-4 H5a: the harness the module ships, from its manifest, and the daemon's state of it when read.
        s.manifest?.harness?.takeIf { it.ids.isNotEmpty() }?.let { h ->
            Row(
                Modifier.testTag(MODULE_HARNESS_TAG_PREFIX + s.name),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                com.superiorsoftllc.infantry.ui.HarnessIcon(h.ids.first(), size = 12)
                Text(harnessLine(h.ids, harnessStatus), color = if (harnessStatus?.startsWith("loaded") == false) Palette.Danger else Palette.Muted, fontSize = 11.sp)
            }
        }
        val needs = graph.needs(s.name)
        if (needs.isNotEmpty()) {
            Text("Needs: " + needs.joinToString(", ") { DependencyGraph.label(it) }, Modifier.testTag(MODULE_NEEDS_TAG_PREFIX + s.name), color = Palette.Muted, fontSize = 11.sp)
        }
        if (parents.isNotEmpty()) {
            Text("Used by: " + parents.joinToString(", "), Modifier.testTag(MODULE_USED_BY_TAG_PREFIX + s.name), color = Palette.Muted, fontSize = 11.sp)
        }
        if (children.isNotEmpty()) {
            Row(Modifier.testTag(MODULE_CHILDREN_TAG_PREFIX + s.name), verticalAlignment = Alignment.CenterVertically) {
                Text("Children: ", color = Palette.Muted, fontSize = 11.sp)
                children.forEachIndexed { i, c ->
                    if (i > 0) Text(", ", color = Palette.Muted, fontSize = 11.sp)
                    TextButton({ onOpenChild(c) }, Modifier.testTag(MODULE_CHILD_TAG_PREFIX + c), contentPadding = PaddingValues(horizontal = 2.dp)) { Text(c, fontSize = 11.sp) }
                }
            }
        }
        val requiredBy = graph.requiredBy(s.name)
        if (requiredBy.isNotEmpty()) {
            Text("Required by: " + requiredBy.joinToString(", "), Modifier.testTag(MODULE_REQUIRED_BY_TAG_PREFIX + s.name), color = Palette.Muted, fontSize = 11.sp)
        }
        s.reason?.let {
            Text(it, Modifier.testTag(MODULE_REASON_TAG_PREFIX + s.name), color = if (s.state == ModuleState.REFUSED) Palette.Danger else Palette.Muted, fontSize = 11.sp)
        }
        message?.let {
            Text(
                it.message,
                Modifier.testTag(MODULE_MESSAGE_TAG_PREFIX + s.name).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (it is ModuleChange.Refused) Palette.Danger else Palette.Ok,
                fontSize = 11.sp,
            )
        }
        removeNote?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
        // The state and its control sit at the foot of the card, level across a row of cards.
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(stateLabel(s.state), if (s.state == ModuleState.LOADED) Palette.Accent else Palette.Muted, textModifier = Modifier.testTag(MODULE_STATE_TAG_PREFIX + s.name))
            Spacer(Modifier.weight(1f))
            if (s.manifest != null && (children.isNotEmpty() || s.manifest?.config?.isNotEmpty() == true || parents.isNotEmpty())) TextButton(
                onClick = onEdit,
                modifier = Modifier.height(28.dp).testTag(MODULE_EDIT_TAG_PREFIX + s.name),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Edit", fontSize = 12.sp) }
            if (onRemove != null) TextButton(
                onClick = onRemove,
                modifier = Modifier.height(28.dp).testTag(MODULE_REMOVE_TAG_PREFIX + s.name),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Remove", fontSize = 12.sp) }
            if (onToggle != null && s.manifest != null) {
                TextButton(
                    onClick = { onToggle(!enabled) },
                    modifier = Modifier.height(28.dp).testTag(MODULE_TOGGLE_TAG_PREFIX + s.name),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text(if (enabled) "Disable" else "Enable", fontSize = 12.sp) }
            } else {
                // Same height as the button, so a card without a control keeps the row's footer level.
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}
