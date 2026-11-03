// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.LifecycleSetting
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.WireRequests
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUG-M2-11e (plan section 8 / 11e): the one session-lifecycle setting in the Configuration modal. Its value is the
 * daemon's (`settings` frames), its labels are the plan's, and a change is a `settings_set` of `session.keepAliveMinutes`.
 */
class LifecycleSettingModelTest {
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }

    @Test fun `0 reads as off and N names the idle minutes`() {
        assertEquals("Off (start a process per reply)", LifecycleSetting.label(0))
        assertEquals("Keep each session's process for 30 idle minutes", LifecycleSetting.label(30))
        assertEquals("Keep each session's process for 1440 idle minutes", LifecycleSetting.label(1440))
    }

    @Test fun `a settings frame sets the value, a frame without the key leaves it, and a bad value is ignored`() {
        assertNull(AppState().keepAliveMinutes, "nothing is shown as the daemon's value before it answers")
        val all = """{"context.includeLinkedTasksContext":true,"context.skillIds":[],"session.keepAliveMinutes":30}"""
        val s = feed(Envelope(Envelope.SETTINGS, all))
        assertEquals(30, s.keepAliveMinutes)
        assertEquals(0, feed(Envelope(Envelope.SETTINGS, """{"session.keepAliveMinutes":0}"""), from = s).keepAliveMinutes)
        assertEquals(30, feed(Envelope(Envelope.SETTINGS, """{"context.skillIds":[]}"""), from = s).keepAliveMinutes)
        assertEquals(30, feed(Envelope(Envelope.SETTINGS, """{"session.keepAliveMinutes":"5"}"""), from = s).keepAliveMinutes)
        assertEquals(30, feed(Envelope(Envelope.SETTINGS, """{"session.keepAliveMinutes":9999}"""), from = s).keepAliveMinutes)
    }

    @Test fun `the set request names the daemon key with a JSON integer and the get asks for that key only`() {
        val set = WireRequests.settingsSet(LifecycleSetting.KEY, JsonPrimitive(15))
        assertEquals(Envelope.SETTINGS_SET, set.type)
        val body = Json.parseToJsonElement(set.data).jsonObject
        assertEquals("session.keepAliveMinutes", body.getValue("key").jsonPrimitive.content)
        val value = body.getValue("value").jsonPrimitive
        assertTrue(!value.isString, "the daemon refuses a string: $value")
        assertEquals(15, value.int)
        assertEquals(Envelope(Envelope.SETTINGS_GET, "session.keepAliveMinutes"), WireRequests.settingsGet(LifecycleSetting.KEY))
    }

    @Test fun `a stored value outside the presets is still offered, in order`() {
        assertEquals(LifecycleSetting.PRESETS, LifecycleSetting.options(null))
        assertEquals(LifecycleSetting.PRESETS, LifecycleSetting.options(30))
        val odd = LifecycleSetting.options(45)
        assertTrue(45 in odd)
        assertEquals(odd.sorted(), odd)
    }
}
