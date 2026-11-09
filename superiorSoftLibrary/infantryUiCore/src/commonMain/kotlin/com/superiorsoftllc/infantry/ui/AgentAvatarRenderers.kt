// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.superiorsoftllc.infantry.ui.plugin.config.Implementations

/**
 * AVATAR-SEAM-1: the registry an avatar style goes in. A module registers one with
 * `registrar.implementation(AGENT_AVATAR_RENDERERS, "<style key>", renderer)` and declares it under `implementations` in
 * its module.json; the window draws the style the [AGENT_AVATAR_PREF_KEY] preference names, and the built-in portrait
 * ([AGENT_AVATAR_PORTRAIT]) whenever that key is unset, names nothing registered (a disabled or removed plugin) or the
 * renderer fails. Core never knows any style by name, only the built-in one.
 */
const val AGENT_AVATAR_RENDERERS = "agent.avatar.renderers"

/** AVATAR-SEAM-1: the per-device UI preference holding the avatar style key; unset = the built-in [AGENT_AVATAR_PORTRAIT]. */
const val AGENT_AVATAR_PREF_KEY = "agentAvatarStyle"

/** AVATAR-SEAM-1: the built-in style, the role's bundled portrait; always present, so an avatar is never missing. */
const val AGENT_AVATAR_PORTRAIT = "portrait"

/**
 * AVATAR-SEAM-1: whether a call site allows an avatar to move. A renderer may animate only under [LIVE]; a list row, a
 * board card or a picker stays [STATIC], so the app never pays for an animation nobody needs.
 */
enum class AvatarMotion { STATIC, LIVE }

/**
 * AVATAR-SEAM-1: everything an avatar renderer may use. Plain data: a renderer never sees [com.superiorsoftllc.infantry.ui.model.AppState]
 * or a session, and a new field here is added with a default so an existing renderer keeps compiling.
 *
 * [role] is the identity (agent -> role, the same as the built-in portrait); [label] is the fallback text a renderer may
 * draw as initials; [catalogColor] is the role's daemon-catalog colour when known. [state] is the session's
 * [com.superiorsoftllc.infantry.ui.protocol.SessionState] NAME (`BUSY`, `IDLE`, ...) or null when the site knows no
 * session. It crosses the seam as a plain string, so a renderer keeps working when the daemon grows a state; an unknown
 * one draws as idle. [busyDetail] is the extra note a site has: `compacting` while the run compacts its context, `error`
 * after a failed turn.
 */
data class AvatarRequest(
    val role: String? = null,
    val label: String = "",
    val catalogColor: String? = null,
    val sizeDp: Int = 28,
    val state: String? = null,
    val busyDetail: String? = null,
    val motion: AvatarMotion = AvatarMotion.STATIC,
)

/**
 * AVATAR-SEAM-1: a plugin's drawing of one agent avatar. [AgentAvatar] resolves the request, reads the preference and
 * hands it to the renderer of the chosen style; a renderer that throws while composing is dropped for the built-in
 * portrait and reported as that module's fault (never fatal).
 */
fun interface AgentAvatarRenderer {
    /** Draw one avatar in a [request]'s size. Use [modifier] for size and clip, or draw into it as the style needs. */
    @Composable
    fun Draw(request: AvatarRequest, modifier: Modifier)
}

/** AVATAR-SEAM-1: a renderer a module registered for a style: the style key, the module that owns it, and the renderer. */
class AvatarStyleRenderer(val key: String, val module: String, val renderer: AgentAvatarRenderer)

/**
 * AVATAR-SEAM-1: the renderer [style] names, or null when the built-in portrait draws it: [style] is null or
 * [AGENT_AVATAR_PORTRAIT], nobody registered it (an uninstalled, disabled or unknown style), or what is registered under
 * that key is not an [AgentAvatarRenderer].
 */
fun Implementations.avatarStyleRenderer(style: String?): AvatarStyleRenderer? {
    if (style == null || style == AGENT_AVATAR_PORTRAIT) return null
    val renderer = get(AGENT_AVATAR_RENDERERS, style) as? AgentAvatarRenderer ?: return null
    return AvatarStyleRenderer(style, moduleOf(AGENT_AVATAR_RENDERERS, style) ?: style, renderer)
}

/**
 * AVATAR-SEAM-1: the avatar style every [AgentAvatar] draws with, provided once by the window from its
 * [AGENT_AVATAR_PREF_KEY] preference (a test provides its own). The built-in portrait when unset.
 */
val LocalAgentAvatarStyle = staticCompositionLocalOf { AGENT_AVATAR_PORTRAIT }

/**
 * AVATAR-PICKER-1: the style the user picked in the Appearance settings (a device-scope config field on the theme node),
 * published by the module that owns the picker. Null until the user picks one; the window then uses the
 * [AGENT_AVATAR_PREF_KEY] preference. A key nothing registered still draws the portrait ([avatarStyleRenderer]).
 */
object AvatarStyleChoice {
    var chosen: String? by mutableStateOf(null)
}
