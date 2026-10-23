package com.superiorsoftllc.agenthq.triggers

import com.superiorsoftllc.agenthq.attention.AttentionItem
import com.superiorsoftllc.agenthq.attention.AttentionStore
import com.superiorsoftllc.agenthq.persistence.AttentionRepository
import com.superiorsoftllc.agenthq.persistence.TriggerRepository
import com.superiorsoftllc.agenthq.protocol.TriggerEventView
import com.superiorsoftllc.agenthq.protocol.TriggerSave
import com.superiorsoftllc.agenthq.protocol.TriggerView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P9.6: the webhook ingress, with no sockets: who is admitted, who is refused, and what is recorded. */
class WebhookIngressTest {
    /**
     * Thread-safe like the SQLite repository it stands in for: every method holds this object's monitor. The
     * service's launch thread REPLACES an event row (a run's relabel) while a test reads `events()` lock-free, and
     * SQLite replaces in one `INSERT OR REPLACE`, so a reader never sees the row missing. Unsynchronized, the
     * remove-then-add below handed a reader an empty list ("List is empty" at `.single()`) or a
     * ConcurrentModificationException.
     */
    private class MemoryTriggers : TriggerRepository {
        val rows = LinkedHashMap<String, TriggerView>()
        val eventRows = mutableListOf<TriggerEventView>()
        val secrets = HashMap<String, String>()
        @Synchronized override fun upsert(trigger: TriggerView, intervalMs: Long?) { rows[trigger.id] = trigger }
        @Synchronized override fun get(id: String): TriggerView? = rows[id]
        @Synchronized override fun all(): List<TriggerView> = rows.values.toList()
        override fun intervals(): Map<String, Long> = emptyMap()
        @Synchronized override fun delete(id: String): Boolean { secrets.remove(id); return rows.remove(id) != null }
        @Synchronized override fun insertEvent(event: TriggerEventView, keep: Long) {
            eventRows.removeAll { it.eventId == event.eventId }
            eventRows += event
        }
        @Synchronized override fun events(triggerId: String): List<TriggerEventView> = eventRows.filter { it.triggerId == triggerId }
        @Synchronized override fun setWebhookSecret(triggerId: String, secret: String) { secrets[triggerId] = secret }
        @Synchronized override fun webhookSecret(triggerId: String): String? = secrets[triggerId]
        @Synchronized override fun deleteWebhookSecret(triggerId: String) { secrets.remove(triggerId) }
        override fun pendingMail(triggerId: String): String? = null
        override fun setPendingMail(triggerId: String, mailId: String?) {}
    }

    private class MemoryAttention : AttentionRepository {
        override fun insert(item: AttentionItem) {}
        override fun get(id: String): AttentionItem? = null
        override fun all(): List<AttentionItem> = emptyList()
        override fun acknowledge(id: String, at: Long): AttentionItem? = null
        override fun openCount(): Long = 0
    }

    private val secret = "whsec-0123456789abcdef0123456789abcdef"
    private var nowMs = 1_800_000_000_000L
    private val repo = MemoryTriggers()
    private val services = mutableListOf<TriggerService>()

    private fun service(over: MemoryTriggers = repo): TriggerService = TriggerService(
        repo = over,
        attention = AttentionStore(MemoryAttention()),
        startNext = { null },
        sessions = { null },
        clock = { nowMs },
        pumpMs = 60_000,
    ).also { services += it }

    private fun ingress(triggers: TriggerService, windowSeconds: Long = 300) =
        WebhookIngress(triggers, clock = { nowMs }, replayWindowSeconds = windowSeconds)

    private fun webhook(triggers: TriggerService, kind: String = "webhook", secretValue: String? = secret): TriggerView =
        triggers.save(TriggerSave(name = "hook", kind = kind, coalesceMs = 0, webhookSecret = secretValue))

    private fun send(
        ingress: WebhookIngress, id: String, body: String = "{\"a\":1}", delivery: String = "d-1",
        ts: Long = nowMs / 1_000, key: String = secret, signature: String? = null, timestamp: String? = ts.toString(),
    ): HookOutcome = ingress.deliver(
        triggerId = id,
        signature = signature ?: WebhookIngress.sign(key, ts, delivery, body.toByteArray()),
        timestamp = timestamp,
        delivery = delivery,
        body = body.toByteArray(),
    )

    @kotlin.test.AfterTest
    fun close() { services.forEach { it.close() } }

    private fun assertRejected(status: Int, outcome: HookOutcome) =
        assertEquals(status, (outcome as HookOutcome.Rejected).status, outcome.toString())

    @Test
    fun aValidSignedDeliveryStartsTheTriggerAndTheEventCarriesItsDeliveryIdAndDigest() {
        val triggers = service()
        val id = webhook(triggers).id
        val outcome = send(ingress(triggers), id, body = "hello")
        assertTrue(outcome is HookOutcome.Accepted, outcome.toString())
        val event = triggers.events(id).single()
        assertEquals("webhook", event.source)
        assertEquals("d-1", event.deliveryId)
        // sha256("hello")
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", event.digest)
    }

