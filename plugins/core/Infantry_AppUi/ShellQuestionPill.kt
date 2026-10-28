package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.superiorsoftllc.agenthq.ui.QuestionGoneCard
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.QuestionPill
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope

/** ASK-1: questions the owner cannot see inline (another session, or put off with Later): a pill that opens the asker. */
object ShellQuestionPill : Block("infantry.shell.questions", "infantry.shell.questions") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val state = s.state
        val box = scope.box ?: return
        val coroutines = rememberCoroutineScope()
        // The question the pill tried to open whose asking session is gone: shown as a card with a Dismiss button.
        var goneId by remember { mutableStateOf<String?>(null) }
        val gone = state.openQuestions.firstOrNull { it.id == goneId && !state.agents.containsKey(it.sessionId) }
        with(box) {
            if (gone != null) QuestionGoneCard(onDismiss = { goneId = null; coroutines.launch { s.controller.cancelQuestion(gone.id) } })
            QuestionPill(
                onDismiss = {
                    // Closes the question the pill would open (the orphaned one first).
                    val q = state.openQuestions.firstOrNull { !state.agents.containsKey(it.sessionId) }
                        ?: state.openQuestions.firstOrNull { it.sessionId != state.selectedId }
                        ?: state.openQuestions.firstOrNull()
                    q?.let { coroutines.launch { s.controller.cancelQuestion(it.id) } }
                },
                hidden = state.openQuestions.count { q -> q.id in state.dismissedQuestions || q.sessionId != state.selectedId },
                onShow = {
                    // BUG 14ca3437: through openQuestion, so a phone also closes the drawer that covers the asker's chat.
                    val q = state.openQuestions.firstOrNull { it.sessionId != state.selectedId && state.agents.containsKey(it.sessionId) }
                        ?: state.openQuestions.firstOrNull { !state.agents.containsKey(it.sessionId) }
                        ?: state.openQuestions.firstOrNull()
                    if (q != null && !state.agents.containsKey(q.sessionId)) goneId = q.id
                    q?.let { s.controller.openQuestion(it.id) } ?: s.controller.showQuestions()
                    s.ui.centerTab = s.homeTab; s.chat.spawnMode = false
                },
            )
        }
    }
}
