// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.agents

import com.superiorsoftllc.infantry.ui.shell.gate
import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.protocol.TicketColumns
import com.superiorsoftllc.infantry.ui.model.agentWork
import androidx.compose.runtime.LaunchedEffect
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.shell.NoAgents
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.superiorsoftllc.infantry.ui.ActiveBorder
import com.superiorsoftllc.infantry.ui.ActiveBorderStyle
import com.superiorsoftllc.infantry.ui.activeBorder
import com.superiorsoftllc.infantry.ui.activeBorderStyle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.superiorsoftllc.infantry.ui.model.AgentGroup
import com.superiorsoftllc.infantry.ui.model.parseHexColor
import com.superiorsoftllc.infantry.ui.model.roleHue
import com.superiorsoftllc.infantry.ui.model.reportFlagOf
import com.superiorsoftllc.infantry.ui.model.watchFlagOf
import com.superiorsoftllc.infantry.ui.model.wakeLine
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.needsInput
import com.superiorsoftllc.infantry.ui.plugin.PanelSlot
import com.superiorsoftllc.infantry.ui.plugin.Panels
import kotlin.time.Clock

// S4c: the left panel's agent list and the centre's tab strip, split out of core App.kt (docs/SHELL-LEAKS.md).
/**
 * UX-POLISH-1: the sidebar's spacing token, the 10dp every sidebar block is inset by. Sections (Board, search, the
 * role chips) are this far apart vertically, so they read as separate blocks.
 */
val SidebarSpacing = 10.dp

/** UX-POLISH-1: the sidebar's agent search field. */
const val SIDEBAR_SEARCH_TAG = "sidebar-search"

/** UI-ARROWS-1: the role filter chip row, its scroll arrows and each chip ("all", then one per role). */
const val ROLE_FILTER_ROW_TAG = "role-filter-row"
const val ROLE_FILTER_ARROW_LEFT_TAG = "role-filter-arrow-left"
const val ROLE_FILTER_ARROW_RIGHT_TAG = "role-filter-arrow-right"
const val ROLE_FILTER_CHIP_TAG_PREFIX = "role-filter-chip-"

/**
 * The sidebar's role filter: "all", then a chip per role of the listed agents (none without agents). Overflowing
 * chips scroll sideways behind the tab strips' arrows (UI-ARROWS-1), one press about one chip.
 */
@Composable
fun RoleFilterChips(state: AppState, roleFilter: String?, onRoleFilter: (String?) -> Unit) {
    val roles = state.agents.values.mapNotNull { it.role }.distinct().sorted()
    if (roles.isEmpty()) return
    ArrowScrollRow(
        ROLE_FILTER_ARROW_LEFT_TAG, ROLE_FILTER_ARROW_RIGHT_TAG,
        Modifier.padding(start = 10.dp, end = 10.dp, top = SidebarSpacing, bottom = 6.dp),
        leftLabel = "Scroll roles left", rightLabel = "Scroll roles right", step = ArrowStep.fixed(72.dp),
        contentTag = ROLE_FILTER_ROW_TAG, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Chip("all", if (roleFilter == null) Palette.Accent else Palette.Muted, Modifier.testTag(ROLE_FILTER_CHIP_TAG_PREFIX + "all").clickable { onRoleFilter(null) })
        // ROLE-ICON-1: each role filter carries the role's icon ("all" names no role, so it has none).
        roles.forEach { r ->
            RoleChip(r, r, state.role(r)?.icon, RoleIconSite.FILTER, if (roleFilter == r) Palette.Accent else Palette.Muted, Modifier.testTag(ROLE_FILTER_CHIP_TAG_PREFIX + r).clickable { onRoleFilter(if (roleFilter == r) null else r) })
        }
    }
}

