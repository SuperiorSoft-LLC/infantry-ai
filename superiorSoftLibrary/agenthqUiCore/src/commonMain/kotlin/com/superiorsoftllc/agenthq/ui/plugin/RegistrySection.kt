package com.superiorsoftllc.agenthq.ui.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.ui.Checkbox
import com.superiorsoftllc.agenthq.ui.Chip
import com.superiorsoftllc.agenthq.ui.TextButton
import com.superiorsoftllc.agenthq.ui.plugin.config.ChildrenControl
import com.superiorsoftllc.agenthq.ui.plugin.config.NodeConfigLoad
import com.superiorsoftllc.agenthq.ui.plugin.config.NodeFieldGroups
import kotlinx.coroutines.launch
import com.superiorsoftllc.agenthq.ui.FilterChip
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.SectionLabel

/** P1 (f60f4d97): Settings > Plugins > Registered. */
object RegistrySetting {
    const val TITLE = "Registered"
    const val APPLIES_TO = "This computer. Pick a node that hosts children (a tab strip, or a container of the window) to see " +
        "what is registered in it: each child's module and version, Core or Community, and whether it is a tab, a core " +
        "tree, an override, a wrap or an injection. Switch a tab or an injected block off or on, and reorder a tab strip; " +
        "stored by the daemon and applied at once. A locked node, the core tree as shipped and a strip's home tab are not editable."
}

/** A node in the picker; the suffix is the node id. */
const val REGISTRY_HOST_TAG_PREFIX = "registry-host-"
/** One child row; the suffix is the child id. */
const val REGISTRY_CHILD_TAG_PREFIX = "registry-child-"
const val REGISTRY_MODULE_TAG_PREFIX = "registry-module-"
const val REGISTRY_TIER_TAG_PREFIX = "registry-tier-"
const val REGISTRY_SOURCE_TAG_PREFIX = "registry-source-"
const val REGISTRY_STATE_TAG_PREFIX = "registry-state-"
const val REGISTRY_CHILD_LOCKED_TAG_PREFIX = "registry-child-locked-"
/** Shown above the list when the picked node is locked. */
const val REGISTRY_LOCKED_TAG = "registry-locked"
const val REGISTRY_EMPTY_TAG = "registry-empty"
/** P1 step 2: a child's Enabled checkbox, its move buttons, and why it is not editable; the suffix is the child id. */
const val REGISTRY_TOGGLE_TAG_PREFIX = "registry-toggle-"
const val REGISTRY_UP_TAG_PREFIX = "registry-up-"
const val REGISTRY_DOWN_TAG_PREFIX = "registry-down-"
const val REGISTRY_REFUSAL_TAG_PREFIX = "registry-refusal-"
/** P1 step 2: the heading of the picked node's config fields. */
const val REGISTRY_FIELDS_TAG = "registry-fields"
/** P1 step 2: the line saying the settings cannot be changed now (loading, or the daemon's answer). */
const val REGISTRY_STATUS_TAG = "registry-status"

/**
 * The node picker (the tab strips, then the window's containers) and the picked node's registered children, one row
 * each. P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s E): each editable child has an Enabled checkbox and, in a tab
 * strip, Up and Down; they write the node's settings through [TabFactory.config] (the daemon's node_config), and the
 * strip and the tree redraw from them at once. While the stored copy is not loaded the controls are off and a line says why.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegistrySection(factory: TabFactory) {
    val config = factory.config
    val stored = config.stored
    val hosts = remember(factory, stored) { NodeRegistry.of(factory) }
    var picked by remember(factory) { mutableStateOf(hosts.firstOrNull()?.id) }
    val host = hosts.firstOrNull { it.id == picked }
    // P1 step 2 (6s A): a node with config fields that hosts no children is still pickable, for its fields.
    val configured = factory.configTree.fields.keys.filter { id -> hosts.none { it.id == id } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, kind) in listOf("Tab strips" to HostKind.TABS, "Window nodes" to HostKind.TREE)) {
            val group = hosts.filter { it.kind == kind }
            if (group.isEmpty()) continue
            SectionLabel(label, Palette.Muted, topGap = false)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (h in group) {
                    FilterChip(
                        selected = h.id == picked, onClick = { picked = h.id },
                        label = { SectionLabel(h.title, if (h.locked) Palette.Muted else Palette.Text, topGap = false) },
                        modifier = Modifier.testTag(REGISTRY_HOST_TAG_PREFIX + h.id),
                    )
                }
            }
        }
        val ready = config.load == NodeConfigLoad.LOADED
        val status = when {
            config.error != null -> "Not saved: ${config.error}"
            config.load == NodeConfigLoad.LOADING || config.load == NodeConfigLoad.IDLE -> "Loading the stored settings from the daemon..."
            else -> null
        }
        if (status != null) {
            SectionLabel(
                status, if (config.error != null) Palette.Danger else Palette.Muted,
                Modifier.testTag(REGISTRY_STATUS_TAG).semantics { liveRegion = LiveRegionMode.Polite }, topGap = false,
            )
        }
        if (configured.isNotEmpty()) {
            SectionLabel("Configured nodes", Palette.Muted, topGap = false)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (id in configured) {
                    FilterChip(
                        selected = id == picked, onClick = { picked = id },
                        label = { SectionLabel(id.removePrefix("infantry.shell."), topGap = false) },
                        modifier = Modifier.testTag(REGISTRY_HOST_TAG_PREFIX + id),
                    )
                }
            }
        }
        if (host != null) HostChildren(factory, host, ready)
        val node = picked
        val exporter = factory.sectionExporter
        if (node != null && exporter != null) {
            var exported by remember(node) { mutableStateOf<String?>(null) }
            TextButton({ exported = exporter(node) }, Modifier.testTag(REGISTRY_EXPORT_TAG)) { Text("Export section…") }
            factory.freshShellLauncher?.let { fresh ->
                TextButton({ exported = fresh(node) }, Modifier.testTag(REGISTRY_FRESH_SHELL_TAG)) { Text("Try in a fresh shell") }
            }
            exported?.let { SectionLabel(it, Palette.Muted, Modifier.testTag(REGISTRY_EXPORT_RESULT_TAG), topGap = false) }
        }
        val fields = node?.let { factory.configTree.fields[it] }.orEmpty()
        if (node != null && fields.isNotEmpty()) {
            val locked = node in com.superiorsoftllc.agenthq.ui.shell.blocks.CoreShell.locked
            SectionLabel("Fields", topGap = false, modifier = Modifier.testTag(REGISTRY_FIELDS_TAG))
            NodeFieldGroups(factory, node, fields, editable = ready && !locked, locked = locked)
        }
        BlockTypeList(factory)
    }
}

/** NS3: the picked node's "Export section…" button and the line it answers with (file written, or the refusals). */
const val REGISTRY_EXPORT_TAG = "registry-export"
const val REGISTRY_EXPORT_RESULT_TAG = "registry-export-result"
/** NS10: "Try in a fresh shell" (export, then start the shell-only process on it). */
const val REGISTRY_FRESH_SHELL_TAG = "registry-fresh-shell"

