// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.PluginEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** DAEMON-FRAMES-1: a daemon frame reaches only the modules that declared its type, as `daemon.<type>`. */
class DaemonFrameRelayTest {
    private val declaring = ModuleManifest(name = "A_One", version = "1.0.0", daemonFrames = listOf("thing_changed"), events = listOf("daemon.thing_changed"))
    private val other = ModuleManifest(name = "B_Two", version = "1.0.0", events = listOf("daemon.thing_changed"))

    @Test
    fun `only the declaring module hears the frame, with its body`() {
        val bus = SimpleEventBus()
        val got = mutableListOf<String>()
        val heardByOther = mutableListOf<String>()
        bus.subscribe("A_One", "daemon.thing_changed") { got += it.data["data"].orEmpty() }
        bus.subscribe("B_Two", "daemon.thing_changed") { heardByOther += it.data["data"].orEmpty() }
        assertEquals(1, DaemonFrameRelay.relay(bus, listOf(declaring, other), "thing_changed", """{"x":1}"""))
        assertEquals(listOf("""{"x":1}"""), got)
        assertEquals(emptyList(), heardByOther)
    }

    @Test
    fun `an undeclared frame type reaches nobody`() {
        val bus = SimpleEventBus()
        var n = 0
        bus.subscribe("A_One", "daemon.other") { n++ }
        assertEquals(0, DaemonFrameRelay.relay(bus, listOf(declaring, other), "other", "{}"))
        assertEquals(0, n)
    }
}
