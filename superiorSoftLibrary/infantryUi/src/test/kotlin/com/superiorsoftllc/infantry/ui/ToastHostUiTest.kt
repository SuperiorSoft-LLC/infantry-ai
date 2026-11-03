package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ToastEvent
import com.superiorsoftllc.infantry.ui.model.ToastKind
import com.superiorsoftllc.infantry.ui.model.withToast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ToastHostUiTest {
    private fun toast(id: Int) = ToastEvent("t$id", id.toLong(), ToastKind.SUCCESS, "Title $id", "Body $id")

    @Test fun `at most three toasts show and the close button dismisses one`() = runDesktopComposeUiTest(500, 500) {
        var state by androidx.compose.runtime.mutableStateOf(AppState())
        setContent { InfantryTheme { ToastHost(state, onOpen = {}) } }
        state = (1..5).fold(state) { s, i -> s.withToast(toast(i)) }
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag(TOAST_TAG).fetchSemanticsNodes().size == 3 }
        onAllNodesWithTag(TOAST_CLOSE_TAG)[0].performClick()
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag(TOAST_TAG).fetchSemanticsNodes().size == 3 }
        assertEquals(3, onAllNodesWithTag(TOAST_TAG).fetchSemanticsNodes().size, "the next queued toast takes the freed slot")
    }
}
