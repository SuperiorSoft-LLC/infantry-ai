// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.triggers.daemon

import com.superiorsoftllc.infantry.protocol.TriggerEventView
import com.superiorsoftllc.infantry.protocol.TriggerView

/**
 * The durable trigger tables (rows, events, webhook secrets, the pending nudge mail), as the trigger service reads them.
 *
 * TRIG-2c: this port lived in the daemon API (`daemon.api.triggers.TriggerStore`, TRIG-1) and moved here with the service. Its
 * signatures name the wire DTOs, which each plugin shades into its own class loader; an SDK interface typed with them breaks the
 * JVM's loader constraints the moment a plugin implements it. [StorageTriggerStore] is the one implementation.
 */
interface TriggerStore {
    fun upsert(trigger: TriggerView, intervalMs: Long?)
    fun get(id: String): TriggerView?
    fun all(): List<TriggerView>
    fun intervals(): Map<String, Long>
    fun delete(id: String): Boolean
    fun insertEvent(event: TriggerEventView, keep: Long = 500)
    fun events(triggerId: String): List<TriggerEventView>
    fun setWebhookSecret(triggerId: String, secret: String)
    fun webhookSecret(triggerId: String): String?
    fun deleteWebhookSecret(triggerId: String)
    fun pendingMail(triggerId: String): String?
    fun setPendingMail(triggerId: String, mailId: String?)
}
