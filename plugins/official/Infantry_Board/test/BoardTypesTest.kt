package com.superiorsoftllc.infantry.plugins.official.board

import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.shell.blocks.*

import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** NS2 (docs/NORTH-STAR-DESIGN.md 3a): Infantry_Board registers its 14 types and the shipped pane is built from them. */
class BoardTypesTest {
    @Test fun `the board module registers its 14 block types and the shipped pane renders as a page of them`() {
        val f = TabFactory(listOf(InfantryBoard))
        assertEquals(emptyList(), f.problems)
        val board = f.blockTypes.types.values.filter { it.module == "Infantry_Board" }.map { it.name }
        assertEquals(BoardTypes.all.keys.toList(), board)
        assertEquals(14, board.size)

        val root = f.pageJson(BoardBody)["root"] as JsonObject
        assertEquals(JsonPrimitive(BoardTypes.PANE), root["blockType"])
        assertEquals(JsonPrimitive(BOARD_PANE_NODE), root["id"])
        assertEquals(5, ((root["config"] as JsonObject)["columns"] as JsonArray).size)
        val children = (root["children"] as JsonArray).map { (it as JsonObject)["blockType"] }
        assertEquals(
            listOf(BoardTypes.FILTERS, BoardTypes.UNASSIGNED, BoardTypes.NEW_TICKET, BoardTypes.EMPTY, BoardTypes.COLUMNS).map(::JsonPrimitive),
            children,
        )
    }
}