@Composable
fun Sidebar(
    state: AppState,
    visible: List<AgentView>,
    query: String,
    onQuery: (String) -> Unit,
    roleFilter: String?,
    onRoleFilter: (String?) -> Unit,
    searchFocus: FocusRequester,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRemove: (String) -> Unit,
    onAssign: (String, String) -> Unit,
    projects: @Composable () -> Unit,
    /** RUNTIME-16: whether the official Infantry_Review tab is loaded; without it there is no "To review" counter. */
    showReview: Boolean = true,
    /** AGENT-TASKS-7: what the cards do with tickets (assign by drop or menu, queue actions, open). */
    work: AgentWorkActions = AgentWorkActions.None,
) {
    // PLUGINS-3: the frame (width, background, tag) is the left panel's; this is Infantry_Agents' section in it.
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Counter("Needs input", state.needsInputCount, if (state.needsInputCount > 0) Palette.Accent else Palette.Muted)
            if (showReview) Counter("To review", state.reviewQueue.size, Palette.Info)
            Counter("Working", state.workingCount, Palette.Ok)
        }
        // AR-7: the left.board slot; Infantry_Board's Board button (order 0) and any module's panel section draw here.
        PanelSlot(Panels.LEFT_BOARD)
        projects()
        // UX-POLISH-1: the Board row and the search are separate sections, a spacing token either side of the divider
        // (the Board row already carries 4dp of outer padding below it).
        HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(top = SidebarSpacing - 4.dp, bottom = SidebarSpacing))
        // The tag sits on a wrapper: BasicTextField reports its semantics bounds inside its own 8dp padding.
        Box(Modifier.padding(horizontal = 10.dp).fillMaxWidth().testTag(SIDEBAR_SEARCH_TAG)) {
            BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 12.sp), cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus)
                    .clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(8.dp),
                decorationBox = { inner -> if (query.isEmpty()) Text("Search agents...  ^K", color = Palette.Muted, fontSize = 12.sp); inner() },
            )
        }
        RoleFilterChips(state, roleFilter, onRoleFilter)
        HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(top = 6.dp))
        if (visible.isEmpty()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (state.agents.isEmpty()) "No agents yet." else "No agent matches the filter.",
                    color = Palette.Muted, fontSize = 12.sp,
                )
                if (state.agents.isEmpty() && state.connection.gate("agents.spawn", com.superiorsoftllc.infantry.plugin.api.ActionKind.SPAWN).enabled) {
                    TextButton(onClick = onNew) { Text("Spawn one (Ctrl+N)") }
                }
            }
        }
        AgentsList(state, visible, onSelect, onRemove, Modifier.weight(1f), onAssign, work)
        HorizontalDivider(color = Palette.Border)
        // UX-ICON-1: the same New agent icon as the composer, with its label (the sidebar has the room).
        // DEMO-MERGE-1: dimmed, with the reason as its tooltip, while the write policy refuses writes.
        val policy = state.connection.gate("agents.spawn", com.superiorsoftllc.infantry.plugin.api.ActionKind.SPAWN)
        val writeLocked = !policy.allowed
        IconAction(
            AhqIcons.PersonAdd, policy.label(NEW_AGENT_LABEL), onNew, Modifier.fillMaxWidth().testTag(SIDEBAR_NEW_AGENT_TAG),
            shortcut = NEW_AGENT_SHORTCUT, style = IconActionStyle.TEXT, enabled = !writeLocked,
            colors = ButtonDefaults.textButtonColors(contentColor = Palette.Accent), text = NEW_AGENT_LABEL, textColor = if (writeLocked) Palette.Muted else Palette.Accent,
        )
    }
}

/**
 * M2-4: the agents list, grouped by status ([AgentGroup]: Waiting on you, Ready to check, Running, Quiet),
 * each group under a header with its count; empty groups are not shown. Public so UI tests render the real list.
 */