/** NS1 / PB1: the registered block types, one chip each (title, type, module); the suffix of the tag is the type. */
const val REGISTRY_BLOCK_TYPE_TAG_PREFIX = "registry-block-type-"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockTypeList(factory: TabFactory) {
    val types = factory.blockTypes.types.values
    if (types.isEmpty()) return
    SectionLabel("Block types", Palette.Muted, topGap = false)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in types) {
            Chip("${t.declaration.title} (${t.name}, ${t.module})", textModifier = Modifier.testTag(REGISTRY_BLOCK_TYPE_TAG_PREFIX + t.name))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostChildren(factory: TabFactory, host: HostNode, ready: Boolean) {
    val scope = rememberCoroutineScope()
    val config = factory.config
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        SectionLabel(host.title, topGap = false)
        Chip(host.id)
        Chip("${host.children.size} registered")
        if (host.locked) Chip("Locked: no module may change it", Palette.Danger, textModifier = Modifier.testTag(REGISTRY_LOCKED_TAG))
    }
    if (host.children.isEmpty()) {
        SectionLabel("Nothing is registered here.", Palette.Muted, Modifier.testTag(REGISTRY_EMPTY_TAG), topGap = false)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val order = host.children.filter { it.loaded }.map { it.id }
        val reorder = ChildrenControl.reorderable(host)
        for (c in host.children) {
            val refusal = ChildrenControl.refusal(factory, host, c)
            val at = order.indexOf(c.id)
            ChildRow(
                c, refusal, ready,
                onEnabled = if (refusal == null) { on -> scope.launch { ChildrenControl.setEnabled(config, host.id, c.id, on) } } else null,
                onMove = if (reorder && c.loaded) { delta -> scope.launch { ChildrenControl.move(config, host.id, order, c.id, delta) } } else null,
                first = at <= 0, last = at == order.lastIndex,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChildRow(
    c: RegisteredChild,
    refusal: String?,
    ready: Boolean,
    onEnabled: ((Boolean) -> Unit)?,
    onMove: ((Int) -> Unit)?,
    first: Boolean,
    last: Boolean,
) {
    FlowRow(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(REGISTRY_CHILD_TAG_PREFIX + c.id),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(c.name, if (c.enabled) Palette.Text else Palette.Muted, topGap = false)
        if (c.name != c.id) Chip(c.id)
        Chip(listOfNotNull(c.module, c.version).joinToString(" "), textModifier = Modifier.testTag(REGISTRY_MODULE_TAG_PREFIX + c.id))
        val community = c.tier == Tier.COMMUNITY
        Chip(if (community) "Community" else "Core", if (community) Palette.Accent else Palette.Muted, textModifier = Modifier.testTag(REGISTRY_TIER_TAG_PREFIX + c.id))
        Chip(c.source.label + (c.by?.let { " of $it" } ?: ""), textModifier = Modifier.testTag(REGISTRY_SOURCE_TAG_PREFIX + c.id))
        c.order?.let { Chip("Order $it") }
        if (c.locked) Chip("Locked", Palette.Danger, textModifier = Modifier.testTag(REGISTRY_CHILD_LOCKED_TAG_PREFIX + c.id))
        Chip(if (c.enabled) "Enabled" else "Disabled", if (c.enabled) Palette.Accent else Palette.Muted, textModifier = Modifier.testTag(REGISTRY_STATE_TAG_PREFIX + c.id))
        if (onEnabled != null) {
            Checkbox(
                c.enabled, onEnabled, enabled = ready,
                modifier = Modifier.testTag(REGISTRY_TOGGLE_TAG_PREFIX + c.id).semantics { contentDescription = "Draw ${c.name}" },
            )
        } else if (refusal != null && !c.locked) {
            Chip("Not editable: $refusal", textModifier = Modifier.testTag(REGISTRY_REFUSAL_TAG_PREFIX + c.id))
        }
        if (onMove != null) {
            TextButton({ onMove(-1) }, Modifier.testTag(REGISTRY_UP_TAG_PREFIX + c.id).semantics { contentDescription = "Move ${c.name} up" }, enabled = ready && !first) { Text("Up") }
            TextButton({ onMove(1) }, Modifier.testTag(REGISTRY_DOWN_TAG_PREFIX + c.id).semantics { contentDescription = "Move ${c.name} down" }, enabled = ready && !last) { Text("Down") }
        }
    }
}
