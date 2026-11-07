// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.attention

import com.superiorsoftllc.infantry.ui.*

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
import com.superiorsoftllc.infantry.ui.model.QUESTION_OPEN
import com.superiorsoftllc.infantry.ui.protocol.OwnerQuestionView
import com.superiorsoftllc.infantry.ui.protocol.QuestionAnswerWire

// RUNTIME-13: the question history, out of Infantry_AppUi's QuestionWindow.kt into the official Infantry_Attention jar.

/** + the question id: the Dismiss button of an open history row. */
const val QUESTION_HISTORY_DISMISS_TAG_PREFIX = "question-history-dismiss-"
/** + the question id: one history row in the attention pane. */
const val QUESTION_HISTORY_TAG_PREFIX = "question-history-"
/** + the question id + "-" + the option index: an override chip on an auto-picked question. */
const val QUESTION_OVERRIDE_TAG_PREFIX = "question-override-"
/** ATTN-TOP-1: the section at the top of the attention pane with the cards waiting for the owner's reply. */
const val ATTENTION_NEEDS_REPLY_TAG = "attention-needs-reply"
/** ATTN-TOP-1: the question history below it (answered, auto-picked, cancelled). */
const val ATTENTION_QUESTIONS_TAG = "attention-questions"

/**
 * ATTN-TOP-1: splits the pane's questions, order kept, into the ones waiting for the owner's reply (state open) and the rest.
 * An answered question leaves the first list as soon as its new state arrives.
 */
fun splitNeedsReply(questions: List<OwnerQuestionView>): Pair<List<OwnerQuestionView>, List<OwnerQuestionView>> =
    questions.partition { it.state == QUESTION_OPEN }

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
    /** GATE-PANES-1: Dismiss is a `questions.answer` write too; off with [live] (the pane passes the gate there). */
    canDismiss: Boolean = live,
    /** ATTN-TOP-1: the section header ("Needs your reply (2)" for the top section). */
    title: String = "Questions",
    /** ATTN-TOP-1: the section's test tag, null = none. */
    tag: String? = null,
) {
    Column(
        Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                            TextButton(onClick = { onDismiss(q.id) }, enabled = canDismiss, modifier = Modifier.testTag(QUESTION_HISTORY_DISMISS_TAG_PREFIX + q.id)) {
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
                    // ASK-BUTTONS-1: options stack full width, text left-aligned and wrapping, never clipped.
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Change to:", color = Palette.Muted, fontSize = 11.sp)
                        q.options.forEachIndexed { i, o ->
                            if (o.label != q.answer) {
                                OutlinedButton(
                                    onClick = { onOverride(QuestionAnswerWire(q.id, answer = o.label)) },
                                    modifier = Modifier.fillMaxWidth().testTag("$QUESTION_OVERRIDE_TAG_PREFIX${q.id}-$i"),
                                ) { Text(o.label, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Start, modifier = Modifier.fillMaxWidth()) }
                            }
                        }
                    }
                }
            }
        }
    }
}