@Composable
fun AgentsList(state: AppState, visible: List<AgentView>, onSelect: (String) -> Unit, onRemove: (String) -> Unit, modifier: Modifier = Modifier, onAssign: (String, String) -> Unit = { _, _ -> }, work: AgentWorkActions = AgentWorkActions.None) {
    // BUG-UX-SHIFT-1: while any pointer is down on the list, keep the rows where they were when it went down. A session
    // changing group mid-click (BUSY -> IDLE takes the WORKING header with it) would move the row under the pointer, the
    // release would land off the 18dp "⋯" button and the click would be dropped. The fresh grouping lands on release.
    var pointerDown by remember { mutableStateOf(false) }
    val lastShown = remember { arrayOfNulls<List<Pair<AgentGroup, List<AgentView>>>>(1) }
    val fresh = state.groupedAgents(visible)
    val groups = lastShown[0]?.takeIf { pointerDown } ?: fresh.also { lastShown[0] = it }
    LazyColumn(
        modifier.pointerInput(Unit) {
            // Initial pass, consumes nothing: clicks, drag-scroll and the wheel behave exactly as before.
            awaitPointerEventScope { while (true) pointerDown = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed } }
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        groups.forEachIndexed { i, (group, rows) ->
            item(key = "h-${group.name}") {
                // UX-POLISH-1: a divider where one status section ends and the next begins (e.g. above INACTIVE).
                Column {
                    if (i > 0) HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(top = 4.dp).testTag(AGENT_GROUP_DIVIDER_TAG_PREFIX + group.name))
                    AgentGroupHeader(group, rows.size)
                }
            }
            items(rows, key = { it.id }) { a ->
                AgentRow(a, state, a.id == state.selectedId, { onSelect(a.id) }, onRemove, Modifier.padding(bottom = 6.dp), onAssign, work)
            }
        }
    }
}

/** UX-POLISH-1: the divider above every status group but the first, suffixed with the group's name. */
const val AGENT_GROUP_DIVIDER_TAG_PREFIX = "agent-group-divider-"

@Composable
private fun AgentGroupHeader(group: AgentGroup, count: Int) {
    val color = when (group) {
        AgentGroup.NEEDS_YOU -> Palette.Accent
        AgentGroup.TO_REVIEW -> Palette.Info
        AgentGroup.WORKING -> Palette.Ok
        AgentGroup.INACTIVE -> Palette.Muted
    }
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(group.label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.testTag("agent-group-${group.name}"))
        Spacer(Modifier.width(6.dp))
        Text(
            "$count", color = Palette.Muted, fontSize = 10.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Raised).padding(horizontal = 5.dp).testTag("agent-group-count-${group.name}"),
        )
    }
}

@Composable
private fun Counter(label: String, n: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$n", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.width(4.dp))
        Text(label, color = Palette.Muted, fontSize = 10.sp)
    }
}

/**
 * One agent session's card in the agents list (M2-4): the portrait with the role badge on its
 * corner; the role name in bold; the role in its colour + a short status/last-activity snippet; the elapsed time
 * and a small "⋯" row menu (M2-5) on the right. The selected card gets an orange border. Public so UI tests render it.
 */
