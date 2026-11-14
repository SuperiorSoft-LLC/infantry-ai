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

    /** True when system Chrome or Edge exists (the launcher in module.json uses it first, so no download is needed). */
    fun systemBrowser(env: (String) -> String?, os: String): Boolean {
        val rel = when {
            os.contains("win", true) -> listOf("Google/Chrome/Application/chrome.exe", "Microsoft/Edge/Application/msedge.exe")
            else -> emptyList()
        }
        val bases = listOf("PROGRAMFILES", "PROGRAMFILES(X86)", "LOCALAPPDATA").mapNotNull { env(it) }
        if (rel.isNotEmpty()) return bases.any { b -> rel.any { File(b, it).isFile } }
        val abs = if (os.contains("mac", true)) listOf("/Applications/Google Chrome.app", "/Applications/Microsoft Edge.app")
        else listOf("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/opt/google/chrome/chrome", "/usr/bin/microsoft-edge", "/opt/microsoft/msedge/msedge")
        return abs.any { File(it).exists() }
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
