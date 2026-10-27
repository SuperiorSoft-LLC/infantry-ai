package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost
import com.superiorsoftllc.agenthq.ui.shell.PickedImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/** IMG-4: the ticket edit form's Images section, its buttons, each thumbnail / remove (+ relPath) and the viewer. */
const val TICKET_IMAGES_TAG = "ticket-images"
const val TICKET_IMAGE_ATTACH_TAG = "ticket-image-attach"
const val TICKET_IMAGE_PASTE_TAG = "ticket-image-paste"
const val TICKET_IMAGE_ERROR_TAG = "ticket-image-error"
const val TICKET_IMAGE_THUMB_TAG_PREFIX = "ticket-image-thumb-"
/** Present only once the thumbnail's bytes decoded: the image itself, not its placeholder. */
const val TICKET_IMAGE_LOADED_TAG_PREFIX = "ticket-image-loaded-"
const val TICKET_IMAGE_REMOVE_TAG_PREFIX = "ticket-image-remove-"
const val TICKET_IMAGE_VIEWER_TAG = "ticket-image-viewer"
/** IMG-VIEW-1: the viewer's Close button, its image (a click toggles fit / actual size) and the file name under it. */
const val IMAGE_VIEWER_CLOSE_TAG = "image-viewer-close"
const val IMAGE_VIEWER_IMAGE_TAG = "image-viewer-image"
const val IMAGE_VIEWER_NAME_TAG = "image-viewer-name"

/** IMG-4: a ticket image's file, the project directory joined with its relPath; null when the project has none. */
internal fun ticketImagePath(projectDirectory: String?, relPath: String): String? =
    projectDirectory?.takeIf { it.isNotBlank() }?.let { it.trimEnd('/', '\\') + "/" + relPath }

/** IMG-4: what opens an image full size: the board's viewer, or nothing outside a board. */
internal val LocalImageViewer = staticCompositionLocalOf<(ImageBitmap, String) -> Unit> { { _, _ -> } }

/**
 * IMG-4: [content] with a full-size image viewer over it. A thumbnail anywhere inside opens it through
 * [LocalImageViewer]. IMG-VIEW-1: the image is scaled to fit with its aspect ratio and its file name under it; a click
 * on it toggles actual size (scrollable when larger than the window). Esc, Close, or a click outside the image closes it.
 */
@Composable
fun ImageViewerHost(content: @Composable () -> Unit) {
    var viewing by remember { mutableStateOf<Pair<ImageBitmap, String>?>(null) }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalImageViewer provides { bitmap, name -> viewing = bitmap to name }) { content() }
        viewing?.let { (bitmap, name) -> ImageViewer(bitmap, name, onClose = { viewing = null }) }
    }
}

@Composable
private fun ImageViewer(bitmap: ImageBitmap, name: String, onClose: () -> Unit) {
    var actualSize by remember(bitmap) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(Color(0xE6000000))
            .clickable(role = Role.Button, onClickLabel = "Close the image", onClick = onClose)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onClose(); true } else false }
            .testTag(TICKET_IMAGE_VIEWER_TAG)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                "Close ✕", color = Palette.Text, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(6.dp)).background(Palette.Raised)
                    .clickable(role = Role.Button, onClickLabel = "Close the image", onClick = onClose)
                    .testTag(IMAGE_VIEWER_CLOSE_TAG)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            // A click on the image toggles its size; only a click beside it reaches the backdrop and closes.
            val toggle = Modifier.clickable(role = Role.Button, onClickLabel = if (actualSize) "Fit the image" else "Show actual size") { actualSize = !actualSize }
            val natW = with(LocalDensity.current) { bitmap.width.toDp() }
            val natH = with(LocalDensity.current) { bitmap.height.toDp() }
            if (actualSize) {
                Box(Modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                    Image(bitmap, contentDescription = name, modifier = Modifier.size(natW, natH).then(toggle).testTag(IMAGE_VIEWER_IMAGE_TAG))
                }
            } else {
                // Fit: the largest box of the image's aspect ratio inside the space.
                val scale = minOf(maxWidth / natW, maxHeight / natH)
                Image(
                    bitmap, contentDescription = name, contentScale = ContentScale.Fit,
                    modifier = Modifier.size(natW * scale, natH * scale).then(toggle).testTag(IMAGE_VIEWER_IMAGE_TAG),
                )
            }
        }
        Text(name, color = Palette.Text, fontSize = 12.sp, modifier = Modifier.testTag(IMAGE_VIEWER_NAME_TAG))
    }
}

