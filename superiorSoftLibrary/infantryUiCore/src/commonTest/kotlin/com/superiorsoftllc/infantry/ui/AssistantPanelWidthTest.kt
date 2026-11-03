// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AssistantPanelWidth
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ASSISTANT-1: the popup's width rules and the contract gate for the `assistant` frame. */
class AssistantPanelWidthTest {
    @Test fun `a drag left widens the docked popup and the width stays inside its bounds`() {
        assertEquals(AssistantPanelWidth.DEFAULT + 50f, AssistantPanelWidth.dragged(AssistantPanelWidth.DEFAULT, -50f))
        assertEquals(AssistantPanelWidth.MIN, AssistantPanelWidth.dragged(AssistantPanelWidth.DEFAULT, 10_000f))
        assertEquals(AssistantPanelWidth.MAX, AssistantPanelWidth.dragged(AssistantPanelWidth.DEFAULT, -10_000f))
    }

    @Test fun `only a 1_115_0 daemon has the assistant`() {
        assertFalse(ConnectionInfo(contract = "1.114.0").hasAssistant)
        assertTrue(ConnectionInfo(contract = "1.115.0").hasAssistant)
        assertTrue(ConnectionInfo(contract = "1.116.0").hasAssistant)
        assertFalse(ConnectionInfo(contract = null).hasAssistant)
    }
}
