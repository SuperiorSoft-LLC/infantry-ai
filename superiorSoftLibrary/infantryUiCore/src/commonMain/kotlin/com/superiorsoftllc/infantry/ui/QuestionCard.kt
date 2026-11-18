// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

/** ASK-CARD-UNIFY-1: one option of a [QuestionCard]. */
data class QuestionCardOption(val label: String, val description: String = "")

/** What a [QuestionCard] draws. Each caller (harness prompt, owner question) adapts its wire frame to this. */
data class QuestionCardModel(
    val title: String,
    val question: String,
    val options: List<QuestionCardOption>,
    val multiSelect: Boolean = false,
    /** The recommended option label: badge, highlight, and what Enter picks first. */
    val recommended: String? = null,
    val reason: String = "",
    /** Who asks ("Agent X, ticket Y"); null draws no line. */
    val asker: String? = null,
    /** The timer line ("Auto-select Use JWT in 1:05"); null when there is no timer. */
    val countdown: String? = null,
    /** "1 of N"; null when one waits. */
    val position: String? = null,
    /** A note under the options (the owner question's timer explanation). */
    val footnote: String? = null,
)

/** The test tags of one [QuestionCard] host; each host keeps its own so its tests and tooling do not change. */
data class QuestionCardTags(
    val card: String,
    val position: String,
    val asker: String,
    val countdown: String,
    val optionPrefix: String,
    val recommendedPrefix: String,
    val otherRow: String,
    val otherField: String,
    val sendOther: String,
    val otherCancel: String,
    val submit: String,
)

/**
 * ASK-CARD-UNIFY-1: the one question card for every question. A single-select option answers on tap; a multi-select
 * list toggles and a Submit button sends the picks. "Other…" opens a free-text field (Enter sends, Shift+Enter new
 * line, Esc closes). Keys: 1-9 pick, arrows move the highlight, Space toggles (multi), Enter answers the highlighted
 * option (or sends the Other text / the picks), Esc closes Other, else [onEscape]. [onAnswer] gets the chosen labels
 * (in option order) and the Other text; [actions] draws the host's buttons (Cancel, Later) under the card.
 */
