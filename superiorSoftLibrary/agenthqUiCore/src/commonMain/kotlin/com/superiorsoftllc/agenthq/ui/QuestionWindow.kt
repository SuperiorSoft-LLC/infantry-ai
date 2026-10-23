package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.PromptCards
import com.superiorsoftllc.agenthq.ui.protocol.OwnerQuestionView
import com.superiorsoftllc.agenthq.ui.protocol.QuestionAnswerWire

const val QUESTION_WINDOW_TAG = "question-window"
const val QUESTION_POSITION_TAG = "question-position"
const val QUESTION_ASKER_TAG = "question-asker"
/** + the option's index: the option button. */
const val QUESTION_OPTION_TAG_PREFIX = "question-option-"
/** + the option's index: the "Recommended" badge, present on the recommended option only. */
const val QUESTION_RECOMMENDED_TAG_PREFIX = "question-recommended-"
const val QUESTION_OTHER_FIELD_TAG = "question-other-field"
const val QUESTION_SEND_OTHER_TAG = "question-send-other"
const val QUESTION_LATER_TAG = "question-later"
const val QUESTION_CANCEL_TAG = "question-cancel"
const val QUESTION_PILL_TAG = "question-pill"
/** + the question id: one history row in the attention pane. */
const val QUESTION_HISTORY_TAG_PREFIX = "question-history-"
/** + the question id + "-" + the option index: an override chip on an auto-picked question. */
const val QUESTION_OVERRIDE_TAG_PREFIX = "question-override-"

/** ASK-1: who asked, as the window shows it: the role and the agent (or the session when there is no agent). */
fun questionAsker(q: OwnerQuestionView): String {
    val who = q.agentId?.takeIf { it.isNotBlank() } ?: "session ${q.sessionId.take(8)}"
    return listOfNotNull(q.role?.takeIf { it.isNotBlank() }, who).joinToString(" · ")
}

/**
 * ASK-1: the owner-question window, drawn over the WHOLE app (whatever pane is on screen) while [shown] is not empty.
 * It shows the oldest question: the asking agent, the question, one button per option with its consequence, the
 * recommended option marked with its reason, a free-text answer when the question allows one, "Later" (hide it in this
 * window; [onLater]) and "Cancel question". One click on an option answers ([onAnswer]); the window moves on when the
 * daemon's `question` push says the question is answered. While questions wait but are hidden ([waiting] > 0, nothing
 * shown), a pill in the corner brings the window back ([onShow]). Esc = Later.
 */
