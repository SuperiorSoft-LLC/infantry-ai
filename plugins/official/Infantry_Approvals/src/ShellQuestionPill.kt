package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.superiorsoftllc.agenthq.ui.QuestionGoneCard
import com.superiorsoftllc.agenthq.ui.QuestionPill
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext

/** ASK-1: questions the owner cannot see inline (another session, or put off with Later): a pill that opens the asker. */
object ShellQuestionPill : Block("infantry.shell.questions", "infantry.shell.questions") {
    @Composable
    override fun Render(scope: BlockScope) {
        val ctx = LocalShellContext.current ?: return
        val questions = ctx.questions
        val session = ctx.session
        val open = questions.open
        val selected = session.selectedAgentId
        val box = scope.box ?: return
        val coroutines = rememberCoroutineScope()
        // The question the pill tried to open whose asking session is gone: shown as a card with a Dismiss button.
        var goneId by remember { mutableStateOf<String?>(null) }
        val gone = open.firstOrNull { it.id == goneId && !session.hasSession(it.sessionId) }
        with(box) {
            if (gone != null) QuestionGoneCard(onDismiss = { goneId = null; coroutines.launch { questions.cancel(gone.id) } })
            QuestionPill(
                onDismiss = {
                    // Closes the question the pill would open (the orphaned one first).
                    val q = open.firstOrNull { !session.hasSession(it.sessionId) }
                        ?: open.firstOrNull { it.sessionId != selected }
                        ?: open.firstOrNull()
                    q?.let { coroutines.launch { questions.cancel(it.id) } }
                },
                hidden = open.count { q -> q.id in questions.dismissed || q.sessionId != selected },
                onShow = {
                    // BUG 14ca3437: through show (openQuestion), so a phone also closes the drawer that covers the asker's chat.
                    val q = open.firstOrNull { it.sessionId != selected && session.hasSession(it.sessionId) }
                        ?: open.firstOrNull { !session.hasSession(it.sessionId) }
                        ?: open.firstOrNull()
                    if (q != null && !session.hasSession(q.sessionId)) goneId = q.id
                    q?.let { questions.show(it.id) } ?: questions.showAll()
                    ctx.window.showChat()
                },
            )
        }
    }
}
