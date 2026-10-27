package com.superiorsoftllc.agenthq.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
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
const val QUESTION_PILL_TAG = "question-pill"
/** + the question id: one history row in the attention pane. */
const val QUESTION_HISTORY_TAG_PREFIX = "question-history-"
/** + the question id + "-" + the option index: an override chip on an auto-picked question. */
const val QUESTION_OVERRIDE_TAG_PREFIX = "question-override-"

private val QUESTION_DIGIT_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

/** ASK-1: who asked, as the window shows it: the role and the agent (or the session when there is no agent). */
fun questionAsker(q: OwnerQuestionView): String {
    val who = q.agentId?.takeIf { it.isNotBlank() } ?: "session ${q.sessionId.take(8)}"
    return listOfNotNull(q.role?.takeIf { it.isNotBlank() }, who).joinToString(" · ")
}

/**
 * ASK-1: the corner pill for questions the owner cannot see inline: [hidden] open questions that are for another session
 * or were put off with "Later". Absent when [hidden] is 0. [onShow] brings them back (and opens the asking session).
 */
@Composable
fun BoxScope.QuestionPill(hidden: Int, onShow: () -> Unit) {
    if (hidden <= 0) return
    Text(
        "? $hidden ${if (hidden == 1) "question" else "questions"} waiting",
        color = Palette.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).clip(RoundedCornerShape(16.dp))
            .background(Palette.Accent).clickable(role = Role.Button, onClickLabel = "Show the questions", onClick = onShow)
            .padding(horizontal = 12.dp, vertical = 6.dp).testTag(QUESTION_PILL_TAG),
    )
}

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
) {
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
                if (otherOpen) { if (other.isNotBlank()) onAnswer(QuestionAnswerWire(q.id, otherText = other.trim())) }
                else q.options.firstOrNull { it.label == q.recommended }?.let { onAnswer(QuestionAnswerWire(q.id, answer = it.label)) }
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
                            digit in q.options.indices -> { onAnswer(QuestionAnswerWire(q.id, answer = q.options[digit].label)); true }
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
                            .clickable(role = Role.Button, onClickLabel = "Choose ${o.label}") { onAnswer(QuestionAnswerWire(q.id, answer = o.label)) }
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
                        onSend = { if (other.isNotBlank()) onAnswer(QuestionAnswerWire(q.id, otherText = other.trim())) },
                        fieldModifier = Modifier.focusRequester(otherFocus).onFocusChanged { otherFocused = it.isFocused },
                    )
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
}

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
) {
    val shape = RoundedCornerShape(6.dp)
    if (!open) {
        Text(
            "Other…", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().clip(shape).background(Palette.Raised).border(1.dp, Palette.Border, shape)
                .clickable(role = Role.Button, onClickLabel = "Type your own answer", onClick = onOpen)
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
            Button(onClick = onSend, enabled = text.isNotBlank(), modifier = Modifier.testTag(QUESTION_SEND_OTHER_TAG)) {
                Text("Send", fontSize = 12.sp)
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.testTag(QUESTION_OTHER_CANCEL_TAG)) {
                Text("Cancel", color = Palette.Text, fontSize = 12.sp)
            }
        }
    }
}
