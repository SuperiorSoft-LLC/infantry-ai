// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.triggers.daemon

import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerStore
import com.superiorsoftllc.infantry.protocol.TriggerAction
import com.superiorsoftllc.infantry.protocol.TriggerEventView
import com.superiorsoftllc.infantry.protocol.TriggerView

/**
 * TRIG-2b: [TriggerStore] over the adopted `triggers`, `trigger_events` and `trigger_webhooks` tables (core creates them; module.json
 * `daemon.storage.adopt`). Hand-written SQL with the same columns and semantics as core's TriggerRepository: upsert keeps the row's
 * pending mail id, delete drops events and secret with the row, insertEvent prunes to the newest `keep`.
 */
class StorageTriggerStore(private val db: PluginStorage) : TriggerStore {
    override fun upsert(trigger: TriggerView, intervalMs: Long?) {
        db.transaction {
            val pending = pendingMail(trigger.id)
            db.execute(
                "INSERT OR REPLACE INTO triggers(id, name, kind, action_kind, action_agent_id, enabled, state, coalesce_ms, ceiling_runs, " +
                    "ceiling_window_ms, created_at, tripped_at, tripped_reason, interval_ms, action_prompt, project_id, last_fired_at, next_fire_at, pending_mail_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(
                    trigger.id, trigger.name, trigger.kind, trigger.action.kind, trigger.action.agentId,
                    if (trigger.enabled) 1L else 0L, trigger.state, trigger.coalesceMs, trigger.ceilingRuns.toLong(),
                    trigger.ceilingWindowMs, trigger.createdAt, trigger.trippedAt, trigger.trippedReason, intervalMs,
                    trigger.action.prompt, trigger.projectId, trigger.lastFiredAt, trigger.nextFireAt, pending,
                ),
            )
        }
    }

    override fun get(id: String): TriggerView? = db.query("SELECT * FROM triggers WHERE id = ?", listOf(id)).firstOrNull()?.toView()

    override fun all(): List<TriggerView> = db.query("SELECT * FROM triggers ORDER BY created_at ASC, id ASC").map { it.toView() }

    override fun intervals(): Map<String, Long> = db.query("SELECT id, interval_ms FROM triggers ORDER BY created_at ASC, id ASC")
        .mapNotNull { row -> (row["interval_ms"] as? Number)?.let { (row["id"] as String) to it.toLong() } }
        .toMap()

    override fun delete(id: String): Boolean = db.transaction {
        val existed = get(id) != null
        db.execute("DELETE FROM trigger_events WHERE trigger_id = ?", listOf(id))
        db.execute("DELETE FROM trigger_webhooks WHERE trigger_id = ?", listOf(id))
        db.execute("DELETE FROM triggers WHERE id = ?", listOf(id))
        existed
    }

    override fun insertEvent(event: TriggerEventView, keep: Long) {
        db.transaction {
            db.execute(
                "INSERT OR REPLACE INTO trigger_events(event_id, trigger_id, received_at, source, disposition, delivery_id, digest) VALUES (?, ?, ?, ?, ?, ?, ?)",
                listOf(event.eventId, event.triggerId, event.receivedAt, event.source, event.disposition, event.deliveryId, event.digest),
            )
            db.execute(
                "DELETE FROM trigger_events WHERE trigger_id = ? AND event_id NOT IN (" +
                    "SELECT event_id FROM trigger_events WHERE trigger_id = ? ORDER BY received_at DESC, event_id DESC LIMIT ?)",
                listOf(event.triggerId, event.triggerId, keep),
            )
        }
    }

    override fun events(triggerId: String): List<TriggerEventView> =
        db.query("SELECT * FROM trigger_events WHERE trigger_id = ? ORDER BY received_at ASC, event_id ASC", listOf(triggerId)).map {
            TriggerEventView(
                it["event_id"] as String, it["trigger_id"] as String, (it["received_at"] as Number).toLong(), it["source"] as String,
                it["disposition"] as String, it["delivery_id"] as String?, it["digest"] as String?,
            )
        }

    override fun setWebhookSecret(triggerId: String, secret: String) {
        db.execute("INSERT OR REPLACE INTO trigger_webhooks(trigger_id, secret) VALUES (?, ?)", listOf(triggerId, secret))
    }

    override fun webhookSecret(triggerId: String): String? =
        db.query("SELECT secret FROM trigger_webhooks WHERE trigger_id = ?", listOf(triggerId)).firstOrNull()?.get("secret") as String?

    override fun deleteWebhookSecret(triggerId: String) {
        db.execute("DELETE FROM trigger_webhooks WHERE trigger_id = ?", listOf(triggerId))
    }

    override fun pendingMail(triggerId: String): String? =
        db.query("SELECT pending_mail_id FROM triggers WHERE id = ?", listOf(triggerId)).firstOrNull()?.get("pending_mail_id") as String?

    override fun setPendingMail(triggerId: String, mailId: String?) {
        db.execute("UPDATE triggers SET pending_mail_id = ? WHERE id = ?", listOf(mailId, triggerId))
    }

    private fun Map<String, Any?>.toView() = TriggerView(
        id = this["id"] as String,
        name = this["name"] as String,
        kind = this["kind"] as String,
        action = TriggerAction(this["action_kind"] as String, this["action_agent_id"] as String?, this["action_prompt"] as String?),
        enabled = (this["enabled"] as Number).toLong() != 0L,
        state = this["state"] as String,
        coalesceMs = (this["coalesce_ms"] as Number).toLong(),
        ceilingRuns = (this["ceiling_runs"] as Number).toInt(),
        ceilingWindowMs = (this["ceiling_window_ms"] as Number).toLong(),
        createdAt = (this["created_at"] as Number).toLong(),
        trippedAt = (this["tripped_at"] as Number?)?.toLong(),
        trippedReason = this["tripped_reason"] as String?,
        intervalMs = (this["interval_ms"] as Number?)?.toLong(),
        projectId = this["project_id"] as String?,
        lastFiredAt = (this["last_fired_at"] as Number?)?.toLong(),
        nextFireAt = (this["next_fire_at"] as Number?)?.toLong(),
    )
}
