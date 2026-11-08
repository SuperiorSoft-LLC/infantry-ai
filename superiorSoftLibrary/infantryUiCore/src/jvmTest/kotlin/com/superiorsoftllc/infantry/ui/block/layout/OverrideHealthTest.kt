// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.block.layout

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * VOICEINPUT-BLANK-1: a verdict recorded for an EQUAL but not identical instance (the data-class copy a live plugin swap
 * makes of an unchanged registration) replaces the old one, so the new instance is drawn instead of its fallback for good.
 */
class OverrideHealthTest {
    private data class Registration(val id: String)

    @AfterTest fun clean() = OverrideHealth.clear()

    @Test fun anEqualCopysVerdictIsStoredAndMatchesTheCopy() {
        val first = Registration("feed")
        val copy = first.copy()
        OverrideHealth.record("section/m/feed", first, OverrideVerdict.Ok)
        assertNull(OverrideHealth.verdict("section/m/feed", copy), "a new instance is probed again")
        OverrideHealth.record("section/m/feed", copy, OverrideVerdict.Ok)
        assertEquals(OverrideVerdict.Ok, OverrideHealth.verdict("section/m/feed", copy))
        assertNull(OverrideHealth.verdict("section/m/feed", first))
    }
}
