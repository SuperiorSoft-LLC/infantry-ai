// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.browser.daemon

import java.io.File

/** BROWSER-INSTALL-1: where Playwright keeps its browsers, whether the one the server drives is there, and the official install argv. */
object BrowserInstall {
    const val BROWSER = "chrome-for-testing"
    const val PACKAGE = "@playwright/mcp@0.0.85"

    /** Playwright's browser cache: PLAYWRIGHT_BROWSERS_PATH, else the per-OS default. */
    fun cacheDir(env: (String) -> String?, os: String, home: String): File {
        env("PLAYWRIGHT_BROWSERS_PATH")?.takeIf { it.isNotBlank() && it != "0" }?.let { return File(it) }
        return when {
            os.contains("win", true) -> File(env("LOCALAPPDATA") ?: File(home, "AppData/Local").path, "ms-playwright")
            os.contains("mac", true) -> File(home, "Library/Caches/ms-playwright")
            else -> File(env("XDG_CACHE_HOME") ?: File(home, ".cache").path, "ms-playwright")
        }
    }

    /** The channel the launcher in module.json passes as `--browser`; the same rules, so health and launch agree. [exists] tests a path. */
    fun channel(choice: String, env: (String) -> String?, os: String, exists: (String) -> Boolean = { File(it).exists() }): String {
        val win = os.contains("win", true)
        val mac = os.contains("mac", true)
        val bases = listOf("PROGRAMFILES", "PROGRAMFILES(X86)", "LOCALAPPDATA").mapNotNull { env(it) }
        fun any(winRel: String, abs: List<String>) = if (win) bases.any { exists(File(it, winRel).path) } else abs.any(exists)
        val chrome = any("Google/Chrome/Application/chrome.exe",
            if (mac) listOf("/Applications/Google Chrome.app") else listOf("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/opt/google/chrome/chrome"))
        val edge = any("Microsoft/Edge/Application/msedge.exe",
            if (mac) listOf("/Applications/Microsoft Edge.app") else listOf("/usr/bin/microsoft-edge", "/opt/microsoft/msedge/msedge"))
        var c = choice.lowercase()
        if ((c == "chrome" && !chrome) || (c == "msedge" && !edge)) c = "auto"
        if (c == "auto") c = if (chrome) "chrome" else if (edge) "msedge" else "chromium"
        return c
    }

    /** True when a chromium build in [cache] holds a chrome executable. */
    fun installed(cache: File): Boolean =
        cache.listFiles { f -> f.isDirectory && f.name.startsWith("chromium-") }.orEmpty().any { dir ->
            dir.listFiles { f -> f.isDirectory && f.name.startsWith("chrome-") }.orEmpty().any {
                File(it, "chrome.exe").isFile || File(it, "chrome").isFile || File(it, "Google Chrome for Testing.app").exists()
            }
        }

    /** The official CLI: `npx @playwright/mcp install-browser chrome-for-testing` (npx is a .cmd on Windows). */
    fun command(os: String): List<String> {
        val npx = listOf("npx", "-y", PACKAGE, "install-browser", BROWSER)
        return if (os.contains("win", true)) listOf("cmd", "/c") + npx else npx
    }
}
