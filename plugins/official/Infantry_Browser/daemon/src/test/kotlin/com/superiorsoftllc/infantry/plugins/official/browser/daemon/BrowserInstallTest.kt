// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.browser.daemon

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserInstallTest {
    @Test fun detectsInstalledBrowser() {
        val dir = kotlin.io.path.createTempDirectory("pw").toFile()
        assertFalse(BrowserInstall.installed(dir))
        File(dir, "chromium-1254/chrome-win64").mkdirs()
        assertFalse(BrowserInstall.installed(dir))
        File(dir, "chromium-1254/chrome-win64/chrome.exe").writeText("x")
        assertTrue(BrowserInstall.installed(dir))
    }

    @Test fun cacheDirAndCommand() {
        assertEquals(File("C:/L/ms-playwright"), BrowserInstall.cacheDir({ if (it == "LOCALAPPDATA") "C:/L" else null }, "Windows 11", "h"))
        assertEquals(File("/p"), BrowserInstall.cacheDir({ if (it == "PLAYWRIGHT_BROWSERS_PATH") "/p" else null }, "Linux", "h"))
        assertEquals(listOf("cmd", "/c", "npx"), BrowserInstall.command("Windows 11").take(3))
        assertEquals("install-browser", BrowserInstall.command("Linux")[3])
    }
}
