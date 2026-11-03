package com.superiorsoftllc.infantry.plugins.official.review

import com.superiorsoftllc.infantry.ui.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.shell.ReviewContext

/** The nodes converged pods queue for review ([AppState.reviewQueue]); "Open agent" goes through [review] (RUNTIME-11). */
@Composable
internal fun ReviewPane(state: AppState, review: ReviewContext) {
    val queue = state.reviewQueue
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (queue.isEmpty()) {
            Text("Nothing is waiting for review.", color = Palette.Text)
            Text(
                "Converged pods queue their nodes here. The client only sees pods it submitted or queried by id " +
                    "(the daemon has no pod-list frame).",
                color = Palette.Muted, fontSize = 11.sp,
            )
            return
        }
        queue.forEach { e ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp)) {
                Text(e.nodeName, color = Palette.Text, fontWeight = FontWeight.SemiBold)
                Text(e.podGoal, color = Palette.Muted, fontSize = 11.sp, maxLines = 2)
                e.branch?.let { Text(it, color = Palette.Accent, fontFamily = Mono, fontSize = 11.sp) }
                e.workingDir?.let { Text(it, color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp) }
                Row {
                    e.sessionId?.takeIf { it in state.agents }?.let { sid ->
                        TextButton(onClick = { review.openAgent(sid) }) { Text("Open agent") }
                    }
                }
            }
        }
        Text("Approve/reject is not on the daemon protocol yet, so a verdict cannot be recorded from here.", color = Palette.Muted, fontSize = 11.sp)
    }
}
