// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.sites.daemon

import com.superiorsoftllc.infantry.daemon.api.pluginsecrets.PluginSecrets
import com.superiorsoftllc.infantry.daemon.api.pluginsecrets.SecretValue
import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.KeyPair
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

/**
 * SITES-SSH-1: the PC's one SSH identity. An Ed25519 key made in the JVM on first need; its 32-byte seed lives only in this
 * plugin's own keystore entry (field [FIELD]). The private key is never written to a file, plugin storage, a log or an answer:
 * callers get the OpenSSH public line and a [KeyPair] to hand straight to the SSH client.
 */
class DeviceKey(private val secrets: () -> PluginSecrets, private val random: SecureRandom = SecureRandom()) {
    @Volatile private var cached: KeyPair? = null

    fun keyPair(): KeyPair = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    /** `ssh-ed25519 AAAA...`: the public half, the only part that leaves the plugin (to the certificate endpoint). */
    fun publicLine(): String = PublicKeyEntry.toString(keyPair().public).trim()

    private fun load(): KeyPair {
        val stored = try { secrets().get(FIELD) } catch (e: Exception) { null }
        val seed = stored?.let { runCatching { Base64.getDecoder().decode(it.reveal()) }.getOrNull() }?.takeIf { it.size == SEED_BYTES }
            ?: ByteArray(SEED_BYTES).also { random.nextBytes(it) }.also { fresh ->
                try { secrets().set(FIELD, SecretValue(Base64.getEncoder().encodeToString(fresh))) }
                catch (e: Exception) { throw SitesException(SitesException.UNAVAILABLE, "the keystore is locked, so no SSH key can be kept; unlock it and try again") }
            }
        return fromSeed(seed)
    }

    companion object {
        /** The keystore field (architect plan section 3). */
        const val FIELD = "ssh_device_ed25519"
        private const val SEED_BYTES = 32

        fun fromSeed(seed: ByteArray): KeyPair {
            val spec = EdDSAPrivateKeySpec(seed, EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519))
            return KeyPair(EdDSAPublicKey(EdDSAPublicKeySpec(spec.a, spec.params)), EdDSAPrivateKey(spec))
        }
    }
}

/** Where to log in, as the certificate endpoint answered it. [hostKeyFingerprint] is `SHA256:...` once WS-SSH-2 knows it, else null (TOFU). */
data class SshConnection(val host: String, val port: Int, val user: String, val hostKeyFingerprint: String?)

/** One issued user certificate. [toString] hides the certificate so it cannot reach a log by accident. */
class SshCert(val certificate: String, val validBefore: Long, val connection: SshConnection) {
    override fun toString(): String = "SshCert(validBefore=$validBefore, host=${connection.host})"
}

/** The hosting API's certificate call: `POST .../servers/{id}/ssh/certificate`. [SitesService] is the real one; tests pass a fake. */
fun interface CertIssuer { fun issue(serverId: String, publicKey: String): SshCert }

/** Certificates kept in memory, per server, until [marginMs] before `validBefore`; never stored. */
class CertCache(private val issuer: CertIssuer, private val now: () -> Long = System::currentTimeMillis, private val marginMs: Long = DEFAULT_MARGIN_MS) {
    private val certs = ConcurrentHashMap<String, SshCert>()

    fun get(serverId: String, publicKey: String): SshCert {
        certs[serverId]?.takeIf { now() < it.validBefore - marginMs }?.let { return it }
        return issuer.issue(serverId, publicKey).also { certs[serverId] = it }
    }

    fun forget(serverId: String) { certs.remove(serverId) }

    companion object { const val DEFAULT_MARGIN_MS = 5 * 60_000L }
}

/** Pinned host keys, per server id (public data: plugin storage). */
interface HostKeyPins {
    fun get(serverId: String): String?
    fun pin(serverId: String, fingerprint: String)
}

class StorageHostKeyPins(private val storage: PluginStorage, private val now: () -> Long = System::currentTimeMillis) : HostKeyPins {
    init {
        storage.migrate(1, listOf("CREATE TABLE IF NOT EXISTS $TABLE (server_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, pinned_at INTEGER NOT NULL)"))
    }
    override fun get(serverId: String): String? = storage.query("SELECT fingerprint FROM $TABLE WHERE server_id = ?", listOf(serverId)).firstOrNull()?.get("fingerprint") as? String
    override fun pin(serverId: String, fingerprint: String) {
        storage.execute("INSERT INTO $TABLE (server_id, fingerprint, pinned_at) VALUES (?, ?, ?) ON CONFLICT(server_id) DO UPDATE SET fingerprint = excluded.fingerprint, pinned_at = excluded.pinned_at", listOf(serverId, fingerprint, now()))
    }
    companion object { const val TABLE = "infantry_sites_ssh_host_pins" }
}

