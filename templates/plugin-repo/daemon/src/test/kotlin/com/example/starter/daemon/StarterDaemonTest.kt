// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.example.starter.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.Sessions
import com.superiorsoftllc.infantry.daemon.api.Tools
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The daemon half registers the tool module.json declares.
class StarterDaemonTest {
    private inline fun <reified T : Any> stub(crossinline answer: (String, Array<Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, args -> answer(m.name, args) } as T

    @Test
    fun `registers the declared read tool`() {
        val registered = mutableListOf<DaemonTool>()
        val tools = stub<Tools> { name, args -> if (name == "register") registered += args!![0] as DaemonTool; null }
        val sessions = stub<Sessions> { name, _ -> if (name == "list") emptyList<Any>() else error("unexpected $name") }
        val api = stub<DaemonApi> { name, _ -> when (name) { "getTools" -> tools; "getSessions" -> sessions; else -> error("unexpected $name") } }

        StarterDaemon().start(api)

        val tool = registered.single()
        assertEquals("example_sessions", tool.name)
        // Gradle runs the daemon half's tests in daemon/, next to the plugin's module.json one folder up.
        assertTrue(File("../module.json").readText().contains("\"${tool.name}\""), "module.json declares the tool")
    }
}
