// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.triggers

/*
 * TRIG-API-1 (DaemonApi 1.65.0, additive; plan CAL-PLAN-2 A1): triggers as a first-class SDK service. The plugin that runs the
 * trigger service publishes [Triggers] with `services.provideScoped` (so it learns who calls); any module finds it with
 * `services.find(Triggers::class)`. Null = this daemon runs no triggers: the caller owns its fail mode.
 *
 * The DTOs below are the API's own, never the wire module's (`com.superiorsoftllc.infantry.protocol.Trigger*`): a plugin's class
 * loader sees the daemon API but not the protocol (each plugin shades its own copy), so a cross-plugin service typed with protocol
 * classes would fail the JVM's loader constraints. They mirror the wire DTOs field for field; the provider maps between them.
 */

/** Found with `services.find(Triggers::class)`; published by the plugin that runs the trigger service. */
interface Triggers {
    /** Every trigger [filter] matches (an empty filter = all), oldest first. */
    fun list(filter: TriggerFilter = TriggerFilter()): List<TriggerRecord>

    /** One trigger, or null when [id] names none. */
    fun get(id: String): TriggerRecord?

    /**
     * Create (no [TriggerSaveRequest.id]) or update. The owner is stamped from the CALLING module ([TriggerOwnership.stamp]) and
     * cannot be forged: a request naming another owner is refused with [IllegalArgumentException]; an update of a trigger the caller
     * does not own is refused the same way. Validation failures (an unknown kind, a bad schedule) throw [IllegalArgumentException].
     */
    fun save(request: TriggerSaveRequest): TriggerRecord

    /** Removes a trigger the caller owns; false when [id] names none. */
    fun delete(id: String): Boolean

    /** Feeds one event to the trigger now, as its [source] (default: the calling module); answers the trigger after it. */
    fun fire(id: String, source: String? = null): TriggerRecord

    /** Re-arms a tripped or disabled trigger. */
    fun arm(id: String): TriggerRecord

    /** Disables a trigger: it records every later event as suppressed and starts nothing. */
    fun disable(id: String): TriggerRecord

    /** The trigger's durable event log, oldest first (the last few hundred are kept). */
    fun events(id: String): List<TriggerEventRecord>

    /**
     * Adds a plugin-defined action kind ([TriggerActionRecord.kind], e.g. `pod.start`): a trigger whose action is [kind] calls
     * [handler] when it fires. Closing the handle, or unloading the registering plugin, removes it; a trigger whose kind has no handler
     * records `refused_unknown_action`. A kind already registered (or a built-in kind) throws [IllegalStateException].
     * Implemented in TRIG-ACTIONS-1: until then a provider may throw [UnsupportedOperationException].
     */
    fun registerAction(kind: String, handler: TriggerActionHandler): AutoCloseable
}

/** What [Triggers.list] matches: every non-null field must equal the trigger's. */
data class TriggerFilter(
    /** The owning module id; use [Triggers.list] with your own id to find the triggers you made. */
    val owner: String? = null,
    val ref: String? = null,
    val projectId: String? = null,
    val kind: String? = null,
)

/** A plugin's handler for its own action kind (see [Triggers.registerAction]). Runs off the scheduler thread; throw = [TriggerActionResult.Refused]. */
fun interface TriggerActionHandler {
    fun run(fire: TriggerFire): TriggerActionResult
}

/** One firing handed to a [TriggerActionHandler]: the trigger as it is now, the event's [source] and the run's id. */
data class TriggerFire(val trigger: TriggerRecord, val source: String, val runId: String)

/** What a [TriggerActionHandler] did; the service records it as the event's disposition. */
sealed interface TriggerActionResult {
    /** A session was started (disposition `started`). */
    data class Started(val sessionId: String) : TriggerActionResult
    /** Mail was queued for a target (disposition `mailed`). */
    data class Mailed(val mailId: String) : TriggerActionResult
    /** The action ran and needs nothing more (disposition `delivered`). */
    data object Done : TriggerActionResult
    /** The action declined (disposition `refused`, with [reason] in the event digest). */
    data class Refused(val reason: String) : TriggerActionResult
}

/** What a trigger does when it fires (wire twin: `protocol.TriggerAction`). */
data class TriggerActionRecord(
    /** `start_next`, `prompt`, or a kind a plugin registered ([Triggers.registerAction]). */
    val kind: String = "start_next",
    /** A profile id, `role:<role>`, or `session:<id>` (one exact session; it is never replaced: gone = `target_gone`). */
    val agentId: String? = null,
    /** For `prompt`: the first message of the session the trigger starts, or the mail it sends. */
    val prompt: String? = null,
)

