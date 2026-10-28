package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.PromptCards
import com.superiorsoftllc.agenthq.ui.protocol.PromptRequestView
import com.superiorsoftllc.agenthq.ui.protocol.PromptResponseWire

const val PROMPT_CARD_TAG = "prompt-card"
const val PROMPT_POSITION_TAG = "prompt-card-position"
const val PROMPT_CHOICE_TAG_PREFIX = "prompt-choice-"
const val PROMPT_OPTION_TAG_PREFIX = "prompt-option-"
const val PROMPT_OTHER_FIELD_TAG = "prompt-other-field"
const val PROMPT_SUBMIT_TAG = "prompt-submit"
const val PROMPT_COUNTDOWN_TAG = "prompt-card-countdown"

/**
 * PROMPT-2: the card docked above the chat input while [prompts] (the open session's, oldest first) is not empty. It
 * shows the oldest one, with "1 of N" when more wait. [onAnswer] sends a `prompt_response`; the card itself only closes
 * when the daemon's `prompt_resolved` removes the prompt. [onDismiss] is Esc on a question (see [PromptCards.escape]).
 * Keyboard: arrow keys move between options, Enter submits, Esc denies / gives no answer.
 */
@Composable
fun PromptCard(
    prompts: List<PromptRequestView>,
    allowAlways: Boolean,
    onAnswer: (PromptResponseWire) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prompt = prompts.firstOrNull() ?: return
    // Fresh selection state per prompt: the next one in the queue never inherits the last one's choice.
    key(prompt.promptId) {
        var index by remember { mutableStateOf(0) }
        var chosen by remember { mutableStateOf(emptySet<String>()) }
        var other by remember { mutableStateOf("") }
        val otherSource = remember { MutableInteractionSource() }
        val otherFocused by otherSource.collectIsFocusedAsState()
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        // ASK-TIMER: the daemon resolves the prompt at expiresAt; the card counts down to it, once a second.
        var now by remember { mutableStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
        if (prompt.expiresAt != null) LaunchedEffect(prompt.expiresAt) {
            while (true) { now = kotlin.time.Clock.System.now().toEpochMilliseconds(); kotlinx.coroutines.delay(1_000) }
        }

        val approval = prompt.kind == PromptCards.KIND_TOOL_APPROVAL
        val choices = if (approval) PromptCards.approvalChoices(prompt, allowAlways) else emptyList()
        val rows = if (approval) choices.size else prompt.options.size
        val answer = if (approval) null else PromptCards.answer(prompt, chosen, other)

        fun submit() {
            if (approval) choices.getOrNull(index)?.let { onAnswer(PromptCards.decide(prompt, it.decision)) }
            else answer?.let(onAnswer)
        }
        fun moveTo(next: Int) {
            index = next
            // A radio list selects as the highlight moves; a checkbox list waits for Space.
            if (!approval && !prompt.multiSelect) prompt.options.getOrNull(next)?.let { chosen = setOf(it.label) }
        }

        val shape = RoundedCornerShape(8.dp)
        Column(
            modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).background(Palette.Raised).border(1.dp, Palette.Accent, shape)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown, Key.DirectionRight -> { moveTo(PromptCards.move(index, 1, rows)); true }
                        Key.DirectionUp, Key.DirectionLeft -> { moveTo(PromptCards.move(index, -1, rows)); true }
                        Key.Enter, Key.NumPadEnter -> { submit(); true }
                        Key.Escape -> { PromptCards.escape(prompt)?.let(onAnswer) ?: onDismiss(prompt.promptId); true }
                        Key.Spacebar -> if (!approval && !otherFocused) {
                            prompt.options.getOrNull(index)?.let { chosen = PromptCards.toggle(prompt, chosen, it.label) }; true
                        } else false
                        else -> false
                    }
                }
                .focusRequester(focus).focusable().testTag(PROMPT_CARD_TAG).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (approval) "Allow this tool to run?" else "The agent asks",
                    color = Palette.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                PromptCards.countdown(prompt, now)?.let {
                    Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp).testTag(PROMPT_COUNTDOWN_TAG))
                }
                PromptCards.position(prompts.size)?.let { Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(PROMPT_POSITION_TAG)) }
            }
            if (approval) {
                Text(prompt.tool ?: "(unnamed tool)", color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                prompt.input?.takeIf { it.isNotBlank() }?.let { Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp, maxLines = 3) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEachIndexed { i, c ->
                        val tag = Modifier.testTag(PROMPT_CHOICE_TAG_PREFIX + c.decision)
                        val onClick = { onAnswer(PromptCards.decide(prompt, c.decision)) }
                        if (i == index) {
                            Button(onClick = onClick, modifier = tag, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)) { Text(c.label, fontSize = 12.sp) }
                        } else {
                            OutlinedButton(onClick = onClick, modifier = tag) { Text(c.label, fontSize = 12.sp, color = Palette.Text) }
                        }
                    }
                }
            } else {
                Text(prompt.question.orEmpty(), color = Palette.Text, fontSize = 13.sp)
                prompt.options.forEachIndexed { i, o ->
                    val picked = o.label in chosen
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(if (i == index) Palette.Panel else Palette.Raised)
                            .clickable { index = i; chosen = PromptCards.toggle(prompt, chosen, o.label) }
                            .testTag(PROMPT_OPTION_TAG_PREFIX + i),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (prompt.multiSelect) Checkbox(checked = picked, onCheckedChange = { index = i; chosen = PromptCards.toggle(prompt, chosen, o.label) })
                        else RadioButton(selected = picked, onClick = { index = i; chosen = PromptCards.toggle(prompt, chosen, o.label) })
                        Column(Modifier.weight(1f)) {
                            Text(o.label, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            if (o.description.isNotBlank()) Text(o.description, color = Palette.Muted, fontSize = 11.sp)
                        }
                    }
                }
                run {
                    OutlinedTextField(
                        value = other, onValueChange = { other = it }, singleLine = true,
                        label = { Text("Other", fontSize = 11.sp) }, interactionSource = otherSource,
                        modifier = Modifier.fillMaxWidth().testTag(PROMPT_OTHER_FIELD_TAG),
                    )
                }
                Row {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { submit() }, enabled = answer != null, modifier = Modifier.testTag(PROMPT_SUBMIT_TAG)) { Text("Submit", fontSize = 12.sp) }
                }
            }
        }
    }
}
