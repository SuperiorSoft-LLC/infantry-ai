// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.runners.LinkFailure
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * One orchestrator -> runner link call: (url, pinned fingerprint, orchestrator id, credential, frame type, data, timeout) -> answer.
 * [timeoutSeconds] bounds THIS call; `runner add '<link>'` gives each host of the link `limits.runners.addHostTimeoutSec`.
 */
fun interface LinkCall {
    fun call(url: String, fingerprint: String?, orchestratorId: String?, credential: String?, type: String, data: String, timeoutSeconds: Long): RunnerLinkServer.Answer
}

/**
 * RUNNER-ADD-LINK-1: what one [RunnerPeers.addLink] did: the paired runner, and one line per host that was tried and skipped before
 * the one that answered ("100.64.0.5:8790 unreachable (timed out after 5s)").
 */
data class AddOutcome(val runner: PairedRunner, val skipped: List<String>)

/**
 * RUNNER-POOL-5 (docs/RUNNER-POOL.md sections 6 and 7): the ORCHESTRATOR side of pairing: [add] (`infantry runner add`), [rotate]
 * and [rotateDue] (in-band rekey every `limits.runners.credentialRotateDays`), [detach] (`infantry runner detach <name>`).
 * A failure throws [LinkFailure] or [IllegalArgumentException] with the reason; nothing is stored then.
 */