/** The [Triggers.save] request (wire twin: `protocol.TriggerSave`); every default is today's behaviour. */
data class TriggerSaveRequest(
    val id: String? = null,
    val name: String,
    /** `manual`, `schedule`, `ship`, `webhook`, or `at` (one-shot at [fireAt]). */
    val kind: String = "manual",
    val enabled: Boolean = true,
    val coalesceMs: Long = 120_000,
    val ceilingRuns: Int = 6,
    val ceilingWindowMs: Long = 3_600_000,
    val action: TriggerActionRecord = TriggerActionRecord(),
    val intervalMs: Long? = null,
    /** Write-only HMAC secret for kind `webhook`. */
    val webhookSecret: String? = null,
    val projectId: String? = null,
    /** For kind `at`: when it fires, epoch ms. */
    val fireAt: Long? = null,
    /** For kind `at`: delete the trigger once it has fired. */
    val deleteAfterFire: Boolean = false,
    /** Leave null: the host stamps the calling module. A value other than the caller's id is refused. */
    val owner: String? = null,
    /** Opaque to the service: the owner's own key for the trigger (e.g. `calendar:cal-1a2b3c4d`). */
    val ref: String? = null,
)

/** A trigger as the service holds it (wire twin: `protocol.TriggerView`). [state] is `armed`, `running`, `tripped` or `disabled`. */
data class TriggerRecord(
    val id: String,
    val name: String,
    val kind: String,
    val action: TriggerActionRecord,
    val enabled: Boolean,
    val state: String,
    val coalesceMs: Long,
    val ceilingRuns: Int,
    val ceilingWindowMs: Long,
    val createdAt: Long,
    val trippedAt: Long? = null,
    val trippedReason: String? = null,
    val intervalMs: Long? = null,
    val projectId: String? = null,
    val lastFiredAt: Long? = null,
    val nextFireAt: Long? = null,
    val fireAt: Long? = null,
    val deleteAfterFire: Boolean = false,
    /** The module that made it; null = a person (the Triggers tab). */
    val owner: String? = null,
    val ref: String? = null,
)

/** One durable event row (wire twin: `protocol.TriggerEventView`); [disposition] as on the wire, plus `target_gone`. */
data class TriggerEventRecord(
    val eventId: String,
    val triggerId: String,
    val receivedAt: Long,
    val source: String,
    val disposition: String,
    val deliveryId: String? = null,
    val digest: String? = null,
)

/**
 * The ownership rule every [Triggers] provider applies, so it is the same everywhere. [callerModuleId] is what
 * `services.provideScoped` handed the provider's factory: the host's word, never the request's.
 */
object TriggerOwnership {
    /** [request] with [callerModuleId] as its owner; a request that names a different owner is refused. */
    fun stamp(request: TriggerSaveRequest, callerModuleId: String): TriggerSaveRequest {
        require(callerModuleId.isNotBlank()) { "a trigger caller must be named" }
        val claimed = request.owner
        require(claimed == null || claimed == callerModuleId) { "trigger owner '$claimed' is not the calling module '$callerModuleId'" }
        return request.copy(owner = callerModuleId)
    }

    /** True when [callerModuleId] may change [trigger] (update, delete, fire, arm, disable): only its owner may; a person's is changed in the app. */
    fun mayChange(trigger: TriggerRecord, callerModuleId: String): Boolean = trigger.owner != null && trigger.owner == callerModuleId

    /** Throws [IllegalArgumentException] unless [mayChange]. */
    fun requireChange(trigger: TriggerRecord, callerModuleId: String) {
        require(mayChange(trigger, callerModuleId)) {
            "trigger ${trigger.id} is owned by ${trigger.owner ?: "a person"}, not by '$callerModuleId'"
        }
    }
}

/**
 * The named events the trigger service publishes (`api.events.publish`), payload `{id, owner, ref, disposition, sessionId}`.
 * Subscribe with `api.events.subscribe("trigger.*")`, a Lua hook, or `infantry event`. Published from TRIG-2c on.
 */
object TriggerEvents {
    /** The trigger accepted an event and ran its action. */
    const val FIRED = "trigger.fired"
    /** The action reached its target (a session started, mail delivered). */
    const val DELIVERED = "trigger.delivered"
    /** The event was refused (ceiling, launch gate, tripped, disabled, an action handler's refusal). */
    const val REFUSED = "trigger.refused"
    /** A `session:<id>` target no longer exists; no replacement is spawned. */
    const val TARGET_GONE = "trigger.target_gone"
    /** Every name above, for one `subscribe`. */
    const val ALL = "trigger.*"
}
