// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CONFIG-8: the client keeps the daemon's `config_*` frames, and a daemon older than contract 1.142.0 keeps the hand-written daemon sections. */
class DaemonConfigFramesTest {
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }

    @Test fun `schema, values and changed frames are kept and an old daemon sends none`() {
        assertNull(AppState().daemonConfigSchema, "nothing before the daemon sends it: the hand-written sections stay")
        val s = feed(
            Envelope(Envelope.CONFIG_SCHEMA, """{"rev":5,"declarations":[{"node":"daemon.x","fields":[{"key":"a.b","type":"number","scope":"daemon"}]}]}"""),
            Envelope(Envelope.CONFIG_VALUES, """{"rev":5,"rows":[{"nodeId":"daemon.x","key":"a.b","value":3}]}"""),
        )
        assertEquals("a.b", s.daemonConfigSchema!!.declarations.single().fields.single().key)
        val changed = feed(Envelope(Envelope.CONFIG_CHANGED, """{"node":"daemon.x","key":"a.b","value":4,"by":"client","rev":6}"""), from = s)
        assertEquals(JsonPrimitive(4), changed.daemonConfigValues!!.rows.single().value)
        assertEquals(6, changed.daemonConfigValues!!.rev)
        val reset = feed(Envelope(Envelope.CONFIG_CHANGED, """{"node":"daemon.x","key":"a.b","by":"client","rev":7}"""), from = changed)
        assertEquals(emptyList(), reset.daemonConfigValues!!.rows)
        assertEquals(s, feed(Envelope(Envelope.CONFIG_SCHEMA, "not json"), from = s), "a malformed frame changes nothing")
    }

    @Test fun `the contract gate keeps an older daemon on the hand-written sections`() {
        assertFalse(ConnectionInfo(contract = "1.141.0").servesConfigSchema)
        assertFalse(ConnectionInfo(contract = null).servesConfigSchema)
        assertTrue(ConnectionInfo(contract = "1.142.0").servesConfigSchema)
    }
}
