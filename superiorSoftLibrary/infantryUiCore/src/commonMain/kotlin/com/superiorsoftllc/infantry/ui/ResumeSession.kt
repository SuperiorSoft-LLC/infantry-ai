// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState

const val RESUME_SESSION_LABEL = "Resume session"
const val RESUME_SESSION_BAR_TAG = "session-resume-bar"
const val RESUME_SESSION_BUTTON_TAG = "session-resume-button"
const val RESUME_SESSION_MENU_TAG_PREFIX = "session-menu-resume-"

/** The line beside the button: the history is on screen, nothing runs until the owner resumes or sends. */
const val RESUME_SESSION_HINT = "Not running since the app restarted. Its history is shown; resume to reconnect (no message is sent)."

/**
 * CLI-PTY-5: whether [session] offers "Resume session": the daemon restarted since it last ran ([SessionRecord.dormant]),
 * it has a provider conversation to resume, and no turn is running.
 */
fun showsResumeSession(session: SessionRecord): Boolean =
    session.dormant && session.providerSessionId != null &&
        session.state !in setOf(SessionState.STARTING, SessionState.BUSY, SessionState.WAITING_INPUT)

/** CLI-PTY-5: the bar above a dormant session's composer: one line and the Resume session button. */
@Composable
fun ResumeSessionBar(enabled: Boolean, onResume: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(bottom = 6.dp).background(Palette.Raised, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp).testTag(RESUME_SESSION_BAR_TAG),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(RESUME_SESSION_HINT, color = Palette.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Button(
            onClick = onResume, enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
            modifier = Modifier.testTag(RESUME_SESSION_BUTTON_TAG),
        ) { Text(RESUME_SESSION_LABEL, fontSize = 13.sp) }
    }
}
