package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.generated.resources.Res
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.AvatarSpec
import com.superiorsoftllc.infantry.ui.model.RolePortrait
import com.superiorsoftllc.infantry.ui.model.parseHexColor
import com.superiorsoftllc.infantry.ui.model.roleHue
import com.superiorsoftllc.infantry.ui.model.rolePortraitFor
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Test tag on every avatar disc: agent portraits and the user's own disc alike. */
const val AVATAR_TAG = "avatar"

/** Semantics: the [RolePortrait.key] an [AgentAvatar] resolved, whether or not the PNG has loaded yet. */
val PortraitKey = SemanticsPropertyKey<String>("PortraitKey")
var SemanticsPropertyReceiver.portraitKey by PortraitKey

/**
 * M2-1: THE avatar of an agent, used by every site that shows one (agents list, top strip, chat
 * header, chat speaker rows, board cards, roster, role pickers). Resolution is agent -> role ->
 * portrait ([rolePortraitFor]); the agent's name never enters it, so an agent named "1" with role
 * `pm` is the PM portrait everywhere.
 *
 * Until the PNG is decoded, or if it cannot be (missing resource, bad bytes), the disc falls back to
 * the role's initials on the role's colour: the daemon catalog's [catalogColor] when known, else a hue
 * derived from the role id; a session with no role gets a neutral disc labelled from [fallback].
 * Rings and status dots stay with the callers, drawn around/next to this circle as before.
 */
@Composable
fun AgentAvatar(role: String?, fallback: String, size: Int = 28, catalogColor: String? = null, modifier: Modifier = Modifier) {
    val portrait = rolePortraitFor(role)
    val bitmap = rememberPortrait(portrait)
    Box(
        modifier.size(size.dp).clip(CircleShape).testTag(AVATAR_TAG).semantics {
            portraitKey = portrait.key
            contentDescription = "${role ?: "no role"} avatar"
        },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            InitialsDisc(
                label = (role ?: fallback).take(2).uppercase(),
                color = when {
                    role == null -> Palette.Raised
                    else -> parseHexColor(catalogColor)?.let { Color(it) } ?: Color.hsv(roleHue(role), 0.45f, 0.62f)
                },
                textColor = if (role == null) Palette.Muted else Color.White,
                fontSize = (size / 2.6).sp,
            )
        }
    }
}

/** The same avatar for a live session: its role, its catalog colour, its id as the no-role label. */
@Composable
fun AgentAvatar(agent: AgentView, state: AppState, size: Int = 28, modifier: Modifier = Modifier) =
    AgentAvatar(agent.role, agent.id, size, state.role(agent.role)?.color, modifier)

/**
 * A plain initials disc from an [AvatarSpec]. Only the human ("You") is drawn with it now; agents
 * always go through [AgentAvatar].
 */
@Composable
fun AvatarBadge(spec: AvatarSpec, size: Int = 22, modifier: Modifier = Modifier) {
    Box(modifier.size(size.dp).clip(CircleShape).testTag(AVATAR_TAG), contentAlignment = Alignment.Center) {
        InitialsDisc(spec.initials, Color(spec.colorArgb), Color.White, (size / 2.4).sp)
    }
}

@Composable
private fun InitialsDisc(label: String, color: Color, textColor: Color, fontSize: TextUnit) {
    Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
        Text(label, color = textColor, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * Decoded portraits, shared by every avatar: a chat with hundreds of lines decodes each PNG once. A
 * failed load is cached as null, so a missing resource falls back to initials without retrying.
 * Touched only from the composition's own dispatcher.
 */
private val portraitCache = HashMap<RolePortrait, ImageBitmap?>()

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun rememberPortrait(portrait: RolePortrait): ImageBitmap? {
    val bitmap by produceState(portraitCache[portrait], portrait) {
        if (portraitCache.containsKey(portrait)) {
            value = portraitCache[portrait]
            return@produceState
        }
        val loaded = try {
            Res.readBytes(portrait.resourcePath).decodeToImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        portraitCache[portrait] = loaded
        value = loaded
    }
    return bitmap
}
