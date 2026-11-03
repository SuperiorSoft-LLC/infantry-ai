// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowCornersTest {
    @Test fun `rounded corners default on`() = assertTrue(WindowCorners.enabled { null })
    @Test fun `setting false turns them off`() = assertFalse(WindowCorners.enabled { if (it == "ui.window.roundedCorners") " False " else null })
    @Test fun `setting true or junk stays on`() { assertTrue(WindowCorners.enabled { "true" }); assertTrue(WindowCorners.enabled { "x" }) }
    @Test fun `only Windows 11 builds are rounded`() {
        assertTrue(WindowCorners.isWindows11("Windows 11", 22631))
        assertFalse(WindowCorners.isWindows11("Windows 10", 19045))
        assertFalse(WindowCorners.isWindows11("Linux", 99999))
        assertEquals(false, WindowCorners.apply(java.awt.Frame(), enabled = false))
    }
}
