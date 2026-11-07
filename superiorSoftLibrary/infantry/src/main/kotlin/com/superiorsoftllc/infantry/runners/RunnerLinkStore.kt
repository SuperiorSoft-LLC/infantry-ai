// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.keystore.Keystore
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * RUNNER-POOL-5: the secret fields the runner link keeps. In the daemon it is a [Keystore] of its own under
 * `<stateDir>/runner-link/` (sealed exactly like the app keystore, but NOT the app keystore: a run, a plugin secret read or the
 * secrets card never sees a pair credential). Tests use [InMemory].
 */
interface LinkVault {
    fun get(name: String, field: String): String?
    fun set(name: String, field: String, value: String)
    fun clear(name: String)
    fun names(): List<String>

    class InMemory : LinkVault {
        private val data = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, String>>()
        override fun get(name: String, field: String) = data[name]?.get(field)
        override fun set(name: String, field: String, value: String) { data.getOrPut(name) { java.util.concurrent.ConcurrentHashMap() }[field] = value }
        override fun clear(name: String) { data.remove(name) }
        override fun names() = data.keys.sorted()
    }

    class OfKeystore(private val keystore: Keystore) : LinkVault {
        override fun get(name: String, field: String) = keystore.field(name, field)
        override fun set(name: String, field: String, value: String) = keystore.setField(name, field, value)
        override fun clear(name: String) { keystore.clear(name) }
        override fun names() = keystore.names()
    }
}

/** The runner side's one paired orchestrator. Only the credential's SHA-256 is kept; the orchestrator holds the credential. */
data class PairedOrchestrator(val id: String, val name: String, val credentialHash: String, val issuedAt: Long, val runnerName: String)

/** The orchestrator side's record of one paired runner. */
data class PairedRunner(val name: String, val url: String, val fingerprint: String, val credential: String, val issuedAt: Long, val runnerId: String)

/**
 * RUNNER-POOL-5 (docs/RUNNER-POOL.md section 7): both sides' pairing state. One daemon can be a runner (paired with exactly one
 * orchestrator), an orchestrator (paired with N runners), or both. The pair credential is per pair: never the owner token, the
 * `infantry remote` token or an agent token.
 */
class RunnerLinkStore(private val vault: LinkVault, private val random: SecureRandom = SecureRandom()) {

    /** This daemon's link id, made once: the `orchestrator:<id>` seat a runner sees, and the runner id an orchestrator records. */
    fun selfId(): String = vault.get(SELF, "id") ?: ("inf-" + token(ID_SIZE)).also { vault.set(SELF, "id", it) }

    /** The PKCS12 password of the runner's certificate, made once. */
    fun tlsPassword(): String = vault.get(SELF, "tls") ?: token(CREDENTIAL_SIZE).also { vault.set(SELF, "tls", it) }

    // ---- runner side ----

    fun orchestrator(): PairedOrchestrator? {
        val id = vault.get(ORCHESTRATOR, "id") ?: return null
        val hash = vault.get(ORCHESTRATOR, "credentialHash") ?: return null
        return PairedOrchestrator(
            id = id,
            name = vault.get(ORCHESTRATOR, "name") ?: id,
            credentialHash = hash,
            issuedAt = vault.get(ORCHESTRATOR, "issuedAt")?.toLongOrNull() ?: 0L,
            runnerName = vault.get(ORCHESTRATOR, "runnerName") ?: "",
        )
    }

    /** Pairs (or re-keys) with [id]; returns the NEW credential, which only the caller ever sees in clear. */
    fun issue(id: String, name: String, runnerName: String, now: Long): String {
        val credential = token(CREDENTIAL_SIZE)
        // The hash goes in first: a crash between the writes leaves the old id with a credential nobody holds (fail closed).
        vault.set(ORCHESTRATOR, "credentialHash", hash(credential))
        vault.set(ORCHESTRATOR, "id", id)
        vault.set(ORCHESTRATOR, "name", name.ifBlank { id })
        vault.set(ORCHESTRATOR, "issuedAt", now.toString())
        vault.set(ORCHESTRATOR, "runnerName", runnerName)
        return credential
    }

    /** True when [credential] is the live credential of the paired orchestrator [id]. Constant-time compare. */
    fun accepts(id: String?, credential: String?): Boolean {
        val paired = orchestrator() ?: return false
        if (id.isNullOrBlank() || credential.isNullOrBlank()) return false
        val idOk = MessageDigest.isEqual(id.toByteArray(), paired.id.toByteArray())
        val credOk = MessageDigest.isEqual(hash(credential).toByteArray(), paired.credentialHash.toByteArray())
        return idOk && credOk
    }

    fun unpair(): Boolean = (orchestrator() != null).also { vault.clear(ORCHESTRATOR) }

    // ---- orchestrator side ----

    fun runners(): List<PairedRunner> = vault.names().filter { it.startsWith(PEER_PREFIX) }.mapNotNull { runner(it.removePrefix(PEER_PREFIX)) }

    fun runner(name: String): PairedRunner? {
        val key = PEER_PREFIX + name
        return PairedRunner(
            name = name,
            url = vault.get(key, "url") ?: return null,
            fingerprint = vault.get(key, "fingerprint") ?: "",
            credential = vault.get(key, "credential") ?: return null,
            issuedAt = vault.get(key, "issuedAt")?.toLongOrNull() ?: 0L,
            runnerId = vault.get(key, "runnerId") ?: "",
        )
    }

    fun saveRunner(r: PairedRunner) {
        val key = PEER_PREFIX + r.name
        vault.set(key, "credential", r.credential)
        vault.set(key, "url", r.url)
        if (r.fingerprint.isNotBlank()) vault.set(key, "fingerprint", r.fingerprint)
        vault.set(key, "issuedAt", r.issuedAt.toString())
        if (r.runnerId.isNotBlank()) vault.set(key, "runnerId", r.runnerId)
    }

    fun removeRunner(name: String): Boolean = (runner(name) != null).also { vault.clear(PEER_PREFIX + name) }

    private fun token(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    companion object {
        const val DIR = "runner-link"
        private const val SELF = "runner-link.self"
        private const val ORCHESTRATOR = "runner-link.orchestrator"
        private const val PEER_PREFIX = "runner-link.peer."
        private const val CREDENTIAL_SIZE = 32
        private const val ID_SIZE = 9

        /** Runner names: what `runners.<name>` and `runner: <name>` use. */
        val NAME = Regex("""^[a-z0-9][a-z0-9-]{0,31}$""")

        fun dir(stateDir: File): File = File(stateDir, DIR)

        /** True once anything was paired on this machine (the link keystore exists); a never-paired daemon opens nothing. */
        fun exists(stateDir: File): Boolean = File(dir(stateDir), Keystore.FILE_NAME).isFile

        /** The daemon's store: a keystore of its own in [dir]. */
        fun open(stateDir: File): RunnerLinkStore = RunnerLinkStore(LinkVault.OfKeystore(Keystore(dir(stateDir).also { it.mkdirs() })))

        fun hash(credential: String): String =
            MessageDigest.getInstance("SHA-256").digest(credential.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
