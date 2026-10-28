package com.superiorsoftllc.agenthq.plugins.official.attention

import com.superiorsoftllc.agenthq.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.protocol.OwnerQuestionView
import com.superiorsoftllc.agenthq.ui.protocol.QuestionAnswerWire

// RUNTIME-13: the question history, out of Infantry_AppUi's QuestionWindow.kt into the official Infantry_Attention jar.

/** + the question id: the Dismiss button of an open history row. */
const val QUESTION_HISTORY_DISMISS_TAG_PREFIX = "question-history-dismiss-"
/** + the question id: one history row in the attention pane. */
const val QUESTION_HISTORY_TAG_PREFIX = "question-history-"
/** + the question id + "-" + the option index: an override chip on an auto-picked question. */
const val QUESTION_OVERRIDE_TAG_PREFIX = "question-override-"

/**
 * ASK-1: the question history at the top of the attention pane (^6), newest first. An open question offers "Show" (the
 * window again, [onShow] with its id); an auto-picked one offers the other options as override chips ([onOverride]), while [live].
 * BUG 14ca3437: the whole row of an open question is a tap target (a phone has no hover), and showing it needs no live
 * link: it only opens the question on this device.
 */
@Composable
fun QuestionHistory(
    questions: List<OwnerQuestionView>,
    live: Boolean,
    onOverride: (QuestionAnswerWire) -> Unit,
    onShow: (questionId: String) -> Unit,
    onDismiss: ((questionId: String) -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Questions", color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        questions.forEach { q ->
            val shape = RoundedCornerShape(6.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(Palette.Raised).border(1.dp, Palette.Border, shape)
                    .then(if (q.state == "open") Modifier.clickable(role = Role.Button, onClickLabel = "Show the question") { onShow(q.id) } else Modifier)
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
                        TextButton(onClick = { onShow(q.id) }) { Text("Show", fontSize = 11.sp) }
                        if (onDismiss != null) {
                            TextButton(onClick = { onDismiss(q.id) }, modifier = Modifier.testTag(QUESTION_HISTORY_DISMISS_TAG_PREFIX + q.id)) {
                                Text("Dismiss", color = Palette.Danger, fontSize = 11.sp)
                            }
                        }
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
                        "-> $picked ${if (by.isNotBlank()) "($by${if (q.overridden) ", overridden" else ""})" else ""}".trim(),
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

