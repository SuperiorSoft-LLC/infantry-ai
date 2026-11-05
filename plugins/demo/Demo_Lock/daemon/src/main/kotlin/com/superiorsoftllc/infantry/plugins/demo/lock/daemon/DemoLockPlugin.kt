// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.demo.lock.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import com.superiorsoftllc.infantry.daemon.api.ReadyHook
import com.superiorsoftllc.infantry.plugin.api.PolicyPreset

/**
 * DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md section 7): Demo_Lock's daemon half, the first real daemon plugin. It implements
 * a preset's `seats` (hook `seats`: core refuses every frame of a seated preset no loaded plugin claims) through the hooks:
 *  - `frameGate`: [DemoLock.frameRefusal], then the visitor turn limit;
 *  - `httpGate`, `mailGate`: [DemoLock.httpRefusal], [DemoLock.mailRefusal];
 *  - `admission`: the public visitor token, only while seated;
 *  - `spawnGate`: once seeded, no path creates a session;
 *  - `ready`: the pinned notice (the deployment's env over the preset's);
 *  - `turns`: a visitor turn that reached an agent is counted;
 *  - the public route `GET /demo/visitor-token`.
 * The lock follows the ACTIVE preset: seeded at start and again on every switch (idempotent), and off when the preset has
 * no seats. Seating's outcome goes to the owner in the `policy` frame ([com.superiorsoftllc.infantry.daemon.api.Policy.reportSeating]).
 */
class DemoLockPlugin : DaemonPlugin {
    private lateinit var api: DaemonApi
    @Volatile private var lock = DemoLock(false)
    /** True while [seat] creates the seat sessions, the one time the spawn gate lets a session through. */
    @Volatile private var seeding = false
    private var subscription: AutoCloseable? = null
    private var warmUp: Thread? = null

    override fun start(api: DaemonApi) {
        this.api = api
        api.policy.claimSeats()
        api.hooks.frameGate { frame, caller -> frameRefusal(frame, caller) }
        api.hooks.httpGate { method, path, webAsset -> lock.httpRefusal(method, path, webAsset) }
        api.hooks.admission { listOfNotNull(lock.takeIf { it.enabled }?.publicToken) }
        api.hooks.mailGate { from, to, allowCrossProject -> lock.mailRefusal(from, to, allowCrossProject) }
        api.hooks.spawnGate { _, _ -> if (lock.enabled && !seeding) DemoLock.SPAWN_REFUSAL else null }
        api.hooks.ready(object : ReadyHook {
            override fun notice(): String? = lock.takeIf { it.enabled }?.notice?.takeIf { it.isNotBlank() }
        })
        api.hooks.turns { frame, caller ->
            val l = lock
            if (l.enabled && frame.type in DemoLock.COUNTED_TURNS) l.turnLimit?.record(clientOf(caller, l))
        }
        // Ticket 9bf81983: the demo's public visitor token, so a visitor lands connected (404 when none).
        api.routes.handle("GET", DemoLock.VISITOR_TOKEN_PATH) {
            val visitor = lock.takeIf { it.enabled }?.publicToken
            if (visitor != null) HttpReply(body = visitor, headers = mapOf("Cache-Control" to "no-store"))
            else HttpReply(status = 404, body = "no demo visitor token")
        }
        // DEMO-CHECK-1: lock now (fail closed), seat once the whole daemon is up: the seats need the Roles port and the
        // seats' harness (opencode), which other modules provide and which are not all loaded while this start() runs.
        lock = DemoLock.of(api.policy.active(), api::env, api.stateDir)
        api.whenStarted {
            seat(api.policy.active())
            subscription = api.policy.onChange { seat(it) }
        }
    }

    override fun stop() {
        subscription?.close()
        warmUp?.interrupt()
    }

    /** The lock's scoping, then a visitor turn over this client's limit is refused before it reaches an agent. */
    private fun frameRefusal(frame: Frame, caller: Caller): String? {
        val l = lock
        if (!l.enabled) return null
        l.frameRefusal(frame, caller.activeSession, api.policy)?.let { return it }
        if (frame.type !in DemoLock.COUNTED_TURNS) return null
        return l.turnLimit?.refusal(clientOf(caller, l))
    }

    private fun clientOf(caller: Caller, l: DemoLock): String =
        DemoTurnLimit.clientIp(caller.remoteAddress.orEmpty(), caller.forwardedFor, l.trustedProxies)

    /**
     * The lock of [preset], seeded (idempotent) and warmed up. A seating failure is not fatal: the lock stays on with no
     * seated project or sessions, so every scoped frame is refused (fail closed), and the owner is told why.
     */
    @Synchronized
    private fun seat(preset: PolicyPreset) {
        warmUp?.interrupt()
        val next = DemoLock.of(preset, api::env, api.stateDir)
        lock = next
        if (!next.enabled) { api.policy.reportSeating(null); return }
        val error = runCatching {
            seeding = true
            try { next.seed(api.projects, api.sessions, api::log) } finally { seeding = false }
            warmUp = next.warmUp(api.sessions, api::log)
        }.exceptionOrNull()
        if (error != null) api.log("policy '${preset.id}': seating failed: ${error.message}")
        api.policy.reportSeating(error?.let { "seating failed: ${it.message ?: it::class.simpleName}" })
    }
}