@Composable
fun AgentRow(a: AgentView, state: AppState, selected: Boolean, onClick: () -> Unit, onRemove: (String) -> Unit, modifier: Modifier = Modifier, onAssign: (String, String) -> Unit = { _, _ -> }, work: AgentWorkActions = AgentWorkActions.None) {
    val waiting = needsInput(a, state.waiting, state.openPrompts, state.openQuestionSessions)
    val now = Clock.System.now().toEpochMilliseconds()
    val roleColor = a.role?.let { r -> parseHexColor(state.role(r)?.color)?.let { Color(it) } ?: Color.hsv(roleHue(r), 0.45f, 0.72f) } ?: Palette.Muted
    val shape = RoundedCornerShape(8.dp)
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    // BG-ACTIVE-2: a pulsing border while a turn or a background job runs; the clock is shared by every card.
    val activeStyle = a.session.activeBorderStyle().takeIf { ActiveBorder.enabled } ?: ActiveBorderStyle.NONE
    if (activeStyle != ActiveBorderStyle.NONE && ActiveBorder.animate) {
        DisposableEffect(Unit) { ActiveBorder.acquire(); onDispose { ActiveBorder.release() } }
    }
    val profile = a.session.agentId
    // AGENT-TASKS-7: ask for the profile's queue whenever the board changes (a start, a finish, a queued drop).
    if (profile != null) LaunchedEffect(profile, state.tickets, state.connection.isLive) { if (state.connection.isLive) work.queue(profile, "list", null, null) }
    Row(
        modifier.fillMaxWidth().hoverable(hoverSource).clip(shape)
            .then(if (profile != null) Modifier.dropTarget("ticket") { work.assign(it.id, profile) } else Modifier)
            .background(if (selected) Palette.Raised else Palette.Bg)
            .then(if (activeStyle != ActiveBorderStyle.NONE) Modifier.activeBorder(activeStyle, shape)
                  else Modifier.border(if (selected) 1.5.dp else 1.dp, if (selected) Palette.Accent else Palette.Border, shape))
            .clickable(onClick = onClick).testTag("agent-row-${a.id}")
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp)) {
            AgentAvatar(a, state, 40)
            // The role badge on the portrait's corner: the role's first letter on its colour, ringed by the card.
            Box(
                Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(if (selected) Palette.Raised else Palette.Bg)
                    .padding(2.dp).clip(CircleShape).background(if (a.role == null) Palette.Raised else roleColor).testTag("agent-row-badge-${a.id}"),
                contentAlignment = Alignment.Center,
            ) { Text((a.role ?: "?").take(1).uppercase(), color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            // State dot on the opposite corner: orange when it needs you, blue working, green idle, red failed.
            Box(
                Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape).background(if (selected) Palette.Raised else Palette.Bg)
                    .padding(2.dp).clip(CircleShape).background(stateColor(a.session.state, waiting)),
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.agentTitle(a), color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("agent-row-title-${a.id}"))
                // TRAITS-T9: the REPORT BACK flag, on the sidebar session row.
                reportFlagOf(a.session.report)?.let { Spacer(Modifier.width(4.dp)); ReportFlagChip(it, Modifier.testTag("session-report-${a.id}")) }
                // PIPELINE-WATCHDOG: stalled / quiet / unreported / waiting, from the daemon's own derivation.
                watchFlagOf(a.session.watch)?.let { Spacer(Modifier.width(4.dp)); WatchFlagChip(it, Modifier.testTag("session-watch-${a.id}")) }
                // SCRIPT-VIS-1c: a script hook works for this session (set only while it runs; display only).
                a.session.automation?.let { Spacer(Modifier.width(4.dp)); AutomationBadge(it.script, Modifier.testTag(AUTOMATION_BADGE_TAG_PREFIX + a.id)) }
                // PROMPT-2: a prompt card waits on this session.
                if (state.hasOpenPrompt(a.id)) NeedsYouBadge(a.id)
                // BUG-M2-15b: a session with no project is listed under every project; say so on the row.
                if (a.session.projectId.isNullOrBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        NO_PROJECT_LABEL, color = Palette.Muted, fontSize = 9.sp, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, Palette.Border, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp).testTag(SESSION_NO_PROJECT_TAG_PREFIX + a.id),
                    )
                }
            }
            // AR-7b: badges modules contribute (the ticket tag is Infantry_Board's); a click anywhere on the row selects the agent.
            SessionRowBadges(a, state)
            // Contract 1.117.0: the agent's own progress report on its long job, as a slim live bar.
            a.session.progress?.let { ProgressMeter(it, "row-${a.id}", Modifier.padding(top = 3.dp), compact = true) }
            // STALL-2: the daemon's watch state for this session (watching X, watch OFF: reason, busy silent, stalled).
            a.session.watchLabel?.takeIf { it.isNotBlank() }?.let { label ->
                val alarm = label.startsWith("stalled") || label.startsWith("watch OFF") || label.startsWith("busy, silent")
                Text(
                    label, color = if (alarm) Palette.Accent else Palette.Muted, fontSize = 10.sp, maxLines = 1,
                    softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp).testTag("agent-row-watch-${a.id}"),
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                a.role?.let { Text(it, color = roleColor, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.testTag("agent-row-role-${a.id}")); Spacer(Modifier.width(5.dp)) }
                // M2-3: the session id stays visible as small secondary text.
                state.agentSecondaryId(a)?.let { Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 9.sp, maxLines = 1, modifier = Modifier.testTag("agent-row-id-${a.id}")); Spacer(Modifier.width(5.dp)) }
                Text(
                    if (waiting) "waiting for you" else listOfNotNull(a.lastActivity.takeIf { it.isNotBlank() }, state.wakeLine(a.id)).joinToString(" · "),
                    color = if (waiting) Palette.Accent else Palette.Muted, fontSize = 11.sp, maxLines = 1,
                    softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("agent-row-snippet-${a.id}"),
                )
            }
            if (profile != null) AgentWorkBlock(a.id, state.agentWork(profile), work, profile)
        }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(ago(a.session.lastHeartbeat, now), color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.testTag("agent-row-time-${a.id}"))
            // M2-5: the row's actions live behind a small "⋯" button, shown on hover/selection (kept in the tree
            // so keyboard and screen readers still reach it). Remove goes through a confirm dialog.
            AgentRowMenu(a, state, revealed = selected || hovered, onOpen = onClick, onRemove = onRemove, onAssign = onAssign, work = work)
        }
    }
}