class MemoryHostKeyPins : HostKeyPins {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(serverId: String): String? = map[serverId]
    override fun pin(serverId: String, fingerprint: String) { map[serverId] = fingerprint }
}

/**
 * The host key rule: the fingerprint the hosting service reports wins (WS-SSH-2); without one, trust on first use and refuse a
 * change. Returns null when the key is accepted, else the reason it is refused.
 */
object HostKeyRule {
    fun check(serverId: String, presented: String, reported: String?, pins: HostKeyPins): String? {
        if (reported != null) {
            if (reported != presented) return "the server's host key ($presented) does not match the one Infantry AI Hosting reports ($reported); refusing to connect"
            if (pins.get(serverId) != presented) pins.pin(serverId, presented)
            return null
        }
        val pinned = pins.get(serverId)
        if (pinned == null) { pins.pin(serverId, presented); return null }
        return if (pinned == presented) null
        else "the server's host key changed (pinned $pinned, now $presented); refusing to connect. If the server was rebuilt, this is expected once WS-SSH-2 reports its key"
    }
}

/** The result of one remote command. Output is capped; [truncated] says when bytes were dropped. */
data class SshExecResult(val exitCode: Int?, val stdout: String, val stderr: String, val truncated: Boolean, val timedOut: Boolean)

/** The bounds of one SSH call; every number is a `daemon.config` key with this default. */
data class SshLimits(val connectTimeoutMs: Long, val execTimeoutMs: Long, val maxOutputBytes: Int, val maxUploadBytes: Int) {
    companion object {
        const val CONNECT_TIMEOUT_KEY = "ssh.connectTimeoutSec"
        const val EXEC_TIMEOUT_KEY = "ssh.execTimeoutSec"
        const val EXEC_TIMEOUT_MAX_KEY = "ssh.execTimeoutMaxSec"
        const val MAX_OUTPUT_KEY = "ssh.maxOutputBytes"
        const val MAX_UPLOAD_KEY = "ssh.maxUploadBytes"
        const val DEFAULT_CONNECT_TIMEOUT_SEC = 15L
        const val DEFAULT_EXEC_TIMEOUT_SEC = 120L
        const val DEFAULT_EXEC_TIMEOUT_MAX_SEC = 1800L
        const val DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024
        const val DEFAULT_MAX_UPLOAD_BYTES = 1024 * 1024
    }
}

/**
 * The SSH client (Apache MINA SSHD, shaded into this jar: works on Windows with no OpenSSH installed). It logs in with the device
 * key and a user certificate, checks the host key with [HostKeyRule], runs one command and closes. Nothing it returns holds the
 * key or the certificate.
 */
class SshExec(private val pins: HostKeyPins) {
    fun run(serverId: String, keys: KeyPair, cert: SshCert, command: String, stdin: ByteArray?, limits: SshLimits): SshExecResult = withPluginLoader {
        val certKey = parseCert(cert.certificate)
        var refused: String? = null
        val client = SshClient.setUpDefaultClient()
        client.serverKeyVerifier = ServerKeyVerifier { _, _, key ->
            refused = HostKeyRule.check(serverId, fingerprint(key), cert.connection.hostKeyFingerprint, pins)
            refused == null
        }
        client.start()
        try {
            val session = try {
                client.connect(cert.connection.user, cert.connection.host, cert.connection.port).verify(limits.connectTimeoutMs).session
            } catch (e: Exception) {
                throw SitesException(SitesException.UNAVAILABLE, "could not reach ${cert.connection.host}:${cert.connection.port} over SSH")
            }
            session.use { s ->
                s.addPublicKeyIdentity(KeyPair(certKey, keys.private))
                try { s.auth().verify(limits.connectTimeoutMs) } catch (e: Exception) {
                    refused?.let { throw SitesException(SitesException.HOST_KEY, it) }
                    throw SitesException(SitesException.AUTH_FAILED, "the server refused the SSH certificate")
                }
                val out = CappedStream(limits.maxOutputBytes)
                val err = CappedStream(limits.maxOutputBytes)
                s.createExecChannel(command).use { ch ->
                    ch.out = out
                    ch.err = err
                    if (stdin != null) ch.`in` = ByteArrayInputStream(stdin)
                    ch.open().verify(limits.connectTimeoutMs)
                    val events = ch.waitFor(EnumSet.of(ClientChannelEvent.CLOSED, ClientChannelEvent.EXIT_STATUS), limits.execTimeoutMs)
                    val timedOut = ClientChannelEvent.TIMEOUT in events
                    if (!timedOut && ClientChannelEvent.CLOSED !in events) ch.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), limits.connectTimeoutMs)
                    SshExecResult(ch.exitStatus, out.text(), err.text(), out.dropped || err.dropped, timedOut)
                }
            }
        } finally {
            runCatching { client.stop() }
        }
    }

    companion object {
        fun fingerprint(key: PublicKey): String = KeyUtils.getFingerPrint(key)

        fun parseCert(line: String): PublicKey = try {
            withPluginLoader { PublicKeyEntry.parsePublicKeyEntry(line.trim()).resolvePublicKey(null, emptyMap(), PublicKeyEntryResolver.FAILING) }
                ?: throw IllegalStateException("empty")
        } catch (e: Exception) {
            throw SitesException(SitesException.UNAVAILABLE, "the hosting service sent a certificate this client cannot read")
        }

        /** MINA looks up its crypto providers through the thread's context class loader; inside the daemon that must be this plugin's. */
        fun <T> withPluginLoader(block: () -> T): T {
            val t = Thread.currentThread()
            val old = t.contextClassLoader
            t.contextClassLoader = SshExec::class.java.classLoader
            return try { block() } finally { t.contextClassLoader = old }
        }

        /** A POSIX shell single-quoted word. */
        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}

