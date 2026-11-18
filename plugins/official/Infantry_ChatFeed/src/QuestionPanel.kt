// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chatfeed

import com.superiorsoftllc.infantry.ui.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.PromptCards
import com.superiorsoftllc.infantry.ui.protocol.OwnerQuestionView
import com.superiorsoftllc.infantry.ui.protocol.QuestionAnswerWire

// RUNTIME-22: the inline question panel of the chat transcript (moved from QuestionWindow.kt; the pill and gone card stay with the shell).
const val QUESTION_WINDOW_TAG = "question-window"
const val QUESTION_POSITION_TAG = "question-position"
const val QUESTION_ASKER_TAG = "question-asker"
/** + the option's index: the option button. */
const val QUESTION_OPTION_TAG_PREFIX = "question-option-"
/** + the option's index: the "Recommended" badge, present on the recommended option only. */
const val QUESTION_RECOMMENDED_TAG_PREFIX = "question-recommended-"
const val QUESTION_OTHER_FIELD_TAG = "question-other-field"
/** The "Other…" row at the end of the options: opens the own-answer field. */
const val QUESTION_OTHER_ROW_TAG = "question-other-row"
const val QUESTION_OTHER_CANCEL_TAG = "question-other-cancel"
const val QUESTION_SEND_OTHER_TAG = "question-send-other"
const val QUESTION_LATER_TAG = "question-later"
const val QUESTION_CANCEL_TAG = "question-cancel"

/**
 * ASK-1: the selected session's pending owner question, inline in the chat above the Activity box. [shown] is that
 * session's open, not-put-off questions, oldest first; the oldest is drawn, with "1 of N". It pops out when a question
 * arrives and collapses once it is answered; with nothing pending it is not composed at all (no space, no header).
 * One click on an option answers ([onAnswer]); keys 1-9 pick an option and Enter sends the own answer (or picks the
 * recommended option) while the panel has focus, except while typing in the own-answer field.
 */
@Composable
fun QuestionPanel(
    shown: List<OwnerQuestionView>,
    onAnswer: (QuestionAnswerWire) -> Unit,
    onCancel: (String) -> Unit,
    onLater: () -> Unit,
    /** GATE-PANES-1: `questions.answer` (answer and cancel) is allowed here; off, the panel stays readable but sends nothing. */
    enabled: Boolean = true,
) {
    val answer: (QuestionAnswerWire) -> Unit = { if (enabled) onAnswer(it) }
    val current = shown.firstOrNull()
    // The last question stays drawn while the panel animates out.
    var last by remember { mutableStateOf(current) }
    if (current != null) last = current
    AnimatedVisibility(visible = current != null, enter = expandVertically(), exit = shrinkVertically()) {
        val q = current ?: last ?: return@AnimatedVisibility
        key(q.id) {
            Box(Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = ChatSidePadding, vertical = 8.dp).testTag(QUESTION_WINDOW_TAG)) {
                // ASK-CARD-UNIFY-1: the one shared question card; this panel only adapts the owner-question frame to it.
                val model = QuestionCardModel(
                    title = "An agent asks you", question = q.question, options = q.options.map { QuestionCardOption(it.label, it.description) },
                    recommended = q.recommended, reason = q.reason, asker = questionAsker(q), position = PromptCards.position(shown.size),
                    footnote = q.defaultAfterMin?.let { "If you do not answer, the recommended option is picked after $it min (you can still change it)." },
                )
                QuestionCard(
                    model, QUESTION_CARD_TAGS, enabled = enabled, onEscape = onLater,
                    onAnswer = { chosen, other ->
                        if (other != null) answer(QuestionAnswerWire(q.id, otherText = other))
                        else chosen.firstOrNull()?.let { answer(QuestionAnswerWire(q.id, answer = it)) }
                    },
                    actions = {
                        TextButton(onClick = { onCancel(q.id) }, enabled = enabled, modifier = Modifier.testTag(QUESTION_CANCEL_TAG)) {
                            Text("Cancel question", color = Palette.Danger, fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = onLater, modifier = Modifier.testTag(QUESTION_LATER_TAG)) {
                            Text("Later", color = Palette.Text, fontSize = 12.sp)
                        }
                    },
                )
            }
        }
    }
}

private val QUESTION_CARD_TAGS = QuestionCardTags(
    card = "question-card", position = QUESTION_POSITION_TAG, asker = QUESTION_ASKER_TAG, countdown = "question-countdown",
    optionPrefix = QUESTION_OPTION_TAG_PREFIX, recommendedPrefix = QUESTION_RECOMMENDED_TAG_PREFIX, otherRow = QUESTION_OTHER_ROW_TAG,
    otherField = QUESTION_OTHER_FIELD_TAG, sendOther = QUESTION_SEND_OTHER_TAG, otherCancel = QUESTION_OTHER_CANCEL_TAG, submit = "question-submit",
)