    @Test
    fun theSecretIsNeverPartOfAnythingTheDaemonPublishes() {
        val triggers = service()
        val view = webhook(triggers)
        assertFalse(view.toString().contains(secret))
        assertFalse(triggers.list().toString().contains(secret))
        assertEquals(secret, triggers.webhookSecret(view.id), "the ingress can still read it")
    }

    @Test
    fun aWrongSecretATamperedBodyAndAWrongVersionAreAllRefused() {
        val triggers = service()
        val id = webhook(triggers).id
        val ing = ingress(triggers)
        assertRejected(401, send(ing, id, key = "another-secret-another-secret-another"))
        val good = WebhookIngress.sign(secret, nowMs / 1_000, "d-1", "{\"a\":1}".toByteArray())
        assertRejected(401, send(ing, id, body = "{\"a\":2}", signature = good))
        assertRejected(401, send(ing, id, signature = good.replaceFirst("v1=", "v2=")))
        assertRejected(401, send(ing, id, signature = good.removePrefix("v1=")))
        assertRejected(401, send(ing, id, signature = "v1=" + "0".repeat(64)))
        assertTrue(triggers.events(id).isEmpty(), "a refused request records and runs nothing")
    }

    @Test
    fun theDeliveryAndTimestampAreInsideTheSignatureSoTheyCannotBeSwapped() {
        val triggers = service()
        val id = webhook(triggers).id
        val ing = ingress(triggers)
        val sigForD1 = WebhookIngress.sign(secret, nowMs / 1_000, "d-1", "x".toByteArray())
        assertRejected(401, ing.deliver(id, sigForD1, (nowMs / 1_000).toString(), "d-2", "x".toByteArray()))
        assertRejected(401, ing.deliver(id, sigForD1, (nowMs / 1_000 + 1).toString(), "d-1", "x".toByteArray()))
    }

    @Test
    fun missingOrMalformedHeadersAreRefusedTheSameWayAsABadSignature() {
        val triggers = service()
        val id = webhook(triggers).id
        val ing = ingress(triggers)
        val body = "x".toByteArray()
        val sig = WebhookIngress.sign(secret, nowMs / 1_000, "d-1", body)
        val ts = (nowMs / 1_000).toString()
        val bad = listOf(
            ing.deliver(id, null, ts, "d-1", body),
            ing.deliver(id, sig, null, "d-1", body),
            ing.deliver(id, sig, ts, null, body),
            ing.deliver(id, sig, "not-a-number", "d-1", body),
            ing.deliver(id, sig, "-5", "d-1", body),
            ing.deliver(id, sig, ts, "has:colon", body),
            ing.deliver(id, sig, ts, "x".repeat(129), body),
        )
        bad.forEach { assertRejected(401, it) }
        assertEquals(1, bad.map { (it as HookOutcome.Rejected).reason }.toSet().size, "one reason for all of them")
    }

    @Test
    fun anUnknownTriggerANonWebhookTriggerAndAWrongSignatureAreIndistinguishable() {
        val triggers = service()
        val manual = webhook(triggers, kind = "manual", secretValue = null).id
        val hook = webhook(triggers).id
        val ing = ingress(triggers)
        val unknown = send(ing, "trg-nope") as HookOutcome.Rejected
        val notAHook = send(ing, manual) as HookOutcome.Rejected
        val wrong = send(ing, hook, key = "z".repeat(40)) as HookOutcome.Rejected
        assertEquals(setOf(401), setOf(unknown.status, notAHook.status, wrong.status))
        assertEquals(1, setOf(unknown.reason, notAHook.reason, wrong.reason).size)
    }

    @Test
    fun aTimestampOutsideTheReplayWindowIsRefusedInBothDirectionsAndInsideItIsAdmitted() {
        val triggers = service()
        val id = webhook(triggers).id
        val ing = ingress(triggers, windowSeconds = 300)
        val now = nowMs / 1_000
        assertRejected(401, send(ing, id, delivery = "old", ts = now - 301))
        assertRejected(401, send(ing, id, delivery = "future", ts = now + 301))
        assertTrue(send(ing, id, delivery = "edge-old", ts = now - 300) is HookOutcome.Accepted)
        assertTrue(send(ing, id, delivery = "edge-new", ts = now + 300) is HookOutcome.Accepted)
        assertEquals(2, triggers.events(id).size, "refused deliveries leave no row")
    }

    @Test
    fun theReplayWindowMustStayInsideTheSpecifiedRange() {
        val triggers = service()
        assertFailsWith<IllegalArgumentException> { ingress(triggers, windowSeconds = 59) }
        assertFailsWith<IllegalArgumentException> { ingress(triggers, windowSeconds = 3_601) }
        ingress(triggers, windowSeconds = 60)
        ingress(triggers, windowSeconds = 3_600)
    }

