// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.runners.RunnerLinkFrames as F

/** One orchestrator -> runner link call: (url, pinned fingerprint, orchestrator id, credential, frame type, data) -> answer. */
fun interface LinkCall {
    fun call(url: String, fingerprint: String?, orchestratorId: String?, credential: String?, type: String, data: String): RunnerLinkServer.Answer
}

/**
 * RUNNER-POOL-5 (docs/RUNNER-POOL.md sections 6 and 7): the ORCHESTRATOR side of pairing: [add] (`infantry runner add`), [rotate]
 * and [rotateDue] (in-band rekey every `limits.runners.credentialRotateDays`), [detach] (`infantry runner detach <name>`).
 * A failure throws [RunnerLinkClient.Failure] or [IllegalArgumentException] with the reason; nothing is stored then.
 */
class RunnerPeers(
    private val store: RunnerLinkStore,
    private val link: LinkCall,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Pairs runner [name] at [url] with the one-time [code]. The runner's certificate must match [fingerprint] (TLS pin) before
     * the code is sent; with no fingerprint the call fails after the handshake and the message carries the fingerprint the
     * runner presented, for the owner to compare.
     */
    fun add(name: String, url: String, code: String, fingerprint: String?, orchestratorName: String): PairedRunner {
        require(RunnerLinkStore.NAME.matches(name)) { "runner name '$name' must be 1-32 of a-z, 0-9 and '-' (starting with a letter or digit)" }
        require(name != LOCAL) { "'$LOCAL' is this machine's own runner; pick another name" }
        require(code.isNotBlank()) { "--code is required: the one-time code `infantry runner pair` printed on the runner" }
        require(store.runner(name) == null) { "runner '$name' is already paired; `infantry runner detach $name` first" }
        val request = F.PairRequest(code = code, orchestratorId = store.selfId(), orchestratorName = orchestratorName)
        val answer = link.call(url, fingerprint, null, null, F.PAIR, F.json.encodeToString(F.PairRequest.serializer(), request))
        if (!answer.ok) throw RunnerLinkClient.Failure(answer.data)
        val paired = F.json.decodeFromString(F.PairAnswer.serializer(), answer.data)
        val record = PairedRunner(name, url, fingerprint.orEmpty(), paired.credential, clock(), paired.runnerId)
        store.saveRunner(record)
        return record
    }

    /** Rekeys runner [name] in-band: the runner issues a new credential and forgets the old one at once. */
    fun rotate(name: String): PairedRunner {
        val r = store.runner(name) ?: throw IllegalArgumentException("no paired runner '$name'")
        val answer = link.call(r.url, r.fingerprint.ifBlank { null }, store.selfId(), r.credential, F.REKEY, "{}")
        if (!answer.ok) throw RunnerLinkClient.Failure("rekey of '$name' refused: ${answer.data}")
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
        val remote = runCatching { link.call(r.url, r.fingerprint.ifBlank { null }, store.selfId(), r.credential, F.DETACH, "{}") }
            .fold({ if (it.ok) "the runner revoked it too" else "the runner answered: ${it.data}" }, { "the runner was not reached (${it.message}); run `infantry runner detach` there too" })
        store.removeRunner(name)
        return remote
    }

    companion object {
        const val LOCAL = "local"
        private const val MS_PER_DAY = 86_400_000L
    }
}
