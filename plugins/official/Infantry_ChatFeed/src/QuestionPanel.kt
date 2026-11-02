package com.superiorsoftllc.agenthq.plugins.official.chatfeed

import com.superiorsoftllc.agenthq.ui.*

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
import com.superiorsoftllc.agenthq.ui.model.PromptCards
import com.superiorsoftllc.agenthq.ui.protocol.OwnerQuestionView
import com.superiorsoftllc.agenthq.ui.protocol.QuestionAnswerWire

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

private val QUESTION_DIGIT_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

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
            var other by remember { mutableStateOf("") }
            var otherOpen by remember { mutableStateOf(false) }
            var otherFocused by remember { mutableStateOf(false) }
            val focus = remember { FocusRequester() }
            val otherFocus = remember { FocusRequester() }
            LaunchedEffect(otherOpen) { runCatching { if (otherOpen) otherFocus.requestFocus() else focus.requestFocus() } }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            fun send() {
                if (otherOpen) { if (other.isNotBlank()) answer(QuestionAnswerWire(q.id, otherText = other.trim())) }
                else q.options.firstOrNull { it.label == q.recommended }?.let { answer(QuestionAnswerWire(q.id, answer = it.label)) }
            }
            val shape = RoundedCornerShape(10.dp)
            Box(Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 14.dp, vertical = 8.dp).testTag(QUESTION_WINDOW_TAG)) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(shape).background(Palette.Raised)
                    .border(1.dp, Palette.Accent, shape).focusRequester(focus).focusable()
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val digit: Int = if (otherFocused) -1 else QUESTION_DIGIT_KEYS.indexOf(e.key)
                        when {
                            e.key == Key.Escape -> { if (otherOpen) otherOpen = false else onLater(); true }
                            e.key == Key.Enter && otherOpen && e.isShiftPressed -> false
                            e.key == Key.Enter -> { send(); true }
                            digit in q.options.indices -> { answer(QuestionAnswerWire(q.id, answer = q.options[digit].label)); true }
                            else -> false
                        }
                    }
                    .verticalScroll(rememberScrollState()).padding(16.dp),
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
                            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Choose ${o.label}") { answer(QuestionAnswerWire(q.id, answer = o.label)) }
                            .testTag(QUESTION_OPTION_TAG_PREFIX + i).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(o.label, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (recommended) {
                                Text(
                                    "Recommended", color = Palette.OnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
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
                run {
                    OtherAnswer(
                        open = otherOpen, text = other, onText = { other = it },
                        onOpen = { otherOpen = true }, onClose = { otherOpen = false },
                        onSend = { if (other.isNotBlank()) answer(QuestionAnswerWire(q.id, otherText = other.trim())) }, enabled = enabled,
                        fieldModifier = Modifier.focusRequester(otherFocus).onFocusChanged { otherFocused = it.isFocused },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onCancel(q.id) }, enabled = enabled, modifier = Modifier.testTag(QUESTION_CANCEL_TAG)) {
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
}

/**
 * ASK-OTHER: the "Other…" row at the end of a question's options. Closed it is one row; open it is a multi-line field
 * with Send and Cancel (Enter sends, Shift+Enter is a newline, Esc closes: the panel's key handler does those).
 */
@Composable
private fun OtherAnswer(
    open: Boolean,
    text: String,
    onText: (String) -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onSend: () -> Unit,
    fieldModifier: Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(6.dp)
    if (!open) {
        Text(
            "Other…", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().clip(shape).background(Palette.Raised).border(1.dp, Palette.Border, shape)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Type your own answer", onClick = onOpen)
                .testTag(QUESTION_OTHER_ROW_TAG).padding(10.dp),
        )
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text, onValueChange = onText, singleLine = false, minLines = 2, maxLines = 6,
            label = { Text("Your own answer (Enter sends, Shift+Enter new line, Esc cancels)", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth().then(fieldModifier).testTag(QUESTION_OTHER_FIELD_TAG),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSend, enabled = enabled && text.isNotBlank(), modifier = Modifier.testTag(QUESTION_SEND_OTHER_TAG)) {
                Text("Send", fontSize = 12.sp)
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.testTag(QUESTION_OTHER_CANCEL_TAG)) {
                Text("Cancel", color = Palette.Text, fontSize = 12.sp)
            }
        }
    }
}
