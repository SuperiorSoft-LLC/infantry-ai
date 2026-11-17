// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.triggers.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.HttpCall
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import com.superiorsoftllc.infantry.daemon.api.RouteCheck
import com.superiorsoftllc.infantry.daemon.api.attention.Attention
import com.superiorsoftllc.infantry.daemon.api.attention.AttentionEntry
import com.superiorsoftllc.infantry.daemon.api.board.BoardResult
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerEvents
import com.superiorsoftllc.infantry.daemon.api.triggers.Triggers
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.TriggerEventView
import com.superiorsoftllc.infantry.protocol.TriggerSave
import com.superiorsoftllc.infantry.protocol.TriggerView
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * TRIG-2c: Infantry_Triggers' daemon half runs the trigger service (stored triggers, coalescing, schedules, ship, webhook ingress,
 * prompt nudges), moved here from core as-is. What core still supplies is [DaemonApi.triggerHost]: the sessions, the mail layer, the
 * unattended prompt launch and the run ends (core-only facts). Everything else is the plugin's, through the daemon API:
 *
 *  - the `trigger_*` frames (declared in module.json under the same policy actions core's catalog names), pushes via `events.broadcast`;
 *  - `POST /hooks/{id}` (a public route; its declared check is the per-hook HMAC, [WebhookIngress.refusal]);
 *  - ship triggers on committed ticket transitions (`events.onTicket`);
 *  - `triggersTripped` on `/health` (`hooks.status`);
 *  - the [Triggers] service per calling module (`services.provideScoped`, owner stamped from the caller);
 *  - the `triggers.list|save|delete|fire` capabilities (scripts, agents, other plugins), gated by the same actions;
 *  - the named events [TriggerEvents] (`trigger.fired`, `trigger.refused`).
 *
 * The pump starts in [DaemonApi.whenStarted] (after recovery, the mailbox and the sessions are in), as core started it.
 */
class TriggersDaemonPlugin : DaemonPlugin {
    @Volatile private var service: TriggerService? = null
    @Volatile private var runEvents: AutoCloseable? = null

    override fun start(api: DaemonApi) {
        val host = api.triggerHost ?: run {
            api.log("Infantry_Triggers: this daemon supplies no trigger host; triggers are off")
            return
        }
        // The board's hand-outs run as the owner: a trigger is the owner's own standing order (as in core before TRIG-2c).
        val owner = Caller(owner = true)
        val store = PublishingTriggerStore(StorageTriggerStore(api.storage)) { name, payload ->
            runCatching { api.events.publish(name, payload) }
        }
        val nudges = TriggerNudges(sessions = host::sessions, send = host::sendMail, startSession = host::startPrompt)
        val triggers = TriggerService(
            repo = store,
            attention = FoundAttention(api),
            startNext = { filter -> (api.tickets.startNext(filter, owner) as? BoardResult.Ok)?.value },
            startPrompt = { agentId, prompt -> host.startPrompt(agentId, prompt, null) },
            sessions = host::session,
            deliverPrompt = nudges::deliver,
            mailPending = host::mailPending,
            log = api::log,
            autoStart = false, // started below, once the daemon is up
        )
        service = triggers
        triggers.eventSink = { view -> api.events.broadcast(Envelope.TRIGGER, json.encodeToString(TriggerView.serializer(), view)) }
        runEvents = host.onRunEvent(triggers::onRunEvent)

        registerFrames(api, triggers)
        registerWebhook(api, triggers)
        // H12b: a committed move into done reaches the ship triggers, after the board commit has released.
        api.events.onTicket("triggers") { e -> if (e.isTransition) triggers.onTicketTransition(e.ticketId, e.to!!) }
        api.hooks.status { mapOf(TRIPPED_FIELD to triggers.trippedCount().toString()) }
        api.services.provideScoped(Triggers::class) { caller -> ScopedTriggers(triggers, caller) }
        TriggerCapabilities(triggers) { broadcastAll(api, triggers) }.register(api)
        // TRIGGER-NUDGE: only now may a schedule fire (a slot missed while down fires on this first pump).
        api.whenStarted { triggers.start() }
    }

    /** Stop observing FIRST, as core did: the shutdown's kills must not read as run failures (a running trigger trips at the next boot). */
    override fun stop() {
        runCatching { runEvents?.close() }
        runCatching { service?.close() }
        runEvents = null
        service = null
    }

    private fun registerFrames(api: DaemonApi, triggers: TriggerService) {
        // Contract 1.14.0. A change is pushed to every client by the service's sink, and for the requester that push is the answer;
        // `trigger_fire` also answers its sender directly.
        api.frames.handle(Envelope.TRIGGER_SAVE) { f, _ -> request(f.type) { triggers.save(TriggerSave.decode(f.data)); null } }
        api.frames.handle(Envelope.TRIGGER_LIST) { f, _ -> request(f.type) { Frame(Envelope.TRIGGERS, listJson(triggers.list())) } }
        api.frames.handle(Envelope.TRIGGER_FIRE) { f, _ ->
            // Every state change is already pushed (TriggerService.store -> eventSink). A direct reply would carry the view fire()
            // read, and could reach the client AFTER the launch thread's newer push (a card left at "running"), so none is sent.
            request(f.type) { triggers.fire(f.data); null }
        }
        api.frames.handle(Envelope.TRIGGER_ARM) { f, _ -> request(f.type) { triggers.arm(f.data); null } }
        api.frames.handle(Envelope.TRIGGER_DISABLE) { f, _ -> request(f.type) { triggers.disable(f.data); null } }
        api.frames.handle(Envelope.TRIGGER_DELETE) { f, _ ->
            request(f.type) {
                triggers.delete(f.data)
                broadcastAll(api, triggers)
                null
            }
        }
        api.frames.handle(Envelope.TRIGGER_EVENTS) { f, _ ->
            request(f.type) { Frame(Envelope.TRIGGER_EVENTS, json.encodeToString(ListSerializer(TriggerEventView.serializer()), triggers.events(f.data))) }
        }
    }

    /**
     * P9.6: `POST /hooks/{id}`, answered without the daemon bearer (module.json `daemon.publicRoutes`). Admission is the per-hook HMAC:
     * the route's declared check is the signature ([WebhookIngress.refusal]), and [WebhookIngress.deliver] checks it again with the
     * replay window and the duplicate guard. Core reads a public route's body with a hard cap (`limits.plugins.publicRouteBodyKb`) and
     * hands over the exact bytes ([HttpCall.bodyBytes]), which the signature covers.
     */
    private fun registerWebhook(api: DaemonApi, triggers: TriggerService) {
        val ingress = WebhookIngress(triggers)
        val check = RouteCheck { call ->
            ingress.refusal(call.pathParams["id"].orEmpty(), call.header(SIG), call.header(TS), call.header(DELIVERY), call.bytes())
        }
        api.routes.handle("POST", HOOK_ROUTE, check) { call ->
            val outcome = ingress.deliver(
                triggerId = call.pathParams["id"].orEmpty(),
                signature = call.header(SIG),
                timestamp = call.header(TS),
                delivery = call.header(DELIVERY),
                body = call.bytes(),
            )
            when (outcome) {
                is HookOutcome.Accepted -> HttpReply(200, """{"status":"ok","disposition":"${outcome.disposition}"}""", "application/json")
                is HookOutcome.Rejected -> HttpReply(outcome.status, outcome.reason)
            }
        }
    }

    private fun broadcastAll(api: DaemonApi, triggers: TriggerService) = api.events.broadcast(Envelope.TRIGGERS, listJson(triggers.list()))

    companion object {
        /** The `/health` `pluginStatus` field core reads into `Health.triggersTripped`. */
        const val TRIPPED_FIELD = "triggersTripped"
        const val HOOK_ROUTE = "/hooks/{id}"
        private val SIG = listOf(WebhookIngress.SIGNATURE_HEADER, WebhookIngress.LEGACY_SIGNATURE_HEADER)
        private val TS = listOf(WebhookIngress.TIMESTAMP_HEADER, WebhookIngress.LEGACY_TIMESTAMP_HEADER)
        private val DELIVERY = listOf(WebhookIngress.DELIVERY_HEADER, WebhookIngress.LEGACY_DELIVERY_HEADER)

        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        internal fun listJson(all: List<TriggerView>): String = json.encodeToString(ListSerializer(TriggerView.serializer()), all)

        /** A trigger request: a refusal is an `error` naming the verb and the problem; nothing changes. */
        internal inline fun request(type: String, block: () -> Frame?): Frame? = try {
            block()
        } catch (e: IllegalArgumentException) {
            Frame(Envelope.ERROR, "$type refused: ${e.message ?: "invalid request"}")
        } catch (e: Exception) {
            Frame(Envelope.ERROR, "$type failed: ${e.message ?: e::class.simpleName}")
        }

        /** The first of [names] the request carries (header names are case-insensitive), the new name before the legacy one. */
        private fun HttpCall.header(names: List<String>): String? =
            names.firstNotNullOfOrNull { n -> headers.entries.firstOrNull { it.key.equals(n, ignoreCase = true) }?.value }

        private fun HttpCall.bytes(): ByteArray = bodyBytes ?: body.toByteArray(Charsets.UTF_8)
    }
}

/**
 * The attention provider, found per call: Infantry_Attention publishes it, and may load after this plugin or be reloaded. No provider =
 * the item is dropped (raise answers null), exactly what an unset attention seam did in core.
 */
private class FoundAttention(private val api: DaemonApi) : Attention {
    private fun provider(): Attention? = runCatching { api.services.find(Attention::class) }.getOrNull()
    override fun raise(kind: String, triggerId: String?, ticketId: String?, podId: String?, sessionId: String?, outcome: String, detail: String): String? =
        provider()?.raise(kind, triggerId, ticketId, podId, sessionId, outcome, detail)
    override fun resolve(id: String): Boolean = provider()?.resolve(id) ?: false
    override fun list(project: String?): List<AttentionEntry> = provider()?.list(project).orEmpty()
}

/**
 * The store as the service writes it, plus the named events: each recorded event disposition is published once as a
 * [TriggerEvents] name: `started` / `mailed` = fired, a refusal or suppression = refused (a `started` the launch relabels is followed by its refusal).
 * `trigger.delivered` and `trigger.target_gone` need an observed delivery and come with session targets (TRIG-TARGET-1). Publishing never fails a write.
 */
internal class PublishingTriggerStore(
    private val inner: TriggerStore,
    private val publish: (name: String, payload: String) -> Unit,
) : TriggerStore by inner {
    override fun insertEvent(event: TriggerEventView, keep: Long) {
        inner.insertEvent(event, keep)
        val names = when (event.disposition) {
            "started", "mailed" -> listOf(TriggerEvents.FIRED)
            in REFUSALS -> listOf(TriggerEvents.REFUSED)
            else -> return
        }
        val t = runCatching { inner.get(event.triggerId) }.getOrNull()
        val payload = buildJsonObject {
            put("id", event.triggerId)
            put("owner", t?.owner)
            put("ref", t?.ref)
            put("disposition", event.disposition)
            put("sessionId", null as String?)
        }.toString()
        names.forEach { runCatching { publish(it, payload) } }
    }

    private companion object {
        val REFUSALS = setOf("refused_ceiling", "refused_launch_gate", "suppressed_tripped", "suppressed_disabled")
    }
}

/** Reads a string argument of a capability call. */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** The JSON form of a wire value, for a capability answer. */
internal fun TriggerView.toJson(): JsonElement = TriggersDaemonPlugin.json.encodeToJsonElement(TriggerView.serializer(), this)
