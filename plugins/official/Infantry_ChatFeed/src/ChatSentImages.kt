// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chatfeed

import com.superiorsoftllc.infantry.ui.*

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * IMG-2: a sent turn's images, read back from its `[image: …]` lines; a click opens one full size. IMG-5: one the
 * retention sweep has deleted shows "image expired" instead of a blank thumbnail; the turn's text is untouched.
 */
@Composable
internal fun ChatSentImages(paths: List<String>, inline: ByteArray? = null) {
    if (paths.isEmpty()) return
    val host = LocalPlatformHost.current
    Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        paths.forEach { path ->
            // Only a host that can look and finds nothing says expired; one that cannot look (null) keeps the thumb.
            val expired by produceState(false, path) { value = inline == null && withContext(Dispatchers.Default) { host.fileExists(path) == false } }
            if (expired) {
                Box(
                    Modifier.size(SENT_DP.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Panel)
                        .testTag(CHAT_IMAGE_EXPIRED_TAG_PREFIX + path),
                ) {
                    Text("image expired", color = Palette.Muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.Center))
                }
            } else {
                Box(Modifier.testTag(CHAT_IMAGE_SENT_TAG_PREFIX + path)) { SentImage(path, path.substringAfterLast('/').substringAfterLast('\\'), inline) }
            }
        }
    }
}

/** A sent chat image [SENT_IMAGE_WIDTH_DP] wide at most, aspect kept, rounded; click opens it full size. */
@Composable
private fun SentImage(path: String, name: String, inline: ByteArray? = null) {
    val bitmap = rememberImage(path, inline)
    val view = LocalImageViewer.current
    if (bitmap == null) {
        Box(Modifier.size(SENT_DP.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Panel)) {
            Text("img", color = Palette.Muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.Center))
        }
        return
    }
    Image(
        bitmap, contentDescription = "Image $name", contentScale = ContentScale.Fit,
        modifier = Modifier.widthIn(max = SENT_IMAGE_WIDTH_DP.dp).heightIn(max = 320.dp)
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)).clip(RoundedCornerShape(8.dp))
            .background(Palette.Panel)
            .clickable(role = Role.Button, onClickLabel = "Open the image full size") { view(bitmap, name) },
    )
}

private const val SENT_DP = 72
private const val SENT_IMAGE_WIDTH_DP = 240

/**
 * CHAT-IMAGE-1: `![caption](path)` lines in an agent's reply. A line that is only such an image whose path is an image-typed file
 * the host can see shows inline (same [ChatSentImages] as ticket images); anything else stays as the text it was.
 */
internal object ChatMarkdownImages {
    private val LINE = Regex("""^!\[([^\]]*)]\(([^)\n]+)\)$""")
    private val TYPES = setOf("png", "jpg", "jpeg", "gif", "webp")

    /** The text without the image-only lines whose path [exists], and those paths in order. */
    fun split(text: String, exists: (String) -> Boolean): Pair<String, List<String>> {
        val paths = mutableListOf<String>()
        val kept = text.lines().filter { line ->
            val m = LINE.matchEntire(line.trim()) ?: return@filter true
            val path = m.groupValues[2].trim()
            if (path.substringAfterLast('.', "").lowercase() in TYPES && "://" !in path && exists(path)) { paths += path; false } else true
        }
        return kept.joinToString("\n") to paths
    }
}