    @Test
    fun aRepeatedDeliveryIdIsRecordedAsDuplicateAndNeverRunAgain() {
        val triggers = service()
        val id = webhook(triggers).id
        val ing = ingress(triggers)
        val first = send(ing, id, delivery = "dup-me") as HookOutcome.Accepted
        assertFalse(first.disposition == "duplicate", first.disposition)
        repeat(3) { assertEquals(HookOutcome.Accepted("duplicate"), send(ing, id, delivery = "dup-me")) }
        val events = triggers.events(id)
        assertEquals(1, events.count { it.disposition != "duplicate" }, "the delivery ran once")
        assertEquals(1, events.count { it.disposition == "duplicate" }, "one duplicate row per delivery id, not one per replay")
    }

    @Test
    fun aReplayWithADifferentTimestampButTheSameDeliveryIdIsStillADuplicate() {
        val triggers = service()
        val id = webhook(triggers).id
        val ing = ingress(triggers)
        send(ing, id, delivery = "same")
        nowMs += 5_000
        assertEquals(HookOutcome.Accepted("duplicate"), send(ing, id, delivery = "same", ts = nowMs / 1_000))
    }

    @Test
    fun duplicateDetectionSurvivesADaemonRestartBecauseTheDeliveryIdIsStored() {
        val first = service()
        val id = webhook(first).id
        send(ingress(first), id, delivery = "persisted")
        // A "restart": a new service and a new ingress over the SAME repository, with an empty in-memory set.
        val second = service()
        assertEquals(HookOutcome.Accepted("duplicate"), send(ingress(second), id, delivery = "persisted"))
        assertEquals(1, second.events(id).count { it.disposition != "duplicate" })
    }

    @Test
    fun theBodyCapIsSixtyFourKilobytesAndIsCheckedBeforeAnythingElse() {
        val triggers = service()
        val id = webhook(triggers).id
        val ing = ingress(triggers)
        val atCap = ByteArray(65_536) { 'a'.code.toByte() }
        val over = ByteArray(65_536 + 1) { 'a'.code.toByte() }
        val ts = nowMs / 1_000
        assertTrue(ing.deliver(id, WebhookIngress.sign(secret, ts, "cap", atCap), ts.toString(), "cap", atCap) is HookOutcome.Accepted)
        // Over the cap is 413 even with a PERFECT signature, and even for a trigger that does not exist.
        assertRejected(413, ing.deliver(id, WebhookIngress.sign(secret, ts, "big", over), ts.toString(), "big", over))
        assertRejected(413, ing.deliver("trg-nope", null, null, null, over))
    }

    @Test
    fun aDisabledWebhookIsAdmittedButSuppressedAndStillRecorded() {
        val triggers = service()
        val id = webhook(triggers).id
        triggers.disable(id)
        val outcome = send(ingress(triggers), id) as HookOutcome.Accepted
        assertEquals("suppressed_disabled", outcome.disposition)
        assertEquals("d-1", triggers.events(id).single().deliveryId)
    }

    @Test
    fun aDeletedTriggerNoLongerAdmitsAnything() {
        val triggers = service()
        val id = webhook(triggers).id
        triggers.delete(id)
        assertNull(repo.secrets[id], "the secret goes with the trigger")
        assertRejected(401, send(ingress(triggers), id))
    }

    @Test
    fun theSecretIsRequiredAndBoundedOnAWebhookTriggerAndRefusedOnAnyOtherKind() {
        val triggers = service()
        assertFailsWith<IllegalArgumentException> { webhook(triggers, secretValue = null) }
        assertFailsWith<IllegalArgumentException> { webhook(triggers, secretValue = "short") }
        assertFailsWith<IllegalArgumentException> { webhook(triggers, secretValue = "x".repeat(257)) }
        assertFailsWith<IllegalArgumentException> { webhook(triggers, secretValue = "has a space " + "x".repeat(40)) }
        assertFailsWith<IllegalArgumentException> { webhook(triggers, kind = "manual", secretValue = secret) }
        assertTrue(repo.secrets.isEmpty(), "a refused save stores no secret")
    }

    @Test
    fun editingAWebhookWithoutASecretKeepsTheStoredOneAndANewOneReplacesIt() {
        val triggers = service()
        val id = webhook(triggers).id
        triggers.save(TriggerSave(id = id, name = "renamed", kind = "webhook", coalesceMs = 0))
        assertEquals(secret, triggers.webhookSecret(id))
        val rotated = "rotated-0123456789abcdef0123456789abcdef"
        triggers.save(TriggerSave(id = id, name = "renamed", kind = "webhook", coalesceMs = 0, webhookSecret = rotated))
        assertEquals(rotated, triggers.webhookSecret(id))
        assertRejected(401, send(ingress(triggers), id, key = secret))
        assertTrue(send(ingress(triggers), id, delivery = "after-rotation", key = rotated) is HookOutcome.Accepted)
    }

    @Test
    fun turningAWebhookIntoAnotherKindDropsItsSecret() {
        val triggers = service()
        val id = webhook(triggers).id
        triggers.save(TriggerSave(id = id, name = "hook", kind = "manual", coalesceMs = 0))
        assertNull(triggers.webhookSecret(id))
        assertNull(repo.secrets[id])
    }
}