@Composable
fun QuestionCard(
    model: QuestionCardModel,
    tags: QuestionCardTags,
    onAnswer: (chosen: List<String>, other: String?) -> Unit,
    onEscape: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val options = model.options
    var index by remember { mutableStateOf(options.indexOfFirst { it.label == model.recommended }.coerceAtLeast(0)) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var other by remember { mutableStateOf("") }
    var otherOpen by remember { mutableStateOf(false) }
    var otherFocused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val otherFocus = remember { FocusRequester() }
    LaunchedEffect(otherOpen) { runCatching { if (otherOpen) otherFocus.requestFocus() else focus.requestFocus() } }

    val otherText = other.trim().takeIf { it.isNotEmpty() }
    fun pick(label: String) { chosen = if (label in chosen) chosen - label else chosen + label }
    fun submitMulti() {
        val picks = options.map { it.label }.filter { it in chosen }
        if (picks.isNotEmpty() || otherText != null) onAnswer(picks, otherText)
    }
    fun sendOther() { if (otherText != null) onAnswer(if (model.multiSelect) options.map { it.label }.filter { it in chosen } else emptyList(), otherText) }
    fun activate(i: Int) {
        val label = options.getOrNull(i)?.label ?: return
        if (model.multiSelect) pick(label) else if (enabled) onAnswer(listOf(label), null)
    }

    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.fillMaxWidth().heightIn(max = 360.dp).clip(shape).background(Palette.Raised).border(1.dp, Palette.Accent, shape)
            .focusRequester(focus).focusable().testTag(tags.card)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = if (otherFocused) -1 else QUESTION_DIGIT_KEYS.indexOf(e.key)
                when {
                    e.key == Key.Escape -> { if (otherOpen) otherOpen = false else onEscape(); true }
                    e.key == Key.Enter && otherOpen && e.isShiftPressed -> false
                    (e.key == Key.Enter || e.key == Key.NumPadEnter) && enabled -> {
                        when {
                            otherOpen -> if (model.multiSelect) submitMulti() else sendOther()
                            model.multiSelect -> submitMulti()
                            else -> activate(index)
                        }
                        true
                    }
                    digit in options.indices -> { index = digit; activate(digit); true }
                    otherFocused -> false
                    e.key == Key.DirectionDown || e.key == Key.DirectionRight -> { index = (index + 1).coerceAtMost(options.lastIndex.coerceAtLeast(0)); true }
                    e.key == Key.DirectionUp || e.key == Key.DirectionLeft -> { index = (index - 1).coerceAtLeast(0); true }
                    e.key == Key.Spacebar && model.multiSelect -> { options.getOrNull(index)?.let { pick(it.label) }; true }
                    else -> false
                }
            }
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.title, color = Palette.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            model.countdown?.let { Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp).testTag(tags.countdown)) }
            model.position?.let { Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(tags.position)) }
        }
        model.asker?.let { Text(it, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(tags.asker)) }
        Text(model.question, color = Palette.Text, fontSize = 14.sp)
        options.forEachIndexed { i, o ->
            val recommended = o.label == model.recommended
            val picked = o.label in chosen
            val optionShape = RoundedCornerShape(6.dp)
            Row(
                Modifier.fillMaxWidth().clip(optionShape)
                    .background(if (recommended || i == index) Palette.Panel else Palette.Raised)
                    .border(if (recommended) 2.dp else 1.dp, if (recommended) Palette.Accent else Palette.Border, optionShape)
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Choose ${o.label}") { index = i; activate(i) }
                    .testTag(tags.optionPrefix + i).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (model.multiSelect) Checkbox(checked = picked, onCheckedChange = { index = i; activate(i) }, enabled = enabled)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.label, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (recommended) {
                            Text(
                                "Recommended", color = Palette.OnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.Accent)
                                    .padding(horizontal = 6.dp, vertical = 2.dp).testTag(tags.recommendedPrefix + i),
                            )
                        }
                    }
                    if (o.description.isNotBlank()) Text(o.description, color = Palette.Muted, fontSize = 12.sp)
                    if (recommended && model.reason.isNotBlank()) Text("Why: ${model.reason}", color = Palette.Accent, fontSize = 11.sp)
                }
            }
        }
        model.footnote?.let { Text(it, color = Palette.Muted, fontSize = 11.sp) }
        OtherAnswer(
            open = otherOpen, text = other, onText = { other = it }, onOpen = { otherOpen = true }, onClose = { otherOpen = false },
            onSend = ::sendOther, showSend = !model.multiSelect, enabled = enabled, tags = tags,
            fieldModifier = Modifier.focusRequester(otherFocus).onFocusChanged { otherFocused = it.isFocused },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            actions?.invoke(this)
            Spacer(Modifier.weight(1f))
            if (model.multiSelect) {
                Button(onClick = ::submitMulti, enabled = enabled && (chosen.isNotEmpty() || otherText != null), modifier = Modifier.testTag(tags.submit)) {
                    Text("Submit", fontSize = 12.sp)
                }
            }
        }
    }
}

private val QUESTION_DIGIT_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

/** The "Other…" row: closed it is one row; open it is a multi-line field (with Send for single-select, Cancel always). */
@Composable
private fun OtherAnswer(
    open: Boolean, text: String, onText: (String) -> Unit, onOpen: () -> Unit, onClose: () -> Unit, onSend: () -> Unit,
    showSend: Boolean, enabled: Boolean, tags: QuestionCardTags, fieldModifier: Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    if (!open) {
        Text(
            "Other…", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().clip(shape).background(Palette.Raised).border(1.dp, Palette.Border, shape)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Type your own answer", onClick = onOpen)
                .testTag(tags.otherRow).padding(10.dp),
        )
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text, onValueChange = onText, singleLine = false, minLines = 2, maxLines = 6,
            label = { Text("Your own answer (Enter sends, Shift+Enter new line, Esc cancels)", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth().then(fieldModifier).testTag(tags.otherField),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showSend) Button(onClick = onSend, enabled = enabled && text.isNotBlank(), modifier = Modifier.testTag(tags.sendOther)) { Text("Send", fontSize = 12.sp) }
            OutlinedButton(onClick = onClose, modifier = Modifier.testTag(tags.otherCancel)) { Text("Cancel", color = Palette.Text, fontSize = 12.sp) }
        }
    }
}