/** SCRIPT-VIS-1c: test tag prefix of the automation badge on a session row. */
const val AUTOMATION_BADGE_TAG_PREFIX = "session-automation-"

/** SCRIPT-VIS-1c: the small "AUTO" chip on a session a script hook is working for; [script] is its tooltip-style text for screen readers. */
@Composable
fun AutomationBadge(script: String, modifier: Modifier = Modifier) {
    Text(
        "⚙ AUTO", color = Palette.Info, fontSize = 9.sp, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, Palette.Info, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
            .semantics { contentDescription = "Automation running: $script" },
    )
}

/** BUG-M2-15b: the "no project" marker on an unbound session's row, and its row-menu "Assign to <project>" item. */
const val NO_PROJECT_LABEL = "no project"
const val SESSION_NO_PROJECT_TAG_PREFIX = "session-no-project-"
const val SESSION_ASSIGN_TAG_PREFIX = "session-assign-"

/**
 * M2-5: an agent card's "⋯" row menu (Open, Remove). Remove never deletes directly: it opens a confirm dialog
 * ("Remove <role name>? This can't be undone.") whose destructive Remove button is the only path to [onRemove];
 * Cancel, Esc or a click outside leave the agent alone. Disabled while disconnected, like every command; a running
 * session is killed first by Controller.removeSession.
 */
