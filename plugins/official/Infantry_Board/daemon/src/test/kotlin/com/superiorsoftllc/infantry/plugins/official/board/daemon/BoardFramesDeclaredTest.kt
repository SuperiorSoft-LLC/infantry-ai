// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.backlog.BoardFromRuntime
import com.superiorsoftllc.infantry.backlog.registerBoardFrames
import com.superiorsoftllc.infantry.daemon.api.FrameBinding
import com.superiorsoftllc.infantry.daemon.api.board.BoardEpicInputs
import com.superiorsoftllc.infantry.daemon.api.board.BoardHost
import com.superiorsoftllc.infantry.daemon.api.board.BoardRuntime
import com.superiorsoftllc.infantry.daemon.api.board.NoBoardHost
import com.superiorsoftllc.infantry.daemon.api.spawns.SpawnCharge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * BOARD-FRAMES-1: the daemon host refuses a frame a plugin answers without declaring it in module.json's daemon.frames, and that
 * refusal at start disables the whole board (build 1d635b91: ticket_assign / agent_switch). Every frame the board registers must be declared.
 */
class BoardFramesDeclaredTest {
    private object Runtime : BoardRuntime {
        override val host: BoardHost = NoBoardHost
        override val epics = object : BoardEpicInputs {
            override fun loadAll() = emptyList<String>()
            override fun upsert(epicJson: String) {}
        }
    }

    private fun registeredTypes(): Set<String> {
        val types = mutableSetOf<String>()
        val binding = object : FrameBinding {
            override fun register(types0: List<String>, handler: (com.superiorsoftllc.infantry.daemon.api.Frame, Boolean) -> com.superiorsoftllc.infantry.daemon.api.Frame?) {
                types += types0
            }
        }
        val charge = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SpawnCharge::class.java)) { _, _, _ -> error("unused") } as SpawnCharge
        val board = BoardFromRuntime.build(Runtime)
        val h = BoardBuild.handlers(Runtime, board)
        registerBoardFrames(binding, board, charge, h.epics, h.stories, h.storyBoard)
        return types + PIPELINE_FRAMES
    }

    private fun declared(): Map<String, Pair<String, String>> {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "plugins/official/Infantry_Board/module.json") }.first { it.isFile }
        val daemon = Json.parseToJsonElement(file.readText()).jsonObject.getValue("daemon").jsonObject
        return daemon.getValue("frames").jsonArray.associate {
            val o = it.jsonObject
            o.getValue("type").jsonPrimitive.content to (o.getValue("action").jsonPrimitive.content to o.getValue("kind").jsonPrimitive.content)
        }
    }

    @Test fun `every frame the board registers is declared in daemon frames`() {
        val registered = registeredTypes()
        assertTrue("ticket_assign" in registered && "agent_switch" in registered && "pipeline_check" in registered, "registered: $registered")
        val missing = registered - declared().keys
        assertTrue(missing.isEmpty(), "frames registered but not declared in Infantry_Board/module.json daemon.frames: $missing")
    }

    @Test fun `assign and switch are declared under the core action board ticket move as write`() {
        val d = declared()
        listOf("ticket_assign", "agent_switch").forEach { assertTrue(d[it] == ("board.ticket.move" to "write"), "$it declared as ${d[it]}") }
    }
}
