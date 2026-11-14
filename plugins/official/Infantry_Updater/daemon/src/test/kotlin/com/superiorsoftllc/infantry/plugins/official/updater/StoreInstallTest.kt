// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** STORE-1: a Microsoft Store (MSIX) install is detected and its window updater is off; an MSI install is untouched. */
class StoreInstallTest {
    private fun props(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    @Test
    fun `the msix package property marks a store install`() {
        assertTrue(StoreInstall.detected(props(StoreInstall.PACKAGE_PROPERTY to "msix")))
        assertTrue(StoreInstall.detected(props(StoreInstall.PACKAGE_PROPERTY to " MSIX ")))
    }

    @Test
    fun `a launcher under WindowsApps is a store install`() {
        val path = """C:\Program Files\WindowsApps\SuperiorSoftLLC.InfantryAI_1.5.3.0_x64__abc123\InfantryAI.exe"""
        assertTrue(StoreInstall.detected(props(StoreInstall.APP_PATH_PROPERTY to path)))
    }

    @Test
    fun `the per-user MSI install, a dev run and other package values are not`() {
        val msi = """C:\Users\me\AppData\Local\InfantryAI\InfantryAI.exe"""
        assertFalse(StoreInstall.detected(props(StoreInstall.APP_PATH_PROPERTY to msi)))
        assertFalse(StoreInstall.detected(props()))
        assertFalse(StoreInstall.detected(props(StoreInstall.PACKAGE_PROPERTY to "msi")))
        assertNull(StoreInstall.disabledReason(props(StoreInstall.APP_PATH_PROPERTY to msi)))
    }

    @Test
    fun `a store install turns the window updater off with the reason`() {
        val off = StoreInstall.disabledReason(props(StoreInstall.PACKAGE_PROPERTY to "msix"))
        assertEquals(UpdateSettings.Disabled(StoreInstall.REASON), off)
    }
}
