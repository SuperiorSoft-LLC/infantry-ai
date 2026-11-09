// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.mascot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** MASCOT-LIVE-1: how long a static avatar morphs into a new pose after its state changes, then freezes again. */
const val SETTLE_MS = 900L

/** MASCOT-LIVE-1: default seconds between idle fidgets (a short random pose while the agent does nothing); 0 = never. Live avatars only. */
const val FIDGET_SEC = 12

/** MASCOT-LIVE-1: how long one fidget pose is held. */
const val FIDGET_HOLD_MS = 2_500L

/** MASCOT-LIVE-1: the poses an idle mascot picks from. */
val FIDGET_MOODS = listOf("Happy", "Greet", "Reading", "Sleepy", "Confused")

/** MASCOT-2: the `mascot` avatar style. Static by default (one frame per state); loops only as `mascot.motion` allows. */
object MascotAvatarRenderer : AgentAvatarRenderer {
    @Composable
    override fun Draw(request: AvatarRequest, modifier: Modifier) {
        val cfg = LocalShellContext.current?.config?.values(MASCOT_NODE)
        DrawMascot(request, cfg?.string("mascot.motion") ?: "static", cfg?.string("mascot.stateMap"), cfg?.string("mascot.roleLooks"), modifier, (cfg?.int("mascot.settleMs") ?: SETTLE_MS.toInt()).toLong(), cfg?.int("mascot.fidgetSec") ?: FIDGET_SEC)
    }
}

/** MASCOT-3: the drawing with the settings passed in, so a perf test can run it per motion mode without a shell. */
@Composable
internal fun DrawMascot(request: AvatarRequest, mode: String, stateText: String?, looksText: String?, modifier: Modifier, settleMs: Long = SETTLE_MS, fidgetSec: Int = FIDGET_SEC) {
    run {
        val live = MascotMapping.live(mode, request.motion)
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
        val baseState = MascotMapping.stateFor(request, map, happy)
        // MASCOT-LIVE-1: an idle live mascot now and then does a short random pose, so it is never a still picture.
        var fidget by remember { mutableStateOf<com.maxmaster.materialmascot.engine.BotState?>(null) }
        LaunchedEffect(live, baseState, fidgetSec) {
            fidget = null
            if (live && fidgetSec > 0 && baseState == com.maxmaster.materialmascot.engine.BotState.Idle) while (true) {
                delay((fidgetSec * 1000L * (0.6 + kotlin.random.Random.nextDouble() * 0.8)).toLong())
                val mood = FIDGET_MOODS.random()
                fidget = com.maxmaster.materialmascot.engine.BotState.all.firstOrNull { it.name.equals(mood, ignoreCase = true) }
                delay(FIDGET_HOLD_MS)
                fidget = null
            }
        }
        val state = fidget ?: baseState
        val body = parseHexColor(request.catalogColor)?.let { Color(it) }
            ?: Color.hsv(roleHue(request.role ?: request.label), 0.55f, 0.55f)
        // MASCOT-LIVE-1: a static avatar still morphs into a new pose (a short settle window after a state change), then
        // freezes again, so it is one tick per change, never a loop. It was a snapshot swap before.
        var shown by remember { mutableStateOf(state) }
        var settling by remember { mutableStateOf(false) }
        if (!live && shown != state) { shown = state; settling = settleMs > 0 }
        LaunchedEffect(state) {
            if (settling) {
                delay(settleMs)
                settling = false
            }
        }
        val animating = live || settling
        val config = MascotConfig(
            state = state, initialState = if (animating) shown else state, color = body, size = request.sizeDp.dp, motion = animating,
            energy = 1.3f, eyeStyle = look.eyes, finish = look.finish, cat = look.cat,
        )
        val description = request.label.ifBlank { request.role }
        MaterialBot(config, modifier, description, frozen = !animating)
    }
}
