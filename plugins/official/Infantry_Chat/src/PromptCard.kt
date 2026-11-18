// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chat

import com.superiorsoftllc.infantry.ui.*

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
import com.superiorsoftllc.infantry.ui.model.PromptCards
import com.superiorsoftllc.infantry.ui.protocol.PromptRequestView
import com.superiorsoftllc.infantry.ui.protocol.PromptResponseWire

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
    /** GATE-PANES-1: `approvals.answer` is allowed here; off, the card stays readable (Esc still hides it) but sends nothing. */
    enabled: Boolean = true,
) {
    val send: (PromptResponseWire) -> Unit = { if (enabled) onAnswer(it) }
    val prompt = prompts.firstOrNull() ?: return
    // Fresh selection state per prompt: the next one in the queue never inherits the last one's choice.
    key(prompt.promptId) {
        var index by remember { mutableStateOf(0) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        // ASK-TIMER: the daemon resolves the prompt at expiresAt; the card counts down to it, once a second.
        var now by remember { mutableStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
        if (prompt.expiresAt != null) LaunchedEffect(prompt.expiresAt) {
            while (true) { now = kotlin.time.Clock.System.now().toEpochMilliseconds(); kotlinx.coroutines.delay(1_000) }
        }

        val approval = prompt.kind == PromptCards.KIND_TOOL_APPROVAL
        if (!approval) {
            // ASK-CARD-UNIFY-1: a harness question is drawn by the one shared question card.
            val model = QuestionCardModel(
                title = "The agent asks", question = prompt.question.orEmpty(), multiSelect = prompt.multiSelect,
                options = prompt.options.map { QuestionCardOption(it.label, it.description) },
                recommended = prompt.recommended, countdown = PromptCards.countdown(prompt, now), position = PromptCards.position(prompts.size),
            )
            QuestionCard(
                model, PROMPT_CARD_TAGS, enabled = enabled, modifier = modifier.padding(bottom = 8.dp),
                onAnswer = { chosen, other -> PromptCards.answer(prompt, chosen.toSet(), other.orEmpty())?.let(send) },
                onEscape = { onDismiss(prompt.promptId) },
            )
            return@key
        }
        val choices = PromptCards.approvalChoices(prompt, allowAlways)
        val rows = choices.size

        fun submit() { choices.getOrNull(index)?.let { send(PromptCards.decide(prompt, it.decision)) } }
        fun moveTo(next: Int) { index = next }

        val shape = RoundedCornerShape(8.dp)
        Column(
            modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).background(Palette.Raised).border(1.dp, Palette.Accent, shape)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown, Key.DirectionRight -> { moveTo(PromptCards.move(index, 1, rows)); true }
                        Key.DirectionUp, Key.DirectionLeft -> { moveTo(PromptCards.move(index, -1, rows)); true }
                        Key.Enter, Key.NumPadEnter -> { submit(); true }
                        Key.Escape -> { PromptCards.escape(prompt)?.takeIf { enabled }?.let(send) ?: onDismiss(prompt.promptId); true }
                        else -> false
                    }
                }
                .focusRequester(focus).focusable().testTag(PROMPT_CARD_TAG).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow this tool to run?", color = Palette.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                PromptCards.countdown(prompt, now)?.let {
                    Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp).testTag(PROMPT_COUNTDOWN_TAG))
                }
                PromptCards.position(prompts.size)?.let { Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(PROMPT_POSITION_TAG)) }
            }
            Text(prompt.tool ?: "(unnamed tool)", color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            prompt.input?.takeIf { it.isNotBlank() }?.let { Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp, maxLines = 3) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEachIndexed { i, c ->
                    val tag = Modifier.testTag(PROMPT_CHOICE_TAG_PREFIX + c.decision)
                    val onClick = { send(PromptCards.decide(prompt, c.decision)) }
                    if (i == index) {
                        Button(onClick = onClick, enabled = enabled, modifier = tag, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)) { Text(c.label, fontSize = 12.sp) }
                    } else {
                        OutlinedButton(onClick = onClick, enabled = enabled, modifier = tag) { Text(c.label, fontSize = 12.sp, color = Palette.Text) }
                    }
                }
            }
        }
    }
}

private val PROMPT_CARD_TAGS = QuestionCardTags(
    card = PROMPT_CARD_TAG, position = PROMPT_POSITION_TAG, asker = "prompt-card-asker", countdown = PROMPT_COUNTDOWN_TAG,
    optionPrefix = PROMPT_OPTION_TAG_PREFIX, recommendedPrefix = "prompt-recommended-", otherRow = "prompt-other-row",
    otherField = PROMPT_OTHER_FIELD_TAG, sendOther = "prompt-send-other", otherCancel = "prompt-other-cancel", submit = PROMPT_SUBMIT_TAG,
)