@Composable
private fun AgentRowMenu(a: AgentView, state: AppState, revealed: Boolean, onOpen: () -> Unit, onRemove: (String) -> Unit, onAssign: (String, String) -> Unit, work: AgentWorkActions) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val live = state.connection.isLive
    // GAP-AUDIT-1 (g): the menu opens while live (Open is a read); each write in it has its own declared action and gate.
    val canOpen = live
    val focusSource = remember { MutableInteractionSource() }
    val focused by focusSource.collectIsFocusedAsState()
    Box {
        Box(
            Modifier.padding(top = 4.dp).size(width = 22.dp, height = 18.dp).clip(RoundedCornerShape(4.dp))
                .alpha(if (revealed || menuOpen || focused) 1f else 0f)
                .clickable(interactionSource = focusSource, indication = null, enabled = canOpen, role = Role.Button, onClickLabel = "Agent actions") { menuOpen = true }
                .semantics { contentDescription = "Agent actions" }
                .testTag("session-menu-${a.id}"),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.material3.Icon(AhqIcons.MoreHoriz, contentDescription = null, tint = if (live) Palette.Muted else Palette.Border, modifier = Modifier.size(14.dp)) }
        DropdownMenu(
            expanded = menuOpen, onDismissRequest = { menuOpen = false },
            modifier = Modifier.background(Palette.Raised).testTag("session-menu-popup-${a.id}"),
        ) {
            DropdownMenuItem(
                text = { Text("Open", color = Palette.Text, fontSize = 12.sp) },
                onClick = { menuOpen = false; onOpen() },
                modifier = Modifier.testTag("session-menu-open-${a.id}"),
            )
            // CLI-PTY-5: a dormant session (the daemon restarted since it ran) resumes from its card too: attach, no turn.
            if (com.superiorsoftllc.infantry.ui.showsResumeSession(a.session)) {
                val resumeGate = state.connection.gate("chat.resume")
                WritePolicyTooltip { m ->
                    DropdownMenuItem(
                        text = { Text(com.superiorsoftllc.infantry.ui.RESUME_SESSION_LABEL, color = if (resumeGate.enabled && live) Palette.Accent else Palette.Border, fontSize = 12.sp) },
                        onClick = { menuOpen = false; work.resume(a.id) },
                        enabled = resumeGate.enabled && live,
                        modifier = m.testTag(com.superiorsoftllc.infantry.ui.RESUME_SESSION_MENU_TAG_PREFIX + a.id),
                    )
                }
            }
            // BUG-M2-15b: only an unbound session, and only while a project is selected: bind it to that project.
            val target = state.activeProject
            if (a.session.projectId.isNullOrBlank() && target != null) {
                val assign = state.connection.gate("agents.project.set")
                WritePolicyTooltip { m ->
                    DropdownMenuItem(
                        text = { Text("Assign to ${target.name}", color = if (assign.enabled) Palette.Text else Palette.Border, fontSize = 12.sp) },
                        onClick = { menuOpen = false; onAssign(a.id, target.id) },
                        enabled = assign.enabled,
                        modifier = m.testTag(SESSION_ASSIGN_TAG_PREFIX + a.id),
                    )
                }
            }
            // AGENT-TASKS-7: the phone route to a drop: pick a todo ticket to hand this agent (start if free, else queue).
            // AGENT-MENU-1: the current ticket as one line plus one action; the todo list lives in a picker dialog, never inline.
            val profile = a.session.agentId
            if (profile != null) {
                HorizontalDivider(color = Palette.Border)
                AgentMenuTicketItems(a.id, state.agentWork(profile).current, onChange = { menuOpen = false; picking = true }, onUnassign = { id -> menuOpen = false; work.unassign(id) })
            }
            HorizontalDivider(color = Palette.Border)
            // DEMO-MERGE-1: the delete is a write; Remove is dimmed and says why while writes are refused.
            val policy = state.connection.gate("agents.delete", com.superiorsoftllc.infantry.plugin.api.ActionKind.DESTRUCTIVE)
            val writeLocked = !policy.allowed
            WritePolicyTooltip { m ->
                DropdownMenuItem(
                    text = { Text("Remove", color = if (writeLocked) Palette.Border else Palette.Danger, fontSize = 12.sp) },
                    onClick = { menuOpen = false; confirming = true },
                    enabled = !writeLocked,
                    modifier = m.testTag("session-remove-${a.id}"),
                )
            }
        }
    }
    val pickProfile = a.session.agentId
    if (picking && pickProfile != null) TicketPickerDialog(
        a.id, state.tickets.filter { it.status == TicketColumns.TODO && (state.activeProjectId == null || it.projectId == state.activeProjectId) },
        onDismiss = { picking = false }, onPick = { picking = false; work.assign(it, pickProfile) },
    )
    if (confirming) RemoveAgentDialog(a, state, onDismiss = { confirming = false }, onRemove = { confirming = false; onRemove(a.id) })
}



/**
 * RUNTIME-21: the left panel's agents section, drawn from the window's [AgentsContext] (the shell keeps the panel frame).
 * Draws nothing where there is no window.
 */
