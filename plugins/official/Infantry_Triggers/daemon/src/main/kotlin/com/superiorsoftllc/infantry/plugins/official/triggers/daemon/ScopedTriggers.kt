// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.triggers.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityHandler
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerActionHandler
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerActionRecord
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerFilter
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerOwnership
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerRecord
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerSaveRequest
import com.superiorsoftllc.infantry.daemon.api.triggers.Triggers
import com.superiorsoftllc.infantry.protocol.TriggerAction
import com.superiorsoftllc.infantry.protocol.TriggerEventView
import com.superiorsoftllc.infantry.protocol.TriggerSave
import com.superiorsoftllc.infantry.protocol.TriggerView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerEventRecord as ApiEventRecord

/**
 * TRIG-2c: the [Triggers] service as one calling module sees it (`services.provideScoped`: [callerModuleId] is the host's word).
 * Every write is stamped with, and checked against, the caller by [TriggerOwnership]: a forged owner is refused, and only a
 * trigger's owner may change it (a person's trigger is changed in the app).
 *
 * The service runs as moved (TRIG-2c is a move): it persists no owner yet, so a trigger a module saves is stored owner-less and the
 * module cannot change it afterwards. Persisting `owner` / `ref` (a column on the adopted table) and the kind `at` are TRIG-AT-1;
 * plugin action kinds ([registerAction]) are TRIG-ACTIONS-1.
 */
internal class ScopedTriggers(
    private val service: TriggerService,
    private val callerModuleId: String,
    private val onDeleted: () -> Unit = {},
) : Triggers {
    override fun list(filter: TriggerFilter): List<TriggerRecord> = service.list().map { it.toRecord() }.filter { r ->
        (filter.owner == null || r.owner == filter.owner) && (filter.ref == null || r.ref == filter.ref) &&
            (filter.projectId == null || r.projectId == filter.projectId) && (filter.kind == null || r.kind == filter.kind)
    }

    override fun get(id: String): TriggerRecord? = service.get(id)?.toRecord()

    override fun save(request: TriggerSaveRequest): TriggerRecord {
        val stamped = TriggerOwnership.stamp(request, callerModuleId)
        stamped.id?.trim()?.takeIf { it.isNotEmpty() }?.let { owned(it) }
        return service.save(stamped.toWire()).toRecord()
    }

    override fun delete(id: String): Boolean {
        val existing = service.get(id) ?: return false
        TriggerOwnership.requireChange(existing.toRecord(), callerModuleId)
        service.delete(existing.id)
        onDeleted()
        return true
    }

    override fun fire(id: String, source: String?): TriggerRecord {
        owned(id)
        return service.fire(id, source = source?.takeIf { it.isNotBlank() } ?: callerModuleId).toRecord()
    }

    override fun arm(id: String): TriggerRecord {
        owned(id)
        return service.arm(id).toRecord()
    }

    override fun disable(id: String): TriggerRecord {
        owned(id)
        return service.disable(id).toRecord()
    }

    override fun events(id: String): List<ApiEventRecord> = service.events(id).map { it.toRecord() }

    override fun registerAction(kind: String, handler: TriggerActionHandler): AutoCloseable =
        throw UnsupportedOperationException("plugin action kinds are not served yet (TRIG-ACTIONS-1)")

    private fun owned(id: String) {
        val existing = service.get(id) ?: throw IllegalArgumentException("no trigger '${id.take(80)}'")
        TriggerOwnership.requireChange(existing.toRecord(), callerModuleId)
    }
}

/**
 * TRIG-2c: the `triggers.list|save|delete|fire` capabilities (module.json `capabilities`, runs: daemon). The host gates each call by its
 * declared policy action (the same actions as the `trigger_*` frames) before the handler runs; the handler answers as the frames do.
 * A capability caller is a person's agent, script or the CLI, so these act on the board's triggers like the Triggers tab.
 */
internal class TriggerCapabilities(
    private val service: TriggerService,
    private val onDeleted: () -> Unit,
) {
    fun register(api: DaemonApi) {
        api.capabilities.handle(LIST, CapabilityHandler { call ->
            val kind = call.args.str("kind")
            val project = call.args.str("projectId")
            JsonArray(service.list().filter { (kind == null || it.kind == kind) && (project == null || it.projectId == project) }.map { it.toJson() })
        })
        api.capabilities.handle(SAVE, CapabilityHandler { call -> service.save(TriggerSave.decode(call.args.toString())).toJson() })
        api.capabilities.handle(DELETE, CapabilityHandler { call ->
            val id = call.args.str("id") ?: throw IllegalArgumentException("id is required")
            service.delete(id)
            onDeleted()
            buildJsonObject { put("deleted", id.trim()) }
        })
        api.capabilities.handle(FIRE, CapabilityHandler { call ->
            val id = call.args.str("id") ?: throw IllegalArgumentException("id is required")
            service.fire(id).toJson()
        })
    }

    companion object {
        const val LIST = "triggers.list"
        const val SAVE = "triggers.save"
        const val DELETE = "triggers.delete"
        const val FIRE = "triggers.fire"
    }
}

internal fun TriggerView.toRecord() = TriggerRecord(
    id = id, name = name, kind = kind, action = TriggerActionRecord(action.kind, action.agentId, action.prompt), enabled = enabled,
    state = state, coalesceMs = coalesceMs, ceilingRuns = ceilingRuns, ceilingWindowMs = ceilingWindowMs, createdAt = createdAt,
    trippedAt = trippedAt, trippedReason = trippedReason, intervalMs = intervalMs, projectId = projectId, lastFiredAt = lastFiredAt,
    nextFireAt = nextFireAt, fireAt = fireAt, deleteAfterFire = deleteAfterFire, owner = owner, ref = ref,
)

internal fun TriggerEventView.toRecord() = ApiEventRecord(eventId, triggerId, receivedAt, source, disposition, deliveryId, digest)

internal fun TriggerSaveRequest.toWire() = TriggerSave(
    id = id, name = name, kind = kind, enabled = enabled, coalesceMs = coalesceMs, ceilingRuns = ceilingRuns, ceilingWindowMs = ceilingWindowMs,
    action = TriggerAction(action.kind, action.agentId, action.prompt), intervalMs = intervalMs, webhookSecret = webhookSecret,
    projectId = projectId, fireAt = fireAt, deleteAfterFire = deleteAfterFire, owner = owner, ref = ref,
)
