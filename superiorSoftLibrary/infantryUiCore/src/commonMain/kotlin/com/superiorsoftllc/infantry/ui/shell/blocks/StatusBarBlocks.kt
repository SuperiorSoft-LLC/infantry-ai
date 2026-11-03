// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell.blocks

import androidx.compose.foundation.background
import com.superiorsoftllc.infantry.ui.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.AhqIcons
import com.superiorsoftllc.infantry.ui.ConfigTab
import com.superiorsoftllc.infantry.ui.FooterBuild
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.LocalShell
import com.superiorsoftllc.infantry.ui.SERVER_MISMATCH_TAG
import com.superiorsoftllc.infantry.ui.ago
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.Place
import com.superiorsoftllc.infantry.ui.block.layout.PlaceholderBlock
import com.superiorsoftllc.infantry.ui.block.layout.RenderPlaced
import com.superiorsoftllc.infantry.ui.block.layout.SpacerBlock
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * BLOCKS-LAYOUT L2 (docs/BLOCKS-LAYOUT-DESIGN.md section 3): the status bar as a `statusBar` layout block. Its frame is
 * the divider plus the 24dp Panel row; the bar's items are ONE ordered array of children, placed by `place.weight`.
 * RUNTIME-18 (F2): the bar is the shell's own (core draws its connection status and config cog), no longer a slot the app
 * UI fills; its processes badge is an empty slot ([PROCESSES_BADGE_SLOT]) the official Infantry_Processes fills.
 */
class StatusBarBlock(id: String, children: List<Block> = emptyList(), place: Place = Place()) :
    Block("statusBar", id, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        HorizontalDivider(color = Palette.Border)
        Row(
            scope.modifier.fillMaxWidth().height(24.dp).background(Palette.Panel).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { RenderPlaced(children) }
    }

    override fun withChildren(children: List<Block>) = StatusBarBlock(id, children, place)
}

/** RUNTIME-18: the status bar's node id (plugin API since L5; the shell's own since RUNTIME-18). */
const val FOOTER_NODE = "infantry.shell.footer"

/** RUNTIME-18: the status bar's processes-badge slot, empty until the official Infantry_Processes fills it. */
const val PROCESSES_BADGE_SLOT = "$FOOTER_NODE.processesBadge"

/** "● daemon X contract Y" / "● state: detail". */
object StatusConnStatus : Block("connStatus", "$FOOTER_NODE.connStatus", Place(weight = 1f, weightFill = false)) {
    @Composable
    override fun Render(scope: BlockScope) {
        val shell = LocalShell.current
        val c = shell.state.connection
        val color = if (c.isLive) Palette.Ok else if (c.state == ConnState.RECONNECTING || c.state == ConnState.CONNECTING) Palette.Accent else Palette.Danger
        // A compact window has no width for the sentence: it would be chopped mid-word ("● dae"), so show the dot (live) or the bare state.
        val compact = shell.state.panels.compactWindow
        Text(
            when {
                compact -> if (c.isLive) "●" else "● ${c.state.name.lowercase()}"
                c.isLive -> "● daemon ${c.daemonProvider ?: "?"}  contract ${c.contract ?: "?"}"
                else -> "● ${c.state.name.lowercase()}: ${c.detail}"
            },
            color = color, fontSize = 11.sp, maxLines = 1, softWrap = false, modifier = scope.modifier,
        )
    }
}

/** Owns the 1 s ticker, so only this text recomposes each second. */
object StatusLastFrame : Block("lastFrame", "$FOOTER_NODE.lastFrame") {
    @Composable
    override fun Render(scope: BlockScope) {
        var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
        LaunchedEffect(Unit) { while (true) { delay(1000); now = Clock.System.now().toEpochMilliseconds() } }
        LocalShell.current.state.connection.lastFrameAt?.let {
            Text("   last frame ${ago(it, now)} ago", color = Palette.Muted, fontSize = 11.sp, modifier = scope.modifier)
        }
    }
}

object StatusContractWarning : Block("contractWarning", "$FOOTER_NODE.contractWarning") {
    @Composable
    override fun Render(scope: BlockScope) {
        val link = (LocalShellContext.current ?: return).link
        if (link.live && link.contract?.substringBefore('.') != "1") Text("   unexpected contract version", color = Palette.Danger, fontSize = 11.sp, modifier = scope.modifier)
    }
}