class RunnerPeers(
    private val store: RunnerLinkStore,
    private val link: LinkCall,
    private val clock: () -> Long = System::currentTimeMillis,
    /** One link call's own timeout (`limits.runners.linkCallSeconds`), overridable per call by [addLink]'s per-host timeout. */
    private val callTimeoutSec: () -> Long = { LimitRegistry.RUNNERS_LINK_CALL_SECONDS.default },
    /** RUNNER-ADD-LINK-1: how long each host of a pairing link gets (`limits.runners.addHostTimeoutSec`). */
    private val hostTimeoutSec: () -> Long = { LimitRegistry.RUNNERS_ADD_HOST_TIMEOUT_SEC.default },
) {
    /**
     * Pairs runner [name] at [url] with the one-time [code]. The runner's certificate must match [fingerprint] (TLS pin) before
     * the code is sent; with no fingerprint the call fails after the handshake and the message carries the fingerprint the
     * runner presented, for the owner to compare.
     */
    fun add(name: String, url: String, code: String, fingerprint: String?, orchestratorName: String): PairedRunner {
        checkName(name)
        require(code.isNotBlank()) { "--code is required: the one-time code `infantry runner pair` printed on the runner" }
        val answer = link.call(url, fingerprint, null, null, F.PAIR, pairData(code, name, orchestratorName), callTimeoutSec())
        if (!answer.ok) throw LinkFailure(answer.data)
        val paired = record(name, url, fingerprint, answer)
        store.saveRunner(paired)
        return paired
    }

    /**
     * RUNNER-ADD-LINK-1: pairs with the runner a pairing link names, trying each host of the link in the link's order.
     *
     * The security property is kept: the transport pins the certificate in the TLS handshake BEFORE the code is sent, so a host whose
     * certificate does not match is skipped without ever seeing the code; a host that does not answer is skipped too, and the FIRST
     * host that answers PAIR wins (its URL is stored). A PAIR refusal (wrong, expired or burned code, another orchestrator) stops at
     * once: trying the next host with a used code would only burn attempts. With every host gone, the failure lists each host tried
     * with its reason and never the code.
     *
     * A plain (`ws://`) link is refused unless [allowPlain] (`runners.allowPlainTailnet` on this side too).
     */
    fun addLink(pairLink: RunnerPairLink, name: String, orchestratorName: String, allowPlain: Boolean): AddOutcome {
        checkName(name)
        require(!pairLink.plain || allowPlain) {
            "that link carries no certificate fingerprint because the runner serves plain ws:// (runners.allowPlainTailnet there); " +
                "set runners.allowPlainTailnet on this machine too, or pair again on the runner without it"
        }
        val data = pairData(pairLink.code, name, orchestratorName)
        val perHost = hostTimeoutSec()
        val skipped = mutableListOf<String>()
        for (host in pairLink.hosts) {
            // RUNNER-LINK-1 stores each host bracketed when it is an IPv6 address, so `wss://<host>:<port>` is ready to dial.
            val url = "${if (pairLink.plain) "ws" else "wss"}://$host:${pairLink.port}"
            val label = "$host:${pairLink.port}"
            val answer = try {
                link.call(url, pairLink.fingerprint, null, null, F.PAIR, data, perHost)
            } catch (e: LinkFailure) {
                skipped += "$label ${failureReason(e, url, perHost)}"
                continue
            }
            // A PAIR that was answered with a refusal is final: the code was spent, or the runner will not pair with this machine.
            if (!answer.ok) throw LinkFailure("$label refused: ${answer.data}")
            val paired = record(name, url, pairLink.fingerprint, answer)
            store.saveRunner(paired)
            return AddOutcome(paired, skipped)
        }
        throw LinkFailure("no host of the link answered: ${skipped.joinToString("; ")}")
    }

    /** Validates a runner name; one write path for both add forms. */
    private fun checkName(name: String) {
        require(RunnerLinkStore.NAME.matches(name)) { "runner name '$name' must be 1-32 of a-z, 0-9 and '-' (starting with a letter or digit)" }
        require(name != LOCAL) { "'$LOCAL' is this machine's own runner; pick another name" }
        require(store.runner(name) == null) { "runner '$name' is already paired; `infantry runner detach $name` first" }
    }

    private fun pairData(code: String, name: String, orchestratorName: String): String =
        F.json.encodeToString(F.PairRequest.serializer(), F.PairRequest(code = code, orchestratorId = store.selfId(), orchestratorName = orchestratorName))

    private fun record(name: String, url: String, fingerprint: String?, answer: RunnerLinkServer.Answer): PairedRunner {
        val paired = runCatching { F.json.decodeFromString(F.PairAnswer.serializer(), answer.data) }.getOrElse {
            throw LinkFailure("$url answered PAIR with an answer that could not be read (${it.message}); nothing was stored")
        }
        return PairedRunner(name, url, fingerprint.orEmpty(), paired.credential, clock(), paired.runnerId)
    }

    /**
     * Why one host of a link did not answer, in the order the message lists it: a certificate that does not match the link (the code
     * was never sent), a timeout, or whatever the transport said with this host's URL taken out of it once.
     */
    private fun failureReason(e: LinkFailure, url: String, perHost: Long): String {
        if (e.presentedFingerprint != null) return "certificate does not match the link"
        val message = e.message.orEmpty()
        if (message.contains("did not answer within")) return "unreachable (timed out after ${perHost}s)"
        val detail = message.replace("cannot reach the runner link at", "").replace(url, "").trim().trim(':', '.', ';', ',', ' ')
        return if (detail.isEmpty()) "unreachable" else "unreachable ($detail)"
    }

    /** Rekeys runner [name] in-band: the runner issues a new credential and forgets the old one at once. */
    fun rotate(name: String): PairedRunner {
        val r = store.runner(name) ?: throw IllegalArgumentException("no paired runner '$name'")
        val answer = link.call(r.url, r.fingerprint.ifBlank { null }, store.selfId(), r.credential, F.REKEY, "{}", callTimeoutSec())
        if (!answer.ok) throw LinkFailure("rekey of '$name' refused: ${answer.data}")
        val fresh = F.json.decodeFromString(F.RekeyAnswer.serializer(), answer.data)
        val record = r.copy(credential = fresh.credential, issuedAt = fresh.issuedAt)
        store.saveRunner(record)
        return record
    }

    /** Rekeys every runner whose credential is at least [rotateDays] old; one line per runner tried. */
    fun rotateDue(rotateDays: Long): List<String> {
        val dueBefore = clock() - rotateDays * MS_PER_DAY
        return store.runners().filter { it.issuedAt <= dueBefore }.map { r ->
            runCatching { rotate(r.name) }.fold({ "runner '${r.name}': credential rotated" }, { "runner '${r.name}': rotation failed: ${it.message}" })
        }
    }

    /**
     * Detaches runner [name]: tells the runner to forget this orchestrator (best effort, it may be offline) and removes the local
     * record either way, so the credential is useless from this side at once. Returns what happened on the runner side.
     */
    fun detach(name: String): String {
        val r = store.runner(name) ?: throw IllegalArgumentException("no paired runner '$name'")
        val remote = runCatching { link.call(r.url, r.fingerprint.ifBlank { null }, store.selfId(), r.credential, F.DETACH, "{}", callTimeoutSec()) }
            .fold({ if (it.ok) "the runner revoked it too" else "the runner answered: ${it.data}" }, { "the runner was not reached (${it.message}); run `infantry runner detach` there too" })
        store.removeRunner(name)
        return remote
    }

    companion object {
        const val LOCAL = "local"
        private const val MS_PER_DAY = 86_400_000L
    }
}
