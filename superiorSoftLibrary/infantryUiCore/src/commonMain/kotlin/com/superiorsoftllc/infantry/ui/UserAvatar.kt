package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.generated.resources.Res
import com.superiorsoftllc.infantry.ui.model.UserPortrait
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/** The daemon's base64 `user.portrait` for the composition below it; null = unset, so the bundled default shows. */
val LocalUserPortrait = compositionLocalOf<String?> { null }

/** Semantics: which picture a [UserAvatar] shows, [USER_PORTRAIT_DEFAULT] or [USER_PORTRAIT_CUSTOM]. */
val UserPortraitSource = SemanticsPropertyKey<String>("UserPortraitSource")
var SemanticsPropertyReceiver.userPortraitSource by UserPortraitSource

const val USER_PORTRAIT_DEFAULT = "default"
const val USER_PORTRAIT_CUSTOM = "custom"

/**
 * USER-PORTRAIT: the human's own avatar (chat bubbles): the portrait stored with the daemon ([LocalUserPortrait]), else
 * the bundled default, drawn as a circular crop like [AgentAvatar]. The "Y" initials disc is only the fallback while
 * a picture is still decoding or cannot be.
 */
@Composable
fun UserAvatar(size: Int = 22, modifier: Modifier = Modifier, portrait: String? = LocalUserPortrait.current) {
    val custom = remember(portrait) { UserPortrait.decode(portrait) }
    val bitmap = rememberUserBitmap(custom)
    Box(
        modifier.size(size.dp).clip(CircleShape).testTag(AVATAR_TAG).semantics {
            userPortraitSource = if (custom != null && bitmap != null) USER_PORTRAIT_CUSTOM else USER_PORTRAIT_DEFAULT
            contentDescription = "your portrait"
        },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            androidx.compose.material3.Text("Y", color = Color.White, fontSize = (size / 2.4).sp)
        }
    }
}

private val defaultBitmapCache = arrayOfNulls<ImageBitmap>(1)
private var defaultFailed = false

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun rememberUserBitmap(custom: ByteArray?): ImageBitmap? {
    val bitmap by produceState(if (custom == null) defaultBitmapCache[0] else null, custom) {
        val decoded = try {
            if (custom != null) {
                custom.decodeToImageBitmap()
            } else if (defaultFailed) {
                null
            } else {
                defaultBitmapCache[0] ?: Res.readBytes("drawable/user_portrait.png").decodeToImageBitmap().also { defaultBitmapCache[0] = it }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A stored picture that will not decode falls back to the bundled one rather than to the placeholder.
            if (custom != null) {
                runCatching {
                    defaultBitmapCache[0] ?: Res.readBytes("drawable/user_portrait.png").decodeToImageBitmap().also { defaultBitmapCache[0] = it }
                }.getOrNull()
            } else {
                defaultFailed = true
                null
            }
        }
        value = decoded
    }
    return bitmap
}
