package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.superiorsoftllc.agenthq.ui.model.ChatSpan
import com.superiorsoftllc.agenthq.ui.model.isSafeChatUrl
import com.superiorsoftllc.agenthq.ui.protocol.SessionState

// S4d (docs/SHELL-LEAKS.md): the two pieces of ChatSurface.kt that core still uses, kept here when the chat tree moved
// to the app UI. Both are kit: a model constant and a pure formatter, no window state.

/** A session whose run is over for good: nothing left to kill, only to resume or remove. */
val ENDED_STATES = setOf(SessionState.EXITED, SessionState.FAILED, SessionState.ORPHANED)

/** The chat's inline spans as styled text (the markdown renderers and the chat table draw them). */
fun spansText(spans: List<ChatSpan>): AnnotatedString = buildAnnotatedString {
    for (s in spans) when (s) {
        is ChatSpan.Text -> append(s.text)
        is ChatSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.text) }
        is ChatSpan.Italic -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(s.text) }
        is ChatSpan.InlineCode -> withStyle(SpanStyle(background = Palette.Raised, color = Palette.Accent)) { append(s.text) }
        is ChatSpan.Link -> if (isSafeChatUrl(s.url)) {
            // Opens through the platform LocalUriHandler; the parser only ever yields http/https/mailto, re-checked here.
            withLink(LinkAnnotation.Url(s.url, TextLinkStyles(SpanStyle(color = Palette.Info, textDecoration = TextDecoration.Underline)))) { append(s.label) }
        } else append(s.label)
    }
}
