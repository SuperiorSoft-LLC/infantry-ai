// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.mascot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.maxmaster.materialmascot.config.MascotConfig
import com.maxmaster.materialmascot.ui.MaterialBot
import com.superiorsoftllc.infantry.ui.AgentAvatarRenderer
import com.superiorsoftllc.infantry.ui.AvatarRequest
import com.superiorsoftllc.infantry.ui.model.parseHexColor
import com.superiorsoftllc.infantry.ui.model.roleHue
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import kotlinx.coroutines.delay

/** MASCOT-2: the config node of module.json. */
const val MASCOT_NODE = "infantry.mascot"

/** MASCOT-2: how long the mascot is Happy after a clean finish, when motion is live. */
const val HAPPY_MS = 3_000L

/** MASCOT-2: the `mascot` avatar style. Static by default (one frame per state); loops only as `mascot.motion` allows. */
object MascotAvatarRenderer : AgentAvatarRenderer {
    @Composable
    override fun Draw(request: AvatarRequest, modifier: Modifier) {
        val cfg = LocalShellContext.current?.config?.values(MASCOT_NODE)
        val mode = cfg?.string("mascot.motion") ?: "static"
        val live = MascotMapping.live(mode, request.motion)
        val stateText = cfg?.string("mascot.stateMap")
        val looksText = cfg?.string("mascot.roleLooks")
        val map = remember(stateText) { MascotMapping.parseStateMap(stateText) }
        val looks = remember(looksText) { MascotMapping.parseRoleLooks(looksText) }
        val look = MascotMapping.lookFor(request.role, looks)
        val exited = request.state.equals("EXITED", ignoreCase = true) && request.busyDetail != "error"
        val happy by produceState(false, exited, live) {
            value = exited && live
            if (value) {
                delay(HAPPY_MS)
                value = false
            }
        }
        val state = MascotMapping.stateFor(request, map, happy)
        val body = parseHexColor(request.catalogColor)?.let { Color(it) }
            ?: Color.hsv(roleHue(request.role ?: request.label), 0.55f, 0.55f)
        val config = MascotConfig(
            state = state, initialState = state, color = body, size = request.sizeDp.dp, motion = live,
            energy = 1.3f, eyeStyle = look.eyes, finish = look.finish, cat = look.cat,
        )
        val description = request.label.ifBlank { request.role }
        // Frozen: a new engine per state, so the pose is the settled one and nothing ticks.
        if (live) MaterialBot(config, modifier, description) else key(state) {
            MaterialBot(config, modifier, description, frozen = true)
        }
    }
}
