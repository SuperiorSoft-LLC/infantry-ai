package com.superiorsoftllc.agenthq.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.SectionLabel
import com.superiorsoftllc.agenthq.ui.block.layout.OverrideHealth

/** GAP-AUDIT-1 (c): the heading of the who-overrides-what list, and one row per override (suffix: `<module>/<mode>/<selector>`). */
const val OVERRIDE_MAP_TAG = "registry-override-map"
const val OVERRIDE_MAP_ROW_TAG_PREFIX = "registry-override-"
const val OVERRIDE_MAP_EMPTY_TAG = "registry-override-map-empty"

/**
 * GAP-AUDIT-1 (c): Settings > Plugins > Registered, read-only: which module overrides, wraps or places a block beside which
 * node, with the selector resolved to the node's path from the tree root, and whether it is drawn now; one that was
 * refused, ignored or that threw in the probe ([OverrideHealth]) says why.
 */
@Composable
fun OverrideMapList(factory: TabFactory) {
    val entries = remember(factory) { factory.overrideMap }
    val faults = OverrideHealth.faults
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel("Who overrides what", Palette.Muted, Modifier.testTag(OVERRIDE_MAP_TAG), topGap = false)
        if (entries.isEmpty()) {
            SectionLabel("No module overrides a node.", Palette.Muted, Modifier.testTag(OVERRIDE_MAP_EMPTY_TAG), topGap = false)
            return@Column
        }
        for (e in entries) {
            val fault = e.node?.let { node -> faults[faultKey(e, node)] }
            val state = when {
                fault != null -> "not drawn: it threw ($fault)"
                e.drawn -> "drawn"
                e.problem != null -> "not drawn: ${e.problem}"
                else -> "not drawn"
            }
            val where = e.path ?: "${e.selector} (no such node)"
            val tier = e.tier.name.lowercase()
            val verb = when (e.mode) { "before" -> "goes before"; "after" -> "goes after"; else -> e.mode + "s" }
            val line = "${e.module} ($tier) $verb $where, priority ${e.priority}: $state"
            SectionLabel(
                line, if (fault != null || !e.drawn) Palette.Danger else Palette.Text,
                Modifier.testTag(OVERRIDE_MAP_ROW_TAG_PREFIX + "${e.module}/${e.mode}/${e.selector}").semantics { contentDescription = line },
                topGap = false,
            )
        }
    }
}

/** The [OverrideHealth] key an entry's drawn block has: a sibling (before / after) is drawn as an injection. */
private fun faultKey(e: OverrideEntry, node: String): String =
    if (e.mode == "before" || e.mode == "after") OverrideHealth.key(e.module, "inject", "${e.module}.${e.mode}-$node")
    else OverrideHealth.key(e.module, e.mode, node)
