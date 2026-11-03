package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.RoleIcons

/** ROLE-ICON-1: a role icon's tag is `role-icon:<where>:<roleId>`, so a test can find it on each surface. */
const val ROLE_ICON_TAG_PREFIX = "role-icon:"

/** Where a role icon is painted; part of its tag. */
object RoleIconSite {
    const val ROW = "row"
    const val DETAIL = "detail"
    const val PICKER = "picker"
    const val ASSIGNEE = "assignee"
    const val FILTER = "filter"
    /** ROLE-PILL-ICONS-1: the Switch agent picker's role pills. */
    const val SWITCH = "switch"
}

fun roleIconTag(site: String, roleId: String): String = "$ROLE_ICON_TAG_PREFIX$site:$roleId"

/** Semantics: the icon key a [RoleIcon] resolved to ([RoleIcons.resolve]), so a test asserts the glyph, not a pixel. */
val RoleIconKey = SemanticsPropertyKey<String>("RoleIconKey")
var SemanticsPropertyReceiver.roleIconKey by RoleIconKey

/**
 * ROLE-ICON-1: THE icon of a role, next to its name wherever a role is shown or picked. [icon] is the role's stored
 * key (contract 1.93.0); blank or unknown paints the default for [roleId]'s kind. Decorative: the role's name sits
 * beside it, so it adds no content description.
 */
@Composable
fun RoleIcon(roleId: String?, icon: String?, site: String, size: Int = 14, tint: Color = Palette.Muted, modifier: Modifier = Modifier) {
    val key = RoleIcons.resolve(roleId, icon)
    Icon(
        AhqRoleIcons.forRole(roleId, icon), contentDescription = null, tint = tint,
        modifier = modifier.size(size.dp).testTag(roleIconTag(site, roleId.orEmpty())).semantics { roleIconKey = key },
    )
}

/** The same icon for a role known only by id: its stored key comes from the daemon catalog in [state]. */
@Composable
fun RoleIcon(roleId: String, state: AppState, site: String, size: Int = 14, tint: Color = Palette.Muted, modifier: Modifier = Modifier) =
    RoleIcon(roleId, state.role(roleId)?.icon, site, size, tint, modifier)