@Composable
fun BoxScope.QuestionWindow(
    shown: List<OwnerQuestionView>,
    waiting: Int,
    onAnswer: (QuestionAnswerWire) -> Unit,
    onCancel: (String) -> Unit,
    onLater: () -> Unit,
    onShow: () -> Unit,
) {
    val q = shown.firstOrNull()
    if (q == null) {
        if (waiting > 0) {
            Text(
                "? $waiting ${if (waiting == 1) "question" else "questions"} waiting",
                color = Color(0xFF1A1200), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).clip(RoundedCornerShape(16.dp))
                    .background(Palette.Accent).clickable(role = Role.Button, onClickLabel = "Show the questions", onClick = onShow)
                    .padding(horizontal = 12.dp, vertical = 6.dp).testTag(QUESTION_PILL_TAG),
            )
        }
        return
    }
    key(q.id) {
        var other by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        RegisterModal()
        val blurStyle = LocalModalBlur.current.blurStyle
        Box(
            // The scrim takes the clicks meant for the panes behind it: a question is answered, put off, or cancelled.
            Modifier.fillMaxSize().background(if (blurStyle) ModalScrimTint else Color(0x99000000)).clickable(onClick = {}).testTag(QUESTION_WINDOW_TAG)
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onLater(); true } else false },
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp).clip(shape).background(Palette.Raised)
                    .border(1.dp, Palette.Accent, shape).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("An agent asks you", color = Palette.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    PromptCards.position(shown.size)?.let { Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(QUESTION_POSITION_TAG)) }
                }
                Text(questionAsker(q), color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(QUESTION_ASKER_TAG))
                Text(q.question, color = Palette.Text, fontSize = 14.sp)
                q.options.forEachIndexed { i, o ->
                    val recommended = o.label == q.recommended
                    val optionShape = RoundedCornerShape(6.dp)
                    Column(
                        Modifier.fillMaxWidth().clip(optionShape)
                            .background(if (recommended) Palette.Panel else Palette.Raised)
                            .border(if (recommended) 2.dp else 1.dp, if (recommended) Palette.Accent else Palette.Border, optionShape)
                            .clickable(role = Role.Button, onClickLabel = "Choose ${o.label}") { onAnswer(QuestionAnswerWire(q.id, answer = o.label)) }
                            .testTag(QUESTION_OPTION_TAG_PREFIX + i).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(o.label, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (recommended) {
                                Text(
                                    "Recommended", color = Color(0xFF1A1200), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.Accent)
                                        .padding(horizontal = 6.dp, vertical = 2.dp).testTag(QUESTION_RECOMMENDED_TAG_PREFIX + i),
                                )
                            }
                        }
                        if (o.description.isNotBlank()) Text(o.description, color = Palette.Muted, fontSize = 12.sp)
                        if (recommended && q.reason.isNotBlank()) Text("Why: ${q.reason}", color = Palette.Accent, fontSize = 11.sp)
                    }
                }
                q.defaultAfterMin?.let {
                    Text("If you do not answer, the recommended option is picked after $it min (you can still change it).", color = Palette.Muted, fontSize = 11.sp)
                }
                if (q.allowOther) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = other, onValueChange = { other = it }, singleLine = true,
                            label = { Text("Your own answer", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f).testTag(QUESTION_OTHER_FIELD_TAG),
                        )
                        Button(
                            onClick = { onAnswer(QuestionAnswerWire(q.id, otherText = other.trim())) }, enabled = other.isNotBlank(),
                            modifier = Modifier.testTag(QUESTION_SEND_OTHER_TAG),
                        ) { Text("Send", fontSize = 12.sp) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onCancel(q.id) }, modifier = Modifier.testTag(QUESTION_CANCEL_TAG)) {
                        Text("Cancel question", color = Palette.Danger, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onLater, modifier = Modifier.testTag(QUESTION_LATER_TAG)) {
                        Text("Later", color = Palette.Text, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * ASK-1: the question history at the top of the attention pane (^6), newest first. An open question offers "Show" (the
 * window again, [onShow]); an auto-picked one offers the other options as override chips ([onOverride]), while [live].
 */
@Composable
fun QuestionHistory(
    questions: List<OwnerQuestionView>,
    live: Boolean,
    onOverride: (QuestionAnswerWire) -> Unit,
    onShow: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Questions", color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        questions.forEach { q ->
            val shape = RoundedCornerShape(6.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(Palette.Raised).border(1.dp, Palette.Border, shape)
                    .testTag(QUESTION_HISTORY_TAG_PREFIX + q.id).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        q.state, color = if (q.state == "open") Palette.Accent else Palette.Muted, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(questionAsker(q), color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    if (q.state == "open") {
                        TextButton(onClick = onShow, enabled = live) { Text("Show", fontSize = 11.sp) }
                    }
                }
                Text(q.question, color = Palette.Text, fontSize = 12.sp)
                val picked = listOfNotNull(q.answer, q.otherText?.let { "\"$it\"" }).joinToString(" · ")
                if (picked.isNotBlank()) {
                    val by = when (q.resolvedBy) {
                        "auto" -> "auto-selected"
                        null -> ""
                        else -> "by ${q.resolvedBy}"
                    }
                    Text(
                        "→ $picked ${if (by.isNotBlank()) "($by${if (q.overridden) ", overridden" else ""})" else ""}".trim(),
                        color = Palette.Muted, fontSize = 11.sp,
                    )
                }
                if (q.state == "auto" && live) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Change to:", color = Palette.Muted, fontSize = 11.sp)
                        q.options.forEachIndexed { i, o ->
                            if (o.label != q.answer) {
                                OutlinedButton(
                                    onClick = { onOverride(QuestionAnswerWire(q.id, answer = o.label)) },
                                    modifier = Modifier.testTag("$QUESTION_OVERRIDE_TAG_PREFIX${q.id}-$i"),
                                ) { Text(o.label, fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}
