package com.superiorsoftllc.agenthq.ui

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

const val QUESTION_PILL_TAG = "question-pill"
/** The drawn dismiss cross on the pill: closes the question the pill would open. */
const val QUESTION_PILL_DISMISS_TAG = "question-pill-dismiss"
/** The card shown instead of an inline question when its asking session no longer exists. */
const val QUESTION_GONE_TAG = "question-gone"
const val QUESTION_GONE_DISMISS_TAG = "question-gone-dismiss"


/**
 * ASK-1: the corner pill for questions the owner cannot see inline: [hidden] open questions that are for another session
 * or were put off with "Later". Absent when [hidden] is 0. [onShow] brings them back (and opens the asking session).
 */
@Composable
fun BoxScope.QuestionPill(hidden: Int, onShow: () -> Unit, onDismiss: (() -> Unit)? = null) {
    if (hidden <= 0) return
    Row(
        Modifier.align(Alignment.BottomEnd).padding(16.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Accent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "? $hidden ${if (hidden == 1) "question" else "questions"} waiting",
            color = Palette.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Show the questions", onClick = onShow)
                .padding(start = 12.dp, end = if (onDismiss == null) 12.dp else 6.dp, top = 6.dp, bottom = 6.dp).testTag(QUESTION_PILL_TAG),
        )
        if (onDismiss != null) {
            val cross = Palette.OnAccent
            Canvas(
                Modifier.clickable(role = Role.Button, onClickLabel = "Dismiss the question", onClick = onDismiss)
                    .padding(start = 6.dp, end = 12.dp, top = 10.dp, bottom = 10.dp).size(10.dp).testTag(QUESTION_PILL_DISMISS_TAG),
            ) {
                drawLine(cross, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = 2f, cap = StrokeCap.Round)
                drawLine(cross, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = 2f, cap = StrokeCap.Round)
            }
        }
    }
}

/** A question whose asking session is gone cannot be answered inline: say so, and offer to close it. */
@Composable
fun BoxScope.QuestionGoneCard(onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.align(Alignment.BottomEnd).padding(start = 16.dp, end = 16.dp, bottom = 64.dp).clip(shape).background(Palette.Raised)
            .border(1.dp, Palette.Accent, shape).padding(12.dp).testTag(QUESTION_GONE_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("This question's session no longer exists", color = Palette.Text, fontSize = 13.sp)
        OutlinedButton(onClick = onDismiss, modifier = Modifier.testTag(QUESTION_GONE_DISMISS_TAG)) {
            Text("Dismiss", color = Palette.Text, fontSize = 12.sp)
        }
    }
}
