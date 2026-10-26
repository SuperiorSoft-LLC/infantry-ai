package com.superiorsoftllc.agenthq.ui.plugin

import androidx.compose.foundation.background
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
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.DependencyGraph
import com.superiorsoftllc.agenthq.plugin.host.ModuleChange
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.ui.Checkbox
import com.superiorsoftllc.agenthq.ui.Chip
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.TextButton

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
const val MODULE_BYLINE_TAG_PREFIX = "plugin-module-byline-"
const val MODULE_REASON_TAG_PREFIX = "plugin-module-reason-"
const val MODULE_NEEDS_TAG_PREFIX = "plugin-module-needs-"
const val MODULE_REQUIRED_BY_TAG_PREFIX = "plugin-module-required-by-"
const val MODULE_TOGGLE_TAG_PREFIX = "plugin-module-toggle-"
const val MODULE_MESSAGE_TAG_PREFIX = "plugin-module-message-"
const val MODULE_CASCADE_TAG = "plugin-modules-cascade"
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
@Composable
fun ModulesSection(factory: TabFactory) {
    val graph = remember(factory) { DependencyGraph.of(factory.statuses) }
    // What this session changed in modules.json (it applies at the next start), and the last answer per module.
    val enabledNow = remember(factory) { mutableStateMapOf<String, Boolean>() }
    val messages = remember(factory) { mutableStateMapOf<String, ModuleChange>() }
    var cascade by remember(factory) { mutableStateOf(false) }
    val changes = factory.changes

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                for (row in factory.statuses.chunked(columns)) {
                    // Intrinsic height: the cards of one row stretch to the tallest, so their footers line up.
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                        for (s in row) {
                            ModuleCard(
                                s, graph,
                                enabled = enabledNow[s.name] ?: (s.state != ModuleState.DISABLED),
                                message = messages[s.name],
                                onToggle = changes?.let { c ->
                                    { on: Boolean ->
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

/** "By <author> · License <license>", from whichever of the two the manifest gives; null when it gives neither. */
internal fun moduleByline(m: ModuleManifest): String? =
    listOfNotNull(m.author?.takeIf { it.isNotBlank() }?.let { "By $it" }, m.license?.takeIf { it.isNotBlank() }?.let { "License $it" })
        .takeIf { it.isNotEmpty() }?.joinToString(" · ")

@Composable
private fun ModuleCard(s: ModuleStatus, graph: DependencyGraph, enabled: Boolean, message: ModuleChange?, onToggle: ((Boolean) -> Unit)?, modifier: Modifier) {
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
        val needs = graph.needs(s.name)
        if (needs.isNotEmpty()) {
            Text("Needs: " + needs.joinToString(", ") { DependencyGraph.label(it) }, Modifier.testTag(MODULE_NEEDS_TAG_PREFIX + s.name), color = Palette.Muted, fontSize = 11.sp)
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
        // The state and its control sit at the foot of the card, level across a row of cards.
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(stateLabel(s.state), if (s.state == ModuleState.LOADED) Palette.Accent else Palette.Muted, textModifier = Modifier.testTag(MODULE_STATE_TAG_PREFIX + s.name))
            Spacer(Modifier.weight(1f))
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
