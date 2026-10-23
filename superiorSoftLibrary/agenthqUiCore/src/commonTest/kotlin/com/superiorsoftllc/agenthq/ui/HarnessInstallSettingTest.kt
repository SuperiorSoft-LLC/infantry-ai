package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.HarnessInstallSetting
import com.superiorsoftllc.agenthq.ui.protocol.HarnessInstallStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HARNESS-INSTALL-1: the Configuration modal's words and button for each daemon-reported state. */
class HarnessInstallSettingTest {
    private fun s(state: String, version: String? = null, note: String? = null) =
        HarnessInstallStatus(id = "claude", state = state, installedVersion = version, note = note, latest = "3.0.0")

    @Test
    fun missingOffersInstallAndOutdatedOffersUpdate() {
        assertEquals("Install", HarnessInstallSetting.action(s("missing")))
        assertEquals("Update", HarnessInstallSetting.action(s("outdated", "1.0.0")))
        assertTrue(HarnessInstallSetting.isUpdate(s("outdated", "1.0.0")))
        assertEquals("Not installed", HarnessInstallSetting.statusText(s("missing")))
        assertEquals("Outdated 1.0.0 (want 3.0.0)", HarnessInstallSetting.statusText(s("outdated", "1.0.0")))
    }

    @Test
    fun anInstalledOrUserManagedCliGetsNoInstallButton() {
        assertNull(HarnessInstallSetting.action(s("installed", "2.0.0")))
        assertNull(HarnessInstallSetting.action(s("user-managed", "2.0.0")))
        assertEquals("Installed 2.0.0", HarnessInstallSetting.statusText(s("installed", "2.0.0")))
        assertEquals("Your install: works 2.0.0", HarnessInstallSetting.statusText(s("user-managed", "2.0.0")))
        assertEquals("Your install: path is gone", HarnessInstallSetting.statusText(s("user-managed", note = "path is gone")))
    }
}