/**
 * IMG-4: the edit form's Images section. Attach by the button (a file picker), Paste (an image on the clipboard) or a
 * drop onto the section; each picked image is handed to [onAttach], which uploads it. [images] are the ticket's
 * relPaths as the form holds them; each shows as a thumbnail read from [projectDirectory] (else from [uploaded], the
 * bytes this form just sent), with an ✕ that hands it to [onRemove]. Clicking a thumbnail opens it full size.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TicketImagesSection(
    images: List<String>,
    projectDirectory: String?,
    uploaded: Map<String, ByteArray>,
    enabled: Boolean,
    busy: Boolean,
    error: String?,
    onAttach: (List<PickedImage>) -> Unit,
    onRemove: (String) -> Unit,
) {
    val host = LocalPlatformHost.current
    var hovering by remember { mutableStateOf(false) }
    val drop = remember(host, enabled) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hovering = true }
            override fun onExited(event: DragAndDropEvent) { hovering = false }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val picked = host.droppedImages(event)
                if (picked.isEmpty() || !enabled) return false
                onAttach(picked)
                return true
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (hovering) Palette.Accent else Palette.Border, RoundedCornerShape(6.dp))
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = drop)
            .testTag(TICKET_IMAGES_TAG)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Images", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (busy) Text("Uploading…", color = Palette.Muted, fontSize = 10.sp)
            IconAction(
                AhqIcons.AttachFile, "Attach an image",
                enabled = enabled && !busy && host.canPickFiles,
                style = IconActionStyle.TEXT,
                modifier = Modifier.testTag(TICKET_IMAGE_ATTACH_TAG),
                onClick = {
                    host.pickFile("Attach an image")?.let { path ->
                        val name = path.substringAfterLast('/').substringAfterLast('\\')
                        host.readBytes(path)?.let { bytes -> onAttach(listOf(PickedImage(name, PickedImage.mimeOf(name), bytes))) }
                    }
                },
            )
            IconAction(
                AhqIcons.ContentPaste, "Paste an image", shortcut = "Ctrl+V",
                enabled = enabled && !busy,
                style = IconActionStyle.TEXT,
                modifier = Modifier.testTag(TICKET_IMAGE_PASTE_TAG),
                onClick = { host.clipboardImage()?.let { onAttach(listOf(it)) } },
            )
        }
        if (images.isEmpty()) {
            Text("Drop, paste or attach a png, jpeg, gif or webp.", color = Palette.Muted, fontSize = 10.sp)
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                images.forEach { relPath -> TicketImageThumb(relPath, ticketImagePath(projectDirectory, relPath), uploaded[relPath], enabled, onRemove) }
            }
        }
        error?.let { Text(it, color = Palette.Danger, fontSize = 10.sp, modifier = Modifier.testTag(TICKET_IMAGE_ERROR_TAG)) }
    }
}

@Composable
private fun TicketImageThumb(relPath: String, path: String?, fallback: ByteArray?, enabled: Boolean, onRemove: (String) -> Unit) {
    val bitmap = rememberImage(path, fallback)
    val view = LocalImageViewer.current
    val name = relPath.substringAfterLast('/')
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(THUMB_DP.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Raised).testTag(TICKET_IMAGE_THUMB_TAG_PREFIX + relPath)) {
        if (bitmap != null) {
            Image(
                bitmap, contentDescription = "Ticket image $name", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
                    .clickable(role = Role.Button, onClickLabel = "Open the image full size") { view(bitmap, name) }
                    .testTag(TICKET_IMAGE_LOADED_TAG_PREFIX + relPath),
            )
        } else {
            Text(name.take(10) + "…", color = Palette.Muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.Center).padding(4.dp))
        }
    }
    // Remove only drops it from the form's list; Save sends the new list.
    IconAction(
        AhqIcons.Close, "Remove the image from the ticket",
        enabled = enabled,
        style = IconActionStyle.TEXT,
        modifier = Modifier.testTag(TICKET_IMAGE_REMOVE_TAG_PREFIX + relPath),
        onClick = { onRemove(relPath) },
    )
    }
}

/** The image at [path] (read through the platform host), else [fallback]'s bytes; null while loading or unreadable. */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun rememberImage(path: String?, fallback: ByteArray?): ImageBitmap? {
    val host = LocalPlatformHost.current
    val bitmap by produceState<ImageBitmap?>(null, path, fallback) {
        value = withContext(Dispatchers.Default) {
            val bytes = path?.let(host::readBytes) ?: fallback
            try {
                bytes?.decodeToImageBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    return bitmap
}

private const val THUMB_DP = 64
