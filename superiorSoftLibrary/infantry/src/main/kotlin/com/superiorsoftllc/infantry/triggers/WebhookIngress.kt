package com.superiorsoftllc.infantry.triggers

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What one `POST /hooks/{id}` came to: an HTTP-free answer the route maps to a status. */
sealed interface HookOutcome {
    /** 200. [disposition] is what the trigger did with the event (`started`, `folded`, `duplicate`, ...). */
    data class Accepted(val disposition: String) : HookOutcome

    /** A refusal: [status] (401 / 413) and a [reason] that never contains the secret or the expected signature. */
    data class Rejected(val status: Int, val reason: String) : HookOutcome
}

/**
 * P9.6 (PHASE9-SPEC 4.6): signature-admitted webhook ingress. `POST /hooks/{triggerId}` is reachable without the
 * daemon bearer, so this class IS the authentication: a request is admitted only with a valid per-trigger
 * signature, `X-Infantry-Signature: v1=<hex HMAC-SHA256 of "v1:<timestamp>:<delivery>:<raw body>">`, with
 * `X-Infantry-Timestamp` (epoch seconds) and `X-Infantry-Delivery` alongside it.
 *
 * Order of checks, fixed: body cap (cheap, before any secret is read) -> trigger and secret -> header shape ->
 * signature -> replay window -> duplicate delivery -> fire. Every failure BEFORE the signature is verified is the
 * same 401, so an unauthenticated caller cannot tell an unknown trigger from a wrong signature from a missing
 * header. Only a caller who holds the secret sees the window and duplicate answers.
 *
 * The body is untrusted data. It is hashed (the digest is stored on the event row), never interpreted.
 */
class WebhookIngress(
    private val triggers: TriggerService,
    private val clock: () -> Long = System::currentTimeMillis,
    replayWindowSeconds: Long = DEFAULT_REPLAY_WINDOW_SECONDS,
) {
    private val windowMs: Long

    /** delivery key -> when it stops being replayable (its timestamp + the window). In-memory backstop to the durable rows. */
    private val seen = HashMap<String, Long>()

    init {
        require(replayWindowSeconds in MIN_REPLAY_WINDOW_SECONDS..MAX_REPLAY_WINDOW_SECONDS) {
            "replay window must be $MIN_REPLAY_WINDOW_SECONDS..$MAX_REPLAY_WINDOW_SECONDS seconds"
        }
        windowMs = replayWindowSeconds * 1_000
    }

    fun deliver(triggerId: String, signature: String?, timestamp: String?, delivery: String?, body: ByteArray): HookOutcome {
        if (body.size > MAX_BODY_BYTES) return HookOutcome.Rejected(413, "body is larger than $MAX_BODY_BYTES bytes")
        val id = triggerId.trim()
        val secret = triggers.webhookSecret(id) ?: return unauthorized()
        val ts = timestamp?.trim()?.takeIf { it.length in 1..13 && it.all(Char::isDigit) }?.toLong() ?: return unauthorized()
        val deliveryId = delivery?.trim()?.takeIf { DELIVERY_ID.matches(it) } ?: return unauthorized()
        val presented = signature?.trim()?.takeIf { SIGNATURE.matches(it) }?.substring(SIGNATURE_PREFIX.length)?.lowercase()
            ?: return unauthorized()
        val expected = hex(hmac(secret, "v1:$ts:$deliveryId:".toByteArray(Charsets.UTF_8) + body))
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), presented.toByteArray(Charsets.US_ASCII))) {
            return unauthorized()
        }

        // Authenticated from here on: the caller holds the secret, so specific answers leak nothing.
        val now = clock()
        if (kotlin.math.abs(now - ts * 1_000) > windowMs) {
            return HookOutcome.Rejected(401, "timestamp is outside the ${windowMs / 1_000}s replay window")
        }
        val digest = hex(MessageDigest.getInstance("SHA-256").digest(body))
        synchronized(this) {
            seen.values.removeAll { it < now }
            val key = "$id/$deliveryId"
            val durable = try {
                triggers.events(id).any { it.deliveryId == deliveryId }
            } catch (e: IllegalArgumentException) {
                return unauthorized() // the trigger was deleted between the secret lookup and here
            }
            if (key in seen || durable) {
                runCatching { triggers.recordDuplicate(id, deliveryId, digest) }
                return HookOutcome.Accepted("duplicate")
            }
            seen[key] = ts * 1_000 + windowMs
            try {
                triggers.fire(id, source = "webhook", deliveryId = deliveryId, digest = digest)
            } catch (e: IllegalArgumentException) {
                seen.remove(key)
                return unauthorized()
            }
            val disposition = triggers.events(id)
                .lastOrNull { it.deliveryId == deliveryId && it.source == "webhook" && !it.eventId.startsWith(TriggerService.DUPLICATE_EVENT_PREFIX) }
                ?.disposition ?: "accepted"
            return HookOutcome.Accepted(disposition)
        }
    }

    private fun unauthorized() = HookOutcome.Rejected(401, "missing or invalid webhook signature")

    companion object {
        const val MAX_BODY_BYTES = 64 * 1024
        const val DEFAULT_REPLAY_WINDOW_SECONDS = 300L
        const val MIN_REPLAY_WINDOW_SECONDS = 60L
        const val MAX_REPLAY_WINDOW_SECONDS = 3_600L
        const val SIGNATURE_HEADER = "X-Infantry-Signature"
        const val TIMESTAMP_HEADER = "X-Infantry-Timestamp"
        const val DELIVERY_HEADER = "X-Infantry-Delivery"
        /** Pre-rename names of the three headers above; still accepted (RENAME-2). */
        const val LEGACY_SIGNATURE_HEADER = "X-AgentHQ-Signature"
        const val LEGACY_TIMESTAMP_HEADER = "X-AgentHQ-Timestamp"
        const val LEGACY_DELIVERY_HEADER = "X-AgentHQ-Delivery"
        private const val SIGNATURE_PREFIX = "v1="

        /** No `:` allowed, so the signed string "v1:<ts>:<delivery>:<body>" cannot be read two ways. */
        private val DELIVERY_ID = Regex("[A-Za-z0-9._-]{1,128}")
        private val SIGNATURE = Regex("v1=[0-9a-fA-F]{64}")

        /** The signature a sender must put in [SIGNATURE_HEADER]; shared by the tests and by any in-repo sender. */
        fun sign(secret: String, timestampSeconds: Long, delivery: String, body: ByteArray): String =
            SIGNATURE_PREFIX + hex(hmac(secret, "v1:$timestampSeconds:$delivery:".toByteArray(Charsets.UTF_8) + body))

        private fun hmac(secret: String, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                doFinal(data)
            }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
