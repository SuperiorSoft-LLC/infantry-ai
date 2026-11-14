// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.browser.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.StatusHook
import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.NotificationLevel
import com.superiorsoftllc.infantry.plugin.api.ProgressSpec
import java.util.concurrent.TimeUnit

/**
 * BROWSER-1: the browser for agents; the MCP server is declared in module.json (`daemon.mcpServers`).
 * BROWSER-INSTALL-1: the server needs Playwright's browser binary. On start, in the background (never blocking the daemon),
 * check for it and install it through the official CLI. State shows in the health `status` rows and a progress task; a failure
 * posts a notice with the error.
 */
class BrowserDaemonPlugin : DaemonPlugin {
    @Volatile private var state = "checking"
    @Volatile private var detail = ""
    private var worker: Thread? = null

    override fun start(api: DaemonApi) {
        api.hooks.status(StatusHook { mapOf("browserInstall" to state, "browserInstallDetail" to detail) })
        val t = Thread({ ensure(api) }, "browser-install")
        t.isDaemon = true
        worker = t
        t.start()
    }

    override fun stop() { worker?.interrupt() }

    private fun ensure(api: DaemonApi) {
        val os = System.getProperty("os.name") ?: ""
        val cache = BrowserInstall.cacheDir({ api.env(it) ?: System.getenv(it) }, os, System.getProperty("user.home") ?: ".")
        val envOf = { n: String -> api.env(n) ?: System.getenv(n) }
        val choice = (envOf("INFANTRY_BROWSER") ?: "auto").lowercase()
        if (choice == "chrome" || choice == "msedge" || (choice == "auto" && BrowserInstall.systemBrowser(envOf, os))) { state = "ready"; detail = "system browser"; return }
        if (BrowserInstall.installed(cache)) { state = "ready"; return }
        state = "installing"; detail = "downloading ${BrowserInstall.BROWSER}; browser calls fail until it is ready"
        val task = api.progress.start(ProgressSpec(title = "Installing browser (${BrowserInstall.BROWSER})", id = "browser-install"))
        try {
            val p = ProcessBuilder(BrowserInstall.command(os)).redirectErrorStream(true).start()
            val tail = ArrayDeque<String>()
            val reader = Thread { p.inputStream.bufferedReader().forEachLine { l -> synchronized(tail) { tail.addLast(l); if (tail.size > TAIL_LINES) tail.removeFirst() } } }
            reader.isDaemon = true; reader.start()
            if (!p.waitFor(INSTALL_TIMEOUT_MIN, TimeUnit.MINUTES)) { p.destroyForcibly(); throw IllegalStateException("install timed out after $INSTALL_TIMEOUT_MIN min") }
            reader.join(JOIN_MS)
            if (p.exitValue() != 0 || !BrowserInstall.installed(cache)) {
                throw IllegalStateException("exit ${p.exitValue()}: " + synchronized(tail) { tail.joinToString(" | ") })
            }
            state = "ready"; detail = ""
            task.done()
        } catch (e: Throwable) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            state = "failed"; detail = e.message ?: e.toString()
            task.fail(detail)
            api.log("browser install failed: $detail")
            api.notifications.notify(Notification(title = "Browser install failed", body = detail, level = NotificationLevel.ERROR, dedupeKey = "browser-install-failed"))
        }
    }

    private companion object {
        const val INSTALL_TIMEOUT_MIN = 15L
        const val TAIL_LINES = 5
        const val JOIN_MS = 2000L
    }
}
