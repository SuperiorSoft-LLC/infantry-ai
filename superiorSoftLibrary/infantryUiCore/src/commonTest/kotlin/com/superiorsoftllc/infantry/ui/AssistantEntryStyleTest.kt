package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AssistantEntryStyle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantEntryStyleTest {
    @Test fun labelShowsInTheFullBar() = assertTrue(AssistantEntryStyle.showLabel(compactBar = false))
    @Test fun labelDropsInTheCompactBar() = assertFalse(AssistantEntryStyle.showLabel(compactBar = true))
}