@Composable
fun AgentsSection() {
    val a = LocalShellContext.current?.agents ?: NoAgents
    val state = a.state ?: return
    val scope = rememberCoroutineScope()
    Sidebar(
        state, a.visible, a.query, a::setQuery, a.roleFilter, a::setRoleFilter, a.searchFocus,
        onSelect = a::select,
        onNew = a::newAgent,
        onRemove = { id -> scope.launch { a.remove(id) } },
        onAssign = { id, projectId -> scope.launch { a.assign(id, projectId) } },
        work = AgentWorkActions(
            assign = { ticketId, agentId -> scope.launch { a.assignTicket(ticketId, agentId) } },
            queue = { agentId, action, ticketId, at -> scope.launch { a.agentQueue(agentId, action, ticketId, at) } },
            open = a::openTicket,
            unassign = { id -> scope.launch { a.unassignTicket(id) } },
            resume = { id -> scope.launch { a.resumeSession(id) } },
        ),
        projects = { ProjectsSidebar(state, onDismiss = a::dismissProjectRefusal) },
        showReview = a.reviewLoaded,
    )
}


private fun shortTicketId(id: String) = id.removePrefix("ticket-").take(8)

private fun isHeld(t: com.superiorsoftllc.infantry.ui.protocol.Ticket) =
    t.heldBy.isNotEmpty() || t.holdReason != null || t.title.startsWith("[HOLD]") || t.title.startsWith("[PARKED]")

/**
 * AGENT-MENU-1: the ticket picker: search box + scrollable list of todo tickets (short id, title); Up/Down move, Enter picks, Esc closes.
 * [HOLD]/[PARKED] tickets are marked and hidden unless the toggle is on. Rows are 48dp tall; the dialog is near full width on phones.
 */
@Composable
private fun TicketPickerDialog(agentKey: String, todo: List<com.superiorsoftllc.infantry.ui.protocol.Ticket>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) { TicketPickerPanel(agentKey, todo, onDismiss, onPick) }
}

@Composable
fun TicketPickerPanel(agentKey: String, todo: List<com.superiorsoftllc.infantry.ui.protocol.Ticket>, onDismiss: () -> Unit, onPick: (String) -> Unit, initialQuery: String = "", initialShowHeld: Boolean = false) {
    var query by remember { mutableStateOf(initialQuery) }
    var showHeld by remember { mutableStateOf(initialShowHeld) }
    var sel by remember { mutableStateOf(0) }
    val shown = todo.filter { (showHeld || !isHeld(it)) && (query.isBlank() || it.title.contains(query, true) || it.id.contains(query, true)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
        Column(
            Modifier.fillMaxWidth(0.94f).widthIn(max = 520.dp).heightIn(max = 560.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Raised).border(1.dp, Palette.Border, RoundedCornerShape(10.dp))
                .padding(12.dp).testTag("ticket-picker-$agentKey")
                .onPreviewKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false else when (e.key) {
                        Key.DirectionDown -> { sel = (sel + 1).coerceAtMost((shown.size - 1).coerceAtLeast(0)); true }
                        Key.DirectionUp -> { sel = (sel - 1).coerceAtLeast(0); true }
                        Key.Enter, Key.NumPadEnter -> { shown.getOrNull(sel)?.let { onPick(it.id) }; true }
                        Key.Escape -> { onDismiss(); true }
                        else -> false
                    }
                },
        ) {
            Text("Pick a ticket", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            BasicTextField(
                value = query, onValueChange = { query = it; sel = 0 }, singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 13.sp), cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(40.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Palette.Border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp).focusRequester(focus).testTag("ticket-picker-search-$agentKey"),
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search todo tickets", color = Palette.Muted, fontSize = 13.sp); inner() } },
            )
            Row(
                Modifier.clickable(role = Role.Switch, onClickLabel = "Show held and parked") { showHeld = !showHeld; sel = 0 }.heightIn(min = 48.dp).testTag("ticket-picker-held-$agentKey"),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text(if (showHeld) "[x] Show HOLD / PARKED" else "[ ] Show HOLD / PARKED", color = Palette.Muted, fontSize = 12.sp) }
            if (shown.isEmpty()) Text("No matching todo tickets", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(shown.size) { i ->
                    val t = shown[i]
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(6.dp)).background(if (i == sel) Palette.Border else Color.Transparent)
                            .clickable { onPick(t.id) }.padding(horizontal = 8.dp).testTag("ticket-picker-item-${t.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(shortTicketId(t.id), color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(t.title, color = if (isHeld(t)) Palette.Muted else Palette.Text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (isHeld(t)) Text("HOLD", color = Palette.Danger, fontSize = 9.sp, modifier = Modifier.padding(start = 6.dp))
                        t.weight?.let { w -> Text("W$w", color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp).testTag("ticket-picker-weight-${t.id}")) }
                    }
                }
            }
        }
}

