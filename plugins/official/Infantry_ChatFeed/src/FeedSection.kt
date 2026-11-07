// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chatfeed

import com.superiorsoftllc.infantry.ui.model.SessionPaneAction
import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.ui.FloatingChips
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.superiorsoftllc.infantry.ui.model.pendingSendsFor
import com.superiorsoftllc.infantry.ui.LocalUserPortrait
import com.superiorsoftllc.infantry.ui.model.StartPlan
import com.superiorsoftllc.infantry.ui.model.sessionPaneAction
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.plugin.LocalSlotArgs
import com.superiorsoftllc.infantry.ui.plugin.TabContext
import com.superiorsoftllc.infantry.ui.shell.rememberGate

/** The slot arguments the host hands this section (see the host's `ChildSlot` call): the session on screen and the actions the empty states offer. */
internal object FeedArgs {
    const val SESSION_ID = "sessionId"
    const val SPAWN_MODE = "spawnMode"
    const val CONTROLLER = "controller"
    const val ON_START_SESSION = "onStartSession"
    const val ON_APPLY_START = "onApplyStart"
    const val ON_RESUME = "onResume"
    const val ON_LATER = "onLater"
    /** CHAT-MINIMIZE-5: the host's floating chips, a [FloatingChips], drawn in the bottom-right float row. */
    const val FLOATERS = "floaters"
}

/**
 * The feed: this session's output (user and agent messages, thinking, comms cards, finished lines, automation rows) by kind,
 * the pending question and the Activity box, or the empty states with Start session / Resume. Contributed to the chat's feed
 * slot by Infantry_ChatFeed; the host's frame (header, tabs, composer) is not drawn here.
 */
@Composable
@Suppress("UNCHECKED_CAST")
internal fun FeedSection(ctx: TabContext) {
    val args = LocalSlotArgs.current
    val controller = args[FeedArgs.CONTROLLER] as? Controller ?: return
    val state = ctx.state
    val agent = (args[FeedArgs.SESSION_ID] as? String)?.let { state.agents[it] }
    val onStartSession = args[FeedArgs.ON_START_SESSION] as? (() -> Unit)
    val onApplyStart = args[FeedArgs.ON_APPLY_START] as? ((StartPlan) -> Unit) ?: {}
    val onResume = args[FeedArgs.ON_RESUME] as? ((String) -> Unit) ?: {}
    val onLater = args[FeedArgs.ON_LATER] as? (() -> Unit) ?: {}
    val floaters = (args[FeedArgs.FLOATERS] as? FloatingChips)?.content ?: {}
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalUserPortrait provides state.userPortrait, LocalMessageActionsHost provides MessageActionsHost(state, controller)) {
        OutputPane(
            agent, args[FeedArgs.SPAWN_MODE] as? Boolean ?: false, roleColor = state.role(agent?.role)?.color,
            contributions = agent?.let { state.timeline[it.id] }.orEmpty(),
            onStartSession = onStartSession,
            // GAP-006b: Start session (its own settings, Apply / Cancel) or Resume, for the session on screen.
            // CHAT-ONE-1: the Assistant (global) gets the same Resume splash after End session; it never offers Start (the daemon makes it).
            paneAction = agent?.let { feedPaneAction(it, state.tickets) },
            onApplyStart = onApplyStart,
            onResume = onResume,
            floaters = floaters,
            emptyHint = if (state.projects.isEmpty()) FIRST_RUN_HINT else NO_SESSION_HINT,
            // CHAT-SENDING-1: this session's just-sent messages ("sending...", "not sent - retry") until the daemon's echo.
            pending = PendingSendsView(
                agent?.let { state.pendingSendsFor(it.id) }.orEmpty(),
                onRetry = { id -> scope.launch { controller.retrySend(id) } },
                onDiscard = controller::discardSend,
            ),
        ) {
            // ASK-1: this session's pending question, inline above the Activity box.
            QuestionPanel(
                shown = agent?.let { state.shownQuestionsFor(it.id) }.orEmpty(),
                enabled = rememberGate("questions.answer").allowed,
                onAnswer = { answer -> scope.launch { controller.answerQuestion(answer) } },
                onCancel = { id -> scope.launch { controller.cancelQuestion(id) } },
                onLater = { controller.dismissQuestions(); onLater() },
            )
        }
    }
}

/** CHAT-ONE-1: the pane action for a session in the shared feed; the Assistant (global) gets Resume only, never Start (the daemon makes it). */
fun feedPaneAction(agent: com.superiorsoftllc.infantry.ui.model.AgentView, tickets: List<com.superiorsoftllc.infantry.ui.protocol.Ticket>): SessionPaneAction? =
    sessionPaneAction(agent.session, tickets)?.takeIf { !agent.session.global || it is SessionPaneAction.Resume }
