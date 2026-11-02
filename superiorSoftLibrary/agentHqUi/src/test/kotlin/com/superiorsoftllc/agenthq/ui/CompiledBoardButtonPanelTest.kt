package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.board.BOARD_BUTTON_PANEL
import com.superiorsoftllc.agenthq.plugins.official.board.BoardPlugin
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.ui.plugin.Panels
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** ANDROID-BOARD-BTN-1: web/Android compile Infantry_Board in as a module list; its Board button must be in the left.board slot there too. */
class CompiledBoardButtonPanelTest {
    @Test
    fun `the compiled-in Board module contributes the Board button to left board`() {
        val f = TabFactory(CoreModules.all + BoardPlugin().modules())
        assertEquals(listOf(BOARD_BUTTON_PANEL), f.panels(Panels.LEFT_BOARD).map { it.id })
    }

    @Test
    fun `without the Board module there is no Board button`() {
        assertEquals(emptyList(), TabFactory(CoreModules.all).panels(Panels.LEFT_BOARD).map { it.id })
    }
}
