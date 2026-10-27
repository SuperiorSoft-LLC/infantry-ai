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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.ui.Chip
import com.superiorsoftllc.agenthq.ui.FilterChip
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.SectionLabel

/** P1 (f60f4d97): Settings > Plugins > Registered. */
object RegistrySetting {
    const val TITLE = "Registered"
    const val APPLIES_TO = "This computer. Pick a node that hosts children (a tab strip, or a container of the window) to see " +
        "what is registered in it: each child's module and version, Core or Community, and whether it is a tab, a core " +
        "tree, an override, a wrap or an injection. Read-only; a locked node can never be changed by a module."
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

/**
 * The node picker (the tab strips, then the window's containers) and the picked node's registered children, one row
 * each. Read-only: step 2 (docs/BLOCKS-LAYOUT-DESIGN.md section 6s) adds the controls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegistrySection(factory: TabFactory) {
    val hosts = remember(factory) { NodeRegistry.of(factory) }
    var picked by remember(factory) { mutableStateOf(hosts.firstOrNull()?.id) }
    val host = hosts.firstOrNull { it.id == picked }
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
        if (host != null) HostChildren(host)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostChildren(host: HostNode) {
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
        for (c in host.children) ChildRow(c)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChildRow(c: RegisteredChild) {
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
    }
}
