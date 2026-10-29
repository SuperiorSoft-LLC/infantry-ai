package com.superiorsoftllc.agenthq.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import com.superiorsoftllc.agenthq.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.plugin.config.NodeConfigLoad
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** SETTINGS-TAB-1: the configuration modal's "Tab groups" section copy. */
object TabGroupsSetting {
    const val TITLE = "Tab groups"
    const val APPLIES_TO = "This daemon. Draw a tab's group as sub-tabs under it (on), or keep them in the tab strip " +
        "with that tab hidden (off, the layout before the group). Stored by the daemon and applied at once."
}

/** One host's switch; the suffix is the host tab's id. */
const val TAB_GROUP_SWITCH_TAG_PREFIX = "tab-group-switch-"
/** The line saying the switches cannot be used now (loading, or the daemon's answer). */
const val TAB_GROUP_STATUS_TAG = "tab-group-status"

/**
 * SETTINGS-TAB-1: one switch per host tab ([TabFactory.hostTabs]), writing its `children.grouped` setting
 * ([TabGroups.GROUPED]) through [TabFactory.config]. Off while the stored copy is not loaded; a line says why.
 */
@Composable
fun TabGroupsSection(factory: TabFactory) {
    val hosts = factory.hostTabs()
    val config = factory.config
    val scope = rememberCoroutineScope()
    val ready = config.load == NodeConfigLoad.LOADED
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (hosts.isEmpty()) {
            Text("No tab groups: no loaded tab has sub-tabs.", color = Palette.Muted, fontSize = 12.sp)
            return@Column
        }
        val status = when {
            config.error != null -> "Not saved: ${config.error}"
            !ready -> "Loading the stored settings from the daemon..."
            else -> null
        }
        if (status != null) {
            Text(
                status, color = if (config.error != null) Palette.Danger else Palette.Muted, fontSize = 11.sp,
                modifier = Modifier.testTag(TAB_GROUP_STATUS_TAG).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        for (host in hosts) {
            val title = factory.title(host, com.superiorsoftllc.agenthq.ui.model.AppState())
            val members = factory.groupable(host.id).joinToString(", ") { factory.title(it, com.superiorsoftllc.agenthq.ui.model.AppState()).substringBefore(" ^").substringBefore(" (").trim() }
            val on = factory.grouped(host.id)
            val label = "Group $members under $title"
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = on,
                    onCheckedChange = { next -> scope.launch { config.set(host.id, TabGroups.GROUPED, JsonPrimitive(next)) } },
                    enabled = ready,
                    modifier = Modifier.testTag(TAB_GROUP_SWITCH_TAG_PREFIX + host.id).semantics { contentDescription = label },
                )
                Text(label, fontSize = 12.sp)
            }
        }
    }
}