/** Keeps the first [cap] bytes and drops the rest (counting that it did). */
private class CappedStream(private val cap: Int) : OutputStream() {
    private val buf = ByteArrayOutputStream()
    var dropped = false
        private set
    override fun write(b: Int) { if (buf.size() < cap) buf.write(b) else dropped = true }
    override fun write(b: ByteArray, off: Int, len: Int) {
        val room = (cap - buf.size()).coerceAtLeast(0)
        if (len > room) dropped = true
        if (room > 0) buf.write(b, off, minOf(len, room))
    }
    fun text(): String = buf.toString(Charsets.UTF_8)
}

/**
 * SITES-SSH-1: what the agent tools call. It picks the server (a name or an id from the list; optional when there is one),
 * gets a certificate (cached), connects, and retries once with a fresh certificate when the server refuses the cached one.
 */
class SitesSsh(
    private val servers: () -> List<Pair<String, String>>,
    private val key: DeviceKey,
    private val certs: CertCache,
    private val exec: SshExec,
    private val limits: () -> SshLimits,
) {
    fun run(server: String?, command: String, timeoutSec: Long?, stdin: ByteArray? = null): Pair<String, SshExecResult> {
        require(command.isNotBlank()) { "'command' is required" }
        val (id, name) = resolve(server)
        val base = limits()
        val l = if (timeoutSec != null && timeoutSec > 0) base.copy(execTimeoutMs = timeoutSec * 1000) else base
        val keys = key.keyPair()
        val pub = key.publicLine()
        return name to try {
            exec.run(id, keys, certs.get(id, pub), command, stdin, l)
        } catch (e: SitesException) {
            if (e.kind != SitesException.AUTH_FAILED) throw e
            certs.forget(id)
            exec.run(id, keys, certs.get(id, pub), command, stdin, l)
        }
    }

    fun upload(server: String?, path: String, content: String): Pair<String, SshExecResult> {
        require(path.isNotBlank()) { "'path' is required" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= limits().maxUploadBytes) { "'content' is ${bytes.size} bytes; the limit is ${limits().maxUploadBytes} (setting ${SshLimits.MAX_UPLOAD_KEY})" }
        val q = SshExec.shellQuote(path)
        return run(server, "mkdir -p \"\$(dirname -- $q)\" && cat > $q", null, bytes)
    }

    private fun resolve(server: String?): Pair<String, String> {
        val all = servers()
        val want = server?.trim().orEmpty()
        if (want.isEmpty()) {
            return when (all.size) {
                1 -> all.single()
                0 -> throw SitesException(SitesException.NOT_FOUND, "this account has no servers; create one on the Infantry AI Hosting site")
                else -> throw IllegalArgumentException("'server' is required: this account has ${all.size} servers (${all.joinToString { it.second.ifBlank { it.first } }})")
            }
        }
        all.firstOrNull { it.first.equals(want, ignoreCase = true) }?.let { return it }
        val byName = all.filter { it.second.equals(want, ignoreCase = true) }
        return when (byName.size) {
            1 -> byName.single()
            0 -> throw SitesException(SitesException.NOT_FOUND, "no server named or with id '$want' (see infantry_sites_list)")
            else -> throw IllegalArgumentException("${byName.size} servers are named '$want'; pass its id")
        }
    }
}
