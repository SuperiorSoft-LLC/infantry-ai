// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.triggers.daemon

import com.superiorsoftllc.infantry.persistence.TriggerRepository
import com.superiorsoftllc.infantry.protocol.TriggerEventView
import com.superiorsoftllc.infantry.protocol.TriggerView

/**
 * TRIG-2c (tests only): the store interface moved into this plugin with the service; core's [TriggerRepository] keeps the same members,
 * so a test that runs the service on a real core database (or compares the two stores) goes through this.
 */
internal class CoreTriggerStore(private val r: TriggerRepository) : TriggerStore {
    override fun upsert(trigger: TriggerView, intervalMs: Long?) = r.upsert(trigger, intervalMs)
    override fun get(id: String) = r.get(id)
    override fun all() = r.all()
    override fun intervals() = r.intervals()
    override fun delete(id: String) = r.delete(id)
    override fun insertEvent(event: TriggerEventView, keep: Long) = r.insertEvent(event, keep)
    override fun events(triggerId: String) = r.events(triggerId)
    override fun setWebhookSecret(triggerId: String, secret: String) = r.setWebhookSecret(triggerId, secret)
    override fun webhookSecret(triggerId: String) = r.webhookSecret(triggerId)
    override fun deleteWebhookSecret(triggerId: String) = r.deleteWebhookSecret(triggerId)
    override fun pendingMail(triggerId: String) = r.pendingMail(triggerId)
    override fun setPendingMail(triggerId: String, mailId: String?) = r.setPendingMail(triggerId, mailId)
}