/** AGENT-TASKS-7: the card's ticket actions, supplied by the window: [assign] ticket to agent profile, [queue] (agent, action, ticket, place), [open] a ticket. */
class AgentWorkActions(
    val assign: (ticketId: String, agentId: String) -> Unit,
    val queue: (agentId: String, action: String, ticketId: String?, at: Int?) -> Unit,
    val open: (ticketId: String) -> Unit,
    val unassign: (ticketId: String) -> Unit = {},
    /** CLI-PTY-5: "Resume session" on a dormant agent's card. */
    val resume: (sessionId: String) -> Unit = {},
) {
    companion object { val None = AgentWorkActions({ _, _ -> }, { _, _, _, _ -> }, { }) }
}

/**
 * AGENT-TASKS-7: an agent card's work: the ticket it is running (title, opens its detail) and its queue head first,
 * each entry with move up / move down / remove. Empty: one muted line, so a drop target never looks like nothing.
 */
@Composable
private fun AgentWorkBlock(id: String, work: com.superiorsoftllc.infantry.ui.model.AgentWork, actions: AgentWorkActions, profile: String) {
    Column(Modifier.padding(top = 3.dp)) {
        val cur = work.current
        if (cur != null) Text(
            "on: ${cur.title}", color = Palette.Text, fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { actions.open(cur.id) }.testTag("agent-row-current-$id"),
        ) else if (work.queue.isEmpty()) Text("no ticket - drop one here", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.testTag("agent-row-idle-$id"))
        work.queue.forEachIndexed { i, q ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("agent-row-queued-$id-${q.ticketId}")) {
                Text("${i + 1}. ${q.title}", color = Palette.Muted, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (i > 0) QueueButton("Move up", "▲") { actions.queue(profile, "move", q.ticketId, i) }
                if (i < work.queue.size - 1) QueueButton("Move down", "▼") { actions.queue(profile, "move", q.ticketId, i + 2) }
                QueueButton("Remove from queue", "✕") { actions.queue(profile, "remove", q.ticketId, null) }
            }
        }
    }
}

@Composable
private fun QueueButton(label: String, glyph: String, onClick: () -> Unit) {
    Text(
        glyph, color = Palette.Muted, fontSize = 10.sp,
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onClick).semantics { contentDescription = label }.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** AGENT-MENU-1: the menu's ticket block: the current ticket as one line (or "No ticket"), Change/Assign, and Unassign while one is running. */
@Composable
fun AgentMenuTicketItems(key: String, cur: com.superiorsoftllc.infantry.ui.protocol.Ticket?, onChange: () -> Unit, onUnassign: (String) -> Unit) {
    Text(
        cur?.let { "${shortTicketId(it.id)} ${it.title}" } ?: "No ticket", color = if (cur != null) Palette.Text else Palette.Muted, fontSize = 11.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 12.dp, vertical = 4.dp).testTag("session-menu-current-$key"),
    )
    DropdownMenuItem(
        text = { Text(if (cur != null) "Change ticket..." else "Assign ticket...", color = Palette.Text, fontSize = 12.sp) },
        onClick = onChange, modifier = Modifier.testTag("session-menu-change-$key"),
    )
    if (cur != null) DropdownMenuItem(
        text = { Text("Unassign", color = Palette.Text, fontSize = 12.sp) },
        onClick = { onUnassign(cur.id) }, modifier = Modifier.testTag("session-menu-unassign-$key"),
    )
}
