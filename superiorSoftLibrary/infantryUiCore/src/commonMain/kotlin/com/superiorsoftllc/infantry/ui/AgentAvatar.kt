// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import com.superiorsoftllc.infantry.ui.block.layout.GuardedBlock
import com.superiorsoftllc.infantry.ui.block.layout.OverrideHealth
import com.superiorsoftllc.infantry.ui.block.layout.OverrideVerdict
import com.superiorsoftllc.infantry.ui.generated.resources.Res
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.AvatarSpec
import com.superiorsoftllc.infantry.ui.model.RolePortrait
import com.superiorsoftllc.infantry.ui.model.parseHexColor
import com.superiorsoftllc.infantry.ui.model.roleHue
import com.superiorsoftllc.infantry.ui.model.rolePortraitFor
import com.superiorsoftllc.infantry.ui.plugin.PluginFaults
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
 *
 * AVATAR-SEAM-1: this and [AgentAvatar] for an [AgentView] are the two entry points; both build an [AvatarRequest] and draw
 * the style the window's [LocalAgentAvatarStyle] names (the built-in portrait by default, a plugin's renderer when one is
 * registered and chosen). [state] is the session state name when the site knows a session, else null; [motion] is whether
 * this site allows the avatar to move.
 */
@Composable
fun AgentAvatar(
    role: String?,
    fallback: String,
    size: Int = 28,
    catalogColor: String? = null,
    modifier: Modifier = Modifier,
    state: String? = null,
    busyDetail: String? = null,
    motion: AvatarMotion = AvatarMotion.STATIC,
) = AgentAvatar(AvatarRequest(role, fallback, catalogColor, size, state, busyDetail, motion), modifier)

/**
 * The same avatar for a live session: its role, its catalog colour, its id as the no-role label, and its state (so a
 * style that draws a mood has something to draw). [motion] is set only by the sites that hold the eye.
 */
@Composable
fun AgentAvatar(agent: AgentView, state: AppState, size: Int = 28, modifier: Modifier = Modifier, motion: AvatarMotion = AvatarMotion.STATIC) =
    AgentAvatar(
        AvatarRequest(
            role = agent.role,
            label = agent.id,
            catalogColor = state.role(agent.role)?.color,
            sizeDp = size,
            state = agent.session.state.name,
            busyDetail = when {
                agent.isCompacting -> "compacting"
                agent.lastError != null -> "error"
                else -> null
            },
            motion = motion,
        ),
        modifier,
    )

/**
 * AVATAR-SEAM-1: the avatar of [request] in the style the window chose: the renderer the [LocalAgentAvatarStyle]
 * preference names, else the built-in portrait. A renderer that throws is dropped for the portrait and reported (the
 * fault list, Settings > Plugins), so a faulty style never takes the window down and never blanks an avatar.
 */
@Composable
fun AgentAvatar(request: AvatarRequest, modifier: Modifier = Modifier) {
    val implementations = LocalTabFactory.current.implementations
    val style = implementations.avatarStyleRenderer(LocalAgentAvatarStyle.current)
    if (style == null) {
        PortraitAvatarRenderer.Draw(request, modifier)
        return
    }
    // GAP-AUDIT-1 (a): a plugin's composable is probed once before it is drawn; until then, and for good if it threw, the portrait.
    val key = OverrideHealth.key(style.module, "avatar", "$AGENT_AVATAR_RENDERERS/${style.key}")
    // MASCOT-PICKER-1: drawn as its module, so a community renderer reads config through its gate and a swapped module recomposes afresh.
    com.superiorsoftllc.infantry.ui.plugin.ModuleScope(style.module) {
        GuardedBlock(key, style.renderer, fallback = { PortraitAvatarRenderer.Draw(request, modifier) }) {
            style.renderer.Draw(request, modifier)
        }
    }
    (OverrideHealth.verdict(key, style.renderer) as? OverrideVerdict.Faulted)?.let { fault ->
        SideEffect { PluginFaults.record(style.module, IllegalStateException(fault.message), PluginFaults.generationLookup(style.module)) }
    }
}

/**
 * AVATAR-SEAM-1: the built-in avatar style ([AGENT_AVATAR_PORTRAIT]), today's drawing: the role's bundled portrait PNG,
 * else its initials on its colour. It ignores [AvatarRequest.state] and [AvatarRequest.motion], and it is what every
 * other style falls back to.
 */
object PortraitAvatarRenderer : AgentAvatarRenderer {
    @Composable
    override fun Draw(request: AvatarRequest, modifier: Modifier) {
        val portrait = rolePortraitFor(request.role)
        val bitmap = rememberPortrait(portrait)
        Box(
            modifier.size(request.sizeDp.dp).clip(CircleShape).testTag(AVATAR_TAG).semantics {
                portraitKey = portrait.key
                contentDescription = "${request.role ?: "no role"} avatar"
            },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                InitialsDisc(
                    label = (request.role ?: request.label).take(2).uppercase(),
                    color = when {
                        request.role == null -> Palette.Raised
                        else -> parseHexColor(request.catalogColor)?.let { Color(it) } ?: Color.hsv(roleHue(request.role), 0.45f, 0.62f)
                    },
                    textColor = if (request.role == null) Palette.Muted else Color.White,
                    fontSize = (request.sizeDp / 2.6).sp,
                )
            }
        }
    }
}

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