/** SERVER-UPDATE-4: client and server differ by more than a patch; the full text is in Configuration > Server. */
object StatusVersionMismatch : Block("versionMismatch", "$FOOTER_NODE.versionMismatch") {
    @Composable
    override fun Render(scope: BlockScope) {
        val ctx = LocalShellContext.current ?: return
        if (ctx.link.live && ctx.window.serverMismatch != null) {
            Text("   version mismatch (see Configuration)", color = Palette.Accent, fontSize = 11.sp, modifier = scope.modifier.testTag(SERVER_MISMATCH_TAG))
        }
    }
}

/**
 * ROGUE-1's processes badge (rogue background processes / low memory): RUNTIME-18, an empty slot the official
 * Infantry_Processes fills (on `ShellContext.processes`). No jar, no badge.
 */
val StatusProcessesBadge: Block = PlaceholderBlock(PROCESSES_BADGE_SLOT, null)

/** The shortcut hint only where there is room for it (a phone has no keyboard shortcuts and no width to spare). */
object StatusShortcutHint : Block("shortcutHint", "$FOOTER_NODE.shortcutHint", Place(weight = 1f, weightFill = false)) {
    @Composable
    override fun Render(scope: BlockScope) {
        if (LocalShellContext.current?.layout?.compact == false) {
            Text("^K search  Alt+Up/Down agents  ^J needs input  ^N new  ^R resume  ^5 roles  ^6 attention  ^L connection  ^, config", color = Palette.Muted, fontSize = 10.sp, maxLines = 1, modifier = scope.modifier)
        }
    }
}

/** BUILD-STAMP-1: which build this is; a click opens Configuration > About. */
object StatusBuildStamp : Block("buildStamp", "$FOOTER_NODE.buildStamp") {
    @Composable
    override fun Render(scope: BlockScope) {
        val ctx = LocalShellContext.current ?: return
        // Compact windows have no width to spare: the stamp would crowd out the config cog, which must stay reachable.
        if (ctx.layout.compact) return
        val link = ctx.link
        FooterBuild(
            ctx.window.appBuild, if (link.live) link.daemonBuild else null,
            scope.modifier.padding(start = 10.dp).clickable(role = Role.Button) { ctx.window.openConfig(ConfigTab.ABOUT) },
        )
    }
}

/** The footer cog, bottom-right. It opens the configuration modal (flags). */
object StatusConfigCog : Block("configCog", "$FOOTER_NODE.configCog") {
    @Composable
    override fun Render(scope: BlockScope) {
        val shell = LocalShellContext.current ?: return
        val window = shell.window
        // DEMO-MERGE-1 / POLICY-1: the cog follows the `settings.save` action (the daemon's policy, a module's write policy)
        // and says why when off; the owner who may switch the policy always reaches it (the Policy card is in there).
        val gate = com.superiorsoftllc.infantry.ui.shell.rememberGate("settings.save")
        // Not the link: the configuration opens offline too; only a refusal (a reason) locks it.
        val locked = gate.reason != null && !(shell.link.live && shell.link.policy.canSwitch)
        Box(
            scope.modifier.padding(start = 10.dp).size(24.dp)
                .clickable(enabled = !locked, role = Role.Button) { window.openConfig() }
                .semantics { contentDescription = if (locked) gate.reason ?: "Configuration" else "Configuration" },
            contentAlignment = Alignment.Center,
        ) { Icon(AhqIcons.Cog, contentDescription = null, tint = if (locked) Palette.Border else Palette.Muted, modifier = Modifier.size(14.dp)) }
    }
}

/** The status bar's items, left to right. */
val CoreStatusBar = StatusBarBlock(
    FOOTER_NODE,
    listOf(
        StatusConnStatus, StatusLastFrame, StatusContractWarning, StatusVersionMismatch, StatusProcessesBadge,
        SpacerBlock("$FOOTER_NODE.gap", place = Place(weight = 1f)),
        StatusShortcutHint, StatusBuildStamp, StatusConfigCog,
    ),
)
