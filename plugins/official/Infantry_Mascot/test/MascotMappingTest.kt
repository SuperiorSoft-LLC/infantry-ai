// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.mascot

import com.maxmaster.materialmascot.engine.BotState
import com.superiorsoftllc.infantry.ui.AvatarMotion
import com.superiorsoftllc.infantry.ui.AvatarRequest
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MascotMappingTest {
    private fun req(state: String?, detail: String? = null) = AvatarRequest(role = "frontend", state = state, busyDetail = detail)

    @Test fun everyStateInTheDefaultMapDrawsItsMood() {
        val expect = mapOf(
            SessionState.STARTING to BotState.Greet, SessionState.BUSY to BotState.Working, SessionState.WAITING_INPUT to BotState.Notify,
            SessionState.IDLE to BotState.Idle, SessionState.EXITED to BotState.Sleepy, SessionState.ORPHANED to BotState.Confused,
            SessionState.FAILED to BotState.Alert,
        )
        assertEquals(SessionState.entries.toSet(), expect.keys, "a new SessionState needs a default mood")
        for ((s, mood) in expect) assertEquals(mood, MascotMapping.stateFor(req(s.name)), s.name)
    }

    @Test fun noSessionAndUnknownStateDrawIdle() {
        assertEquals(BotState.Idle, MascotMapping.stateFor(req(null)))
        assertEquals(BotState.Idle, MascotMapping.stateFor(req("SOME_FUTURE_STATE")))
    }

    @Test fun detailsAndHappyFinish() {
        assertEquals(BotState.Reading, MascotMapping.stateFor(req("BUSY", "compacting")))
        assertEquals(BotState.Alert, MascotMapping.stateFor(req("IDLE", "error")))
        assertEquals(BotState.Happy, MascotMapping.stateFor(req("EXITED"), happy = true))
        assertEquals(BotState.Idle, MascotMapping.stateFor(req(null), happy = true))
    }

    @Test fun stateMapOverridesAndUnknownMoodFallsBackToIdle() {
        val map = MascotMapping.parseStateMap("busy=Thinking; IDLE=NoSuchMood; junk")
        assertEquals(BotState.Thinking, MascotMapping.stateFor(req("BUSY"), map))
        assertEquals(BotState.Idle, MascotMapping.stateFor(req("IDLE"), map))
        assertEquals(BotState.Greet, MascotMapping.stateFor(req("STARTING"), map))
    }

    @Test fun motionModes() {
        assertTrue(!MascotMapping.live("static", AvatarMotion.LIVE))
        assertTrue(MascotMapping.live("focus", AvatarMotion.LIVE))
        assertTrue(!MascotMapping.live("focus", AvatarMotion.STATIC))
        assertTrue(MascotMapping.live("all", AvatarMotion.STATIC))
        assertTrue(!MascotMapping.live(null, AvatarMotion.LIVE))
    }

    @Test fun looksAreDeterministicDifferAcrossRolesAndOverride() {
        val roles = listOf("frontend", "backend", "architect", "pm", "qa", "devops", "docs", "automation")
        assertEquals(MascotMapping.lookFor("pm"), MascotMapping.lookFor("pm"))
        assertTrue(roles.map { MascotMapping.lookFor(it) }.toSet().size > 3)
        val o = MascotMapping.parseRoleLooks("architect=round/chrome/cat")
        assertNotNull(o["architect"])
        assertEquals(true, MascotMapping.lookFor("Architect", o).cat)
    }
}
