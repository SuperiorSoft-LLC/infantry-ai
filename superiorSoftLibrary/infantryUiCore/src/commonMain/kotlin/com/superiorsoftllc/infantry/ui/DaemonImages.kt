// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * CHAT-IMAGE-REMOTE-1: what one fetch of an uploaded image from the daemon came to. [Missing] = the daemon holds no such upload (the
 * retention sweep removed it); [Failed] = the fetch itself failed (not connected, timeout, refusal), worth a retry.
 */
sealed interface DaemonImage {
    class Bytes(val bytes: ByteArray) : DaemonImage
    data object Missing : DaemonImage
    data class Failed(val reason: String) : DaemonImage
}

/** CHAT-IMAGE-REMOTE-1: loads an uploaded image's bytes from the daemon by upload id, over the client's own authenticated connection. */
fun interface DaemonImageSource {
    suspend fun fetch(id: String): DaemonImage
}

/** The connection's [DaemonImageSource]; null outside an app (a test or preview), where only local files load. */
val LocalDaemonImages = staticCompositionLocalOf<DaemonImageSource?> { null }

/** CHAT-IMAGE-REMOTE-1: upload ids are the stored file names, `<sha256>.<ext>`. */
object UploadIds {
    private val ID = Regex("^[0-9a-f]{64}\\.(png|jpg|gif|webp)$")

    /** The upload id [path] names (its last segment, either separator), or null when it names no upload. */
    fun of(path: String): String? = path.substringAfterLast('/').substringAfterLast('\\').lowercase().takeIf { ID.matches(it) }
}

/** What an image shown in the client came to: still [Loading], [Shown], [Expired] (removed by retention) or [Failed] (retry). */
sealed interface ImageLoad {
    data object Loading : ImageLoad
    class Shown(val bitmap: ImageBitmap) : ImageLoad
    data object Expired : ImageLoad
    data class Failed(val reason: String) : ImageLoad
}

/**
 * CHAT-IMAGE-REMOTE-1: the image at [path], loaded lazily (only once composed). [inline] bytes win; then the file when this host can
 * read it (the daemon's own machine); else, when [path] names an upload, its bytes fetched from the daemon by id (cached by the
 * source), so a remote client (phone, browser) shows it too. [attempt] changes to retry a failed load. "Expired" is said only when
 * the daemon (or, for a non-upload path, this host) answers that the file is gone.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun rememberDaemonImage(path: String?, inline: ByteArray? = null, attempt: Int = 0): ImageLoad {
    val host = LocalPlatformHost.current
    val source = LocalDaemonImages.current
    val load by produceState<ImageLoad>(ImageLoad.Loading, path, inline, attempt, source) {
        value = loadImage(path, inline, host::readBytes, host::fileExists, source)
    }
    return load
}

@OptIn(ExperimentalResourceApi::class)
internal suspend fun loadImage(
    path: String?,
    inline: ByteArray?,
    readLocal: (String) -> ByteArray?,
    existsLocal: (String) -> Boolean?,
    source: DaemonImageSource?,
    decodeBitmap: (ByteArray) -> ImageBitmap = { it.decodeToImageBitmap() },
): ImageLoad {
    fun decode(bytes: ByteArray): ImageLoad = try {
        ImageLoad.Shown(decodeBitmap(bytes))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ImageLoad.Failed("not a readable image")
    }
    inline?.let { return withContext(Dispatchers.Default) { decode(it) } }
    if (path == null) return ImageLoad.Failed("no image")
    withContext(Dispatchers.Default) { readLocal(path) }?.let { return withContext(Dispatchers.Default) { decode(it) } }
    val id = UploadIds.of(path)
    if (id != null && source != null) {
        return when (val got = source.fetch(id)) {
            is DaemonImage.Bytes -> withContext(Dispatchers.Default) { decode(got.bytes) }
            DaemonImage.Missing -> ImageLoad.Expired
            is DaemonImage.Failed -> ImageLoad.Failed(got.reason)
        }
    }
    // No daemon to ask: only a host that can look and finds nothing says expired; one that cannot look (null) cannot tell.
    return if (withContext(Dispatchers.Default) { existsLocal(path) } == false) ImageLoad.Expired else ImageLoad.Failed("cannot read ${path.takeLast(60)}")
}
