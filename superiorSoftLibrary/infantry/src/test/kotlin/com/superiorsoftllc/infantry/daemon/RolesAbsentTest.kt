// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.StubScript
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** AR-4b-ii-b: with no Infantry_Roles plugin every role frame is refused (never hangs), and the testRoles seam provides the port only. */
class RolesAbsentTest {
    private val frames = mapOf(
        Envelope.ROLE_LIST to "", Envelope.ROLE_SAVE to """{"id":"x","name":"X"}""", Envelope.ROLE_DELETE to "x",
        Envelope.ROLE_BINDING_LIST to "", Envelope.ROLE_BINDING_GET to "x", Envelope.ROLE_BINDING_SET to """{"roleId":"x"}""",
        Envelope.ROLE_TRAITS_LIST to "", Envelope.ROLE_TRAITS_GET to "x", Envelope.ROLE_TRAITS_SET to """{"roleId":"x","traitIds":[]}""",
    )

    @Test
    fun roleFramesAreRefusedWithThePluginAbsentText() {
        val none = TestDaemonPlugins.none(Files.createTempDirectory("roles-absent").toFile())
        val fixture = DaemonFixture.launch(StubScript.default(), pluginsRoot = none)
        try {
            fixture.client.await(Envelope.READY)
            frames.entries.forEachIndexed { i, (type, data) ->
                fixture.send(type, data)
                assertEquals("$type refused: this daemon has no roles", fixture.client.await(Envelope.ERROR, occurrence = i).data)
            }
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
