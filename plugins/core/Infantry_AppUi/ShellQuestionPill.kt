package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
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
        with(box) {
            QuestionPill(
                hidden = state.openQuestions.count { q -> q.id in state.dismissedQuestions || q.sessionId != state.selectedId },
                onShow = {
                    // BUG 14ca3437: through openQuestion, so a phone also closes the drawer that covers the asker's chat.
                    val q = state.openQuestions.firstOrNull { it.sessionId != state.selectedId && state.agents.containsKey(it.sessionId) }
                        ?: state.openQuestions.firstOrNull()
                    q?.let { s.controller.openQuestion(it.id) } ?: s.controller.showQuestions()
                    s.ui.centerTab = s.homeTab; s.chat.spawnMode = false
                },
            )
        }
    }
}
