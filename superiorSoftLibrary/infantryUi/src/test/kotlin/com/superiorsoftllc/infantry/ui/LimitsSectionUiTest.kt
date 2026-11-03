// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.LimitInfo
import com.superiorsoftllc.infantry.ui.model.UiFlags
import kotlin.test.Test
import kotlin.test.assertEquals

/** No-hard-coded-limits (contract 1.133.0): Configuration > Limits shows each daemon limit and sends a valid change. */
@OptIn(ExperimentalTestApi::class)
class LimitsSectionUiTest {
    private val pod = LimitInfo(
        "limits.concurrency.maxPerPod", 4, 4, "sessions", "How many live sessions a pod may grow by.", 0, null, "unlimited",
        "INFANTRY_LIMIT_CONCURRENCY_MAX_PER_POD",
    )

    @Test fun `the Limits tab lists a limit and Set sends only a value in range`() = runDesktopComposeUiTest(1000, 900) {
        val sent = mutableListOf<Pair<String, Long>>()
        setContent {
            ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}, limits = listOf(pod), onLimit = { k, v -> sent += k to v }, initialTab = ConfigTab.LIMITS)
        }
        onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.LIMITS.name).assertExists()
        onNodeWithTag(LIMIT_VALUE_TAG_PREFIX + pod.key).assertExists()
        onNodeWithTag(LIMIT_SET_TAG_PREFIX + pod.key).assertIsNotEnabled()

        onNodeWithTag(LIMIT_FIELD_TAG_PREFIX + pod.key).performTextClearance()
        onNodeWithTag(LIMIT_FIELD_TAG_PREFIX + pod.key).performTextInput("-3")
        onNodeWithTag(LIMIT_SET_TAG_PREFIX + pod.key).assertIsNotEnabled()

        onNodeWithTag(LIMIT_FIELD_TAG_PREFIX + pod.key).performTextClearance()
        onNodeWithTag(LIMIT_FIELD_TAG_PREFIX + pod.key).performTextInput("15")
        onNodeWithTag(LIMIT_SET_TAG_PREFIX + pod.key).assertIsEnabled().performClick()
        assertEquals(listOf(pod.key to 15L), sent)
    }

    @Test fun `without the daemon catalog the section says so`() = runDesktopComposeUiTest(1000, 900) {
        setContent { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}, limits = null, onLimit = { _, _ -> }, initialTab = ConfigTab.LIMITS) }
        onNodeWithTag(LIMITS_PENDING_TAG).assertExists()
    }
}
