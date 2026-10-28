package com.superiorsoftllc.agenthq.ui

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

/** IMG-2: the composer's paperclip, its chip row, each chip / its remove, the inline refusal, and a sent turn's thumbnails. */
const val CHAT_IMAGE_ATTACH_TAG = "chat-image-attach"
const val CHAT_IMAGE_CHIPS_TAG = "chat-image-chips"
const val CHAT_IMAGE_CHIP_TAG_PREFIX = "chat-image-chip-"
const val CHAT_IMAGE_REMOVE_TAG_PREFIX = "chat-image-remove-"
const val CHAT_IMAGE_ERROR_TAG = "chat-image-error"
const val CHAT_IMAGE_SENT_TAG_PREFIX = "chat-image-sent-"
/** IMG-5: a sent image the daemon's retention sweep has deleted. */
const val CHAT_IMAGE_EXPIRED_TAG_PREFIX = "chat-image-expired-"
/** ATTACH-INLINE-1: shown while an attachment is on its way to the daemon. */
const val CHAT_ATTACH_BUSY_TAG = "chat-attach-busy"
/** ATTACH-INLINE-1: the paperclip's label (its tooltip adds [ATTACH_FILE_SHORTCUT]). */
const val ATTACH_FILE_LABEL = "Attach file"
const val ATTACH_FILE_SHORTCUT = "Ctrl+Shift+A"
/** ATTACH-INLINE-1: what a tag's pill starts with, before the file's name. */
const val ATTACH_PILL_MARK = "📎"
/** ATTACH-INLINE-1: the limit for a non-image file (contract 1.89.0, purpose `file`); images keep 10 MB. */
const val CHAT_FILE_MAX_BYTES: Int = 20 * 1024 * 1024

/**
 * ATTACH-INLINE-1: one attachment of the message being written. [token] is the single character that stands for it in
 * the composer's text (see [ComposerTags]); [path] is null while the upload is on its way, then the absolute file the
 * daemon stored. [bytes] draw an image's thumbnail.
 */
class ChatAttachment(val token: Char, val fileName: String, val isImage: Boolean, val bytes: ByteArray, val path: String? = null) {
    fun stored(path: String) = ChatAttachment(token, fileName, isImage, bytes, path)
}

/**
 * ATTACH-INLINE-1: the composer's text model. The text is a plain String in which each attachment is ONE private-use
 * character ([ChatAttachment.token], U+E000..U+E0FF): typing, the caret, Shift+Enter and the slash list all work on
 * the String untouched, and Backspace after a tag deletes that one character, so the whole tag. A tag whose character
 * left the text is released ([prune]). [transformation] draws each token as a pill "📎 name"; [toPrompt] turns each
 * into its `[image: <path>]` / `[file: <path>]` reference at the same position.
 */
object ComposerTags {
    private const val FIRST = ''
    private const val LAST = ''

    fun isToken(c: Char): Boolean = c in FIRST..LAST

    /** A token neither [text] nor [attachments] uses; null when all 256 are taken. */
    fun nextToken(text: String, attachments: List<ChatAttachment>): Char? =
        (FIRST..LAST).firstOrNull { c -> c !in text && attachments.none { it.token == c } }

    /** The attachments whose token is still in [text]; the rest were deleted from it and are released. */
    fun prune(text: String, attachments: List<ChatAttachment>): List<ChatAttachment> = attachments.filter { it.token in text }

    /** [text] without [token], or without every token when null (a `/command` never carries attachments). */
    fun strip(text: String, token: Char? = null): String = text.filterNot { if (token == null) isToken(it) else it == token }

    /** The PROMPT: each token replaced by its reference where it stands; a token with no stored file is dropped. */
    fun toPrompt(text: String, attachments: List<ChatAttachment>): String = buildString {
        for (c in text) {
            if (!isToken(c)) { append(c); continue }
            val path = attachments.firstOrNull { it.token == c }?.let { a -> a.path?.let { if (a.isImage) ChatImageLines.image(it) else ChatImageLines.file(it) } }
            if (path != null) append(path)
        }
    }

    /** What a tag reads as in the box. */
    fun pill(a: ChatAttachment): String = "$ATTACH_PILL_MARK " + (if (a.fileName.length > 32) a.fileName.take(31) + "…" else a.fileName)

    /** Draws each known token as its pill; the caret steps over a pill as over one character. */
    fun transformation(attachments: List<ChatAttachment>, style: SpanStyle): VisualTransformation = VisualTransformation { text ->
        val raw = text.text
        if (raw.none { c -> isToken(c) && attachments.any { it.token == c } }) return@VisualTransformation TransformedText(text, OffsetMapping.Identity)
        val toShown = IntArray(raw.length + 1)
        val toRaw = ArrayList<Int>(raw.length + 16)
        val out = AnnotatedString.Builder()
        raw.forEachIndexed { i, c ->
            toShown[i] = out.length
            toRaw += i
            val a = if (isToken(c)) attachments.firstOrNull { it.token == c } else null
            if (a == null) {
                out.append(c)
            } else {
                val label = pill(a)
                repeat(label.length - 1) { toRaw += i + 1 } // inside the pill: the caret lands after it
                out.withStyle(style) { append(label) }
            }
        }
        toShown[raw.length] = out.length
        toRaw += raw.length
        TransformedText(out.toAnnotatedString(), object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = toShown[offset.coerceIn(0, raw.length)]
            override fun transformedToOriginal(offset: Int): Int = toRaw[offset.coerceIn(0, toRaw.size - 1)]
        })
    }
}

/**
 * IMG-2: how an attachment travels in a PROMPT: `[image: <absolute path>]`, and since ATTACH-INLINE-1 `[file: <absolute
 * path>]` for any other file, each where its tag stood in the text. The agent reads the file by its path; the
 * transcript reads the references back to draw the thumbnails.
 */
object ChatImageLines {
    private val REF = Regex("""\[(image|file): ([^\]\n]+)]""")

    fun image(path: String) = "[image: $path]"
    fun file(path: String) = "[file: $path]"

    /**
     * The image paths [text] carries, and the text to show: a line that is only an image reference is dropped (its
     * thumbnail shows it); any other reference reads as its pill "📎 name" where it stands.
     */
    fun split(text: String): Pair<String, List<String>> {
        val paths = mutableListOf<String>()
        val kept = text.lines().mapNotNull { line ->
            val alone = REF.matchEntire(line.trim())
            if (alone != null && alone.groupValues[1] == "image") {
                paths += alone.groupValues[2].trim()
                return@mapNotNull null
            }
            REF.replace(line) { m ->
                val path = m.groupValues[2].trim()
                if (m.groupValues[1] == "image") paths += path
                "$ATTACH_PILL_MARK " + path.substringAfterLast('/').substringAfterLast('\\')
            }
        }
        return kept.joinToString("\n").trimEnd() to paths
    }
}
