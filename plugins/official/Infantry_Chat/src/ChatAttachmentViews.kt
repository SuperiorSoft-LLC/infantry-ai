package com.superiorsoftllc.agenthq.plugins.official.chat

import com.superiorsoftllc.agenthq.ui.*

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
import com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// S4d (docs/SHELL-LEAKS.md): the chat's attachment strip and sent images, moved from core ChatAttachments.kt with the
// chat; the attachment model (ChatAttachment, ComposerTags, ChatImageLines) stays in core, the window owns the draft.

/**
 * IMG-2: the composer's attached images, above the input: a thumbnail each (a click opens it full size) with a remove,
 * "Uploading…" while one is on its way, and a refusal (nothing was attached) inline. ATTACH-INLINE-1: the tags in the
 * text ARE the attachments; this strip only previews the stored images, and its remove deletes the image's tag.
 */
@Composable
internal fun ChatAttachmentChips(all: List<ChatAttachment>, busy: Boolean, error: String?, onRemove: (String) -> Unit) {
    val attachments = all.mapNotNull { a -> a.path?.takeIf { a.isImage }?.let { it to a } }
    if (attachments.isEmpty() && !busy && error == null) return
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (attachments.isNotEmpty() || busy) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).testTag(CHAT_IMAGE_CHIPS_TAG),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                attachments.forEach { (path, a) ->
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.Raised).testTag(CHAT_IMAGE_CHIP_TAG_PREFIX + path).padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ImageThumb(null, a.bytes, a.fileName, CHIP_DP)
                        Text(a.fileName.take(18), color = Palette.Text, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        IconAction(
                            AhqIcons.Close, "Remove the image",
                            style = IconActionStyle.TEXT,
                            modifier = Modifier.testTag(CHAT_IMAGE_REMOVE_TAG_PREFIX + path),
                            onClick = { onRemove(path) },
                        )
                    }
                }
                if (busy) Text("Uploading…", color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.testTag(CHAT_ATTACH_BUSY_TAG))
            }
        }
        error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.testTag(CHAT_IMAGE_ERROR_TAG)) }
    }
}

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

@Composable
private fun ImageThumb(path: String?, bytes: ByteArray?, name: String, sizeDp: Int) {
    val bitmap = rememberImage(path, bytes)
    val view = LocalImageViewer.current
    Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Panel)) {
        if (bitmap != null) {
            Image(
                bitmap, contentDescription = "Image $name", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clickable(role = Role.Button, onClickLabel = "Open the image full size") { view(bitmap, name) },
            )
        } else {
            Text("img", color = Palette.Muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}

private const val CHIP_DP = 32
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
