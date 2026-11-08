// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.health.HealthReport
import com.superiorsoftllc.infantry.daemon.health.HealthText
import com.superiorsoftllc.infantry.daemon.health.OfflineHealth
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.session.stateDir
import kotlinx.coroutines.runBlocking

/**
 * DAEMON-HEALTH-1: `infantry health [--json]`. Asks the daemon for its live report; when the daemon does not answer (down,
 * restarting, or only the MCP bridge broke) it reads the files the daemon keeps in the state dir and says so. Exit 0 =
 * daemon up, 1 = down.
 */
internal object HealthCli {
    private const val RECENT_EVENTS = 20

    fun run(target: Target, json: Boolean, out: Appendable, err: Appendable, getenv: (String) -> String?): Int {
        val report = live(target, err, getenv) ?: OfflineHealth.report(
            stateDir(getenv), alive = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }, eventTail = RECENT_EVENTS,
        )
        out.appendLine(if (json) report.toJson() else HealthText.render(report))
        return if (report.daemon.up) 0 else 1
    }

    private fun live(target: Target, err: Appendable, getenv: (String) -> String?): HealthReport? = try {
        val stream = FrameStream.open(target, err)
        runBlocking {
            val session = getenv("INFANTRY_SESSION_ID")?.takeIf { it.isNotBlank() }
            val data = if (session == null) "{}" else """{"sessionId":"${session.replace("\"", "")}"}"""
            val answer = stream.request(Envelope(Envelope.HEALTH, data), answerType = Envelope.HEALTH, what = "health", idempotent = true)
            answer.data.takeIf { it.isNotBlank() }?.let(HealthReport::parse)
        }
    } catch (_: Exception) {
        null
    }
}
