package com.superiorsoftllc.agenthq.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.ui.Chip
import com.superiorsoftllc.agenthq.ui.Palette

const val COMMUNITY_BADGE_TAG = "plugin-community-badge"

/** The line naming a community module's author and license; the same text the badge and Settings > Plugins show. */
fun communityByline(m: ModuleManifest): String = "${m.name} ${m.version} by ${m.author.orEmpty()} - license ${m.license.orEmpty()}"

/** PLUGINS-1: marks everything a community module draws; a trust label, not a sandbox. */
@Composable
fun CommunityBadge(manifest: ModuleManifest, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag(COMMUNITY_BADGE_TAG),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip("Community", Palette.Accent)
        Text(communityByline(manifest), color = Palette.Muted, fontSize = 11.sp)
    }
}
