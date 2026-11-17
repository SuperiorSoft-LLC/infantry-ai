// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.acme.triggeraction

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerActionRecord
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerActionResult
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerFilter
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerSaveRequest
import com.superiorsoftllc.infantry.daemon.api.triggers.Triggers

/**
 * TRIG-ACTIONS-1: the reference plugin-defined trigger action. It registers the kind `acme.log` (what the trigger does when it
 * fires: here, one log line; the handler's result becomes the event's disposition, `delivered`) and, once the daemon is up,
 * creates ONE owned one-shot `at` trigger that uses the kind. The trigger shows in the Triggers tab as "from Acme_TriggerAction";
 * "Fire now" there runs the handler too. The kind goes away when this plugin unloads (the handle closes).
 */
class TriggerActionDaemon : DaemonPlugin {
    private var handle: AutoCloseable? = null

    override fun start(api: DaemonApi) {
        // null = no trigger plugin on this daemon: this plugin simply has nothing to do.
        val triggers = api.services.find(Triggers::class) ?: return api.log("no Triggers service; nothing registered")
        handle = triggers.registerAction(KIND) { fire ->
            api.log("acme.log fired: trigger ${fire.trigger.id} (${fire.trigger.name}) by ${fire.source}, run ${fire.runId}, note '${fire.trigger.action.prompt}'")
            TriggerActionResult.Done
        }
        api.whenStarted {
            if (triggers.list(TriggerFilter(owner = api.moduleId, ref = REF)).isEmpty()) {
                triggers.save(TriggerSaveRequest(
                    name = "Acme log demo", kind = "at", fireAt = System.currentTimeMillis() + 5 * 60_000L, deleteAfterFire = false,
                    action = TriggerActionRecord(kind = KIND, prompt = "hello from Acme_TriggerAction"), ref = REF,
                ))
            }
        }
    }

    override fun stop() {
        runCatching { handle?.close() }
    }

    private companion object {
        const val KIND = "acme.log"
        const val REF = "acme-log-demo"
    }
}
