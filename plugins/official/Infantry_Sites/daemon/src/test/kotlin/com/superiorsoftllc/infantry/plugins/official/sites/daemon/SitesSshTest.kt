// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.sites.daemon

import com.sun.net.httpserver.HttpServer
import com.superiorsoftllc.infantry.daemon.api.http.Http
import com.superiorsoftllc.infantry.daemon.api.http.HttpException
import com.superiorsoftllc.infantry.daemon.api.http.HttpResult
import com.superiorsoftllc.infantry.daemon.api.http.HttpSpec
import com.superiorsoftllc.infantry.daemon.api.pluginsecrets.NoPluginSecrets
import com.superiorsoftllc.infantry.daemon.api.pluginsecrets.PluginSecrets
import com.superiorsoftllc.infantry.daemon.api.pluginsecrets.SecretValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.sshd.certificate.OpenSshCertificateBuilder
import org.apache.sshd.common.config.keys.OpenSshCertificate
import org.apache.sshd.common.config.keys.PublicKeyEntry
import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.SshException
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPair
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SSH_TOKEN = "ssh-bearer-secret"
private const val SID = "22222222-2222-4222-8222-222222222222"

private class MemSecrets : PluginSecrets {
    val map = mutableMapOf<String, String>()
    override fun get(field: String): SecretValue? = map[field]?.let(::SecretValue)
    override fun set(field: String, value: SecretValue) { map[field] = value.reveal() }
    override fun clear(field: String): Boolean = map.remove(field) != null
}

private class SshJdkHttp : Http {
    private val client = HttpClient.newHttpClient()
    override fun request(spec: HttpSpec): HttpResult {
        val b = HttpRequest.newBuilder(URI(spec.url))
        (spec.headers + spec.secretHeaders).forEach { (k, v) -> b.header(k, v) }
        b.method(spec.method, spec.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        val r = try { client.send(b.build(), HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { throw HttpException("no answer") }
        return HttpResult(r.statusCode(), r.headers().map().mapValues { it.value.first() }, r.body())
    }
    override fun json(spec: HttpSpec): JsonElement = Json.parseToJsonElement(request(spec).body)
}

/** Issues a user certificate for [user] signed by [ca]; the same thing WS-SSH-1 does on the server. */
private fun issueCert(ca: KeyPair, userPub: java.security.PublicKey, after: Instant, before: Instant): String {
    val cert = OpenSshCertificateBuilder.userCertificate()
        .serial(1L).id("sites-ssh-test").publicKey(userPub)
        .principals(listOf("superiorsoft"))
        .extensions(listOf(OpenSshCertificate.CertificateOption("permit-pty")))
        .validAfter(after).validBefore(before)
        .sign(ca, "ssh-ed25519")
    return PublicKeyEntry.toString(cert).trim()
}

class SitesSshTest {
    private var server: HttpServer? = null

    @AfterTest fun stop() { server?.stop(0) }

    // ---- device key ---------------------------------------------------------------------------------------------------

    @Test
    fun `the device key is made once, kept only in the keystore field, and the same key comes back`() {
        val secrets = MemSecrets()
        val a = DeviceKey({ secrets })
        val line = a.publicLine()
        assertTrue(line.startsWith("ssh-ed25519 "), line)
        assertEquals(setOf(DeviceKey.FIELD), secrets.map.keys)
        assertEquals(32, Base64.getDecoder().decode(secrets.map.getValue(DeviceKey.FIELD)).size)
        assertEquals(line, DeviceKey({ secrets }).publicLine(), "a restart reads the same key")
        val seed = secrets.map.getValue(DeviceKey.FIELD)
        assertFalse(seed in line, "the public line holds no private seed")
        assertFalse(seed in SecretValue(seed).toString())
    }

    @Test
    fun `a locked keystore refuses plainly and the message holds no key`() {
        val e = assertFailsWith<SitesException> { DeviceKey({ NoPluginSecrets }).keyPair() }
        assertEquals(SitesException.UNAVAILABLE, e.kind)
        assertTrue("keystore" in e.message!!)
    }

    // ---- certificate cache --------------------------------------------------------------------------------------------

    @Test
    fun `a certificate is reused until five minutes before it expires, then renewed, forget drops it`() {
        var now = 1_000_000L
        var issued = 0
        val cache = CertCache(CertIssuer { _, _ -> issued++; SshCert("cert-$issued", now + 60 * 60_000L, SshConnection("h", 22, "u", null)) }, { now })
        val first = cache.get(SID, "pub")
        assertEquals(first, cache.get(SID, "pub"))
        now += 54 * 60_000L
        assertEquals(1, issued, "54 min in: still the cached one")
        cache.get(SID, "pub")
        now += 60_000L + 1
        assertEquals("cert-2", cache.get(SID, "pub").certificate, "inside the 5 min margin: renewed")
        cache.forget(SID)
        assertEquals("cert-3", cache.get(SID, "pub").certificate)
        assertFalse("cert-3" in cache.get(SID, "pub").toString(), "toString hides the certificate")
    }

    // ---- host key rule ------------------------------------------------------------------------------------------------

    @Test
    fun `host keys are pinned on first use and a change is refused, a reported fingerprint wins`() {
        val pins = MemoryHostKeyPins()
        assertNull(HostKeyRule.check(SID, "SHA256:aaa", null, pins))
        assertEquals("SHA256:aaa", pins.get(SID))
        assertNull(HostKeyRule.check(SID, "SHA256:aaa", null, pins))
        assertTrue(HostKeyRule.check(SID, "SHA256:bbb", null, pins)!!.contains("changed"))
        assertTrue(HostKeyRule.check(SID, "SHA256:bbb", "SHA256:ccc", pins)!!.contains("does not match"))
        assertNull(HostKeyRule.check(SID, "SHA256:ccc", "SHA256:ccc", pins), "the hosting service's word replaces an old pin")
        assertEquals("SHA256:ccc", pins.get(SID))
    }

    // ---- SSH-DAEMON-1: what failed inside the daemon ------------------------------------------------------------------------

    /** A map-backed storage that records each statement; refuses `UPDATE`, the word the daemon's table check misreads in an upsert. */
    private class RecordingStorage : PluginStorage {
        val statements = mutableListOf<String>()
        val rows = linkedMapOf<String, String>()
        override fun migrate(version: Int, sql: List<String>) { statements += sql }
        override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> { statements += sql; return listOfNotNull(rows[args[0]]?.let { mapOf("fingerprint" to it) }) }
        override fun execute(sql: String, args: List<Any?>): Int {
            statements += sql
            require(!Regex("""UPDATE""", RegexOption.IGNORE_CASE).containsMatchIn(sql)) { "table 'SET' is not owned by this module (prefix 'infantry_sites_')" }
            rows[args[0] as String] = args[1] as String
            return 1
        }
        override fun <T> transaction(block: () -> T): T = block()
    }

    @Test
    fun `storage pins are written without an upsert and a re-pin replaces the old one`() {
        val storage = RecordingStorage()
        val pins = StorageHostKeyPins(storage)
        pins.pin(SID, "SHA256:aaa")
        pins.pin(SID, "SHA256:bbb")
        assertEquals("SHA256:bbb", pins.get(SID))
        assertTrue(storage.statements.none { it.contains("ON CONFLICT", ignoreCase = true) }, storage.statements.toString())
    }

    /** An in-process SSH server on port 0 that turns every login down; its host key is made in memory. */
    private fun refusingSshd(): SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"; port = 0
        keyPairProvider = SimpleGeneratorHostKeyProvider()
        publickeyAuthenticator = PublickeyAuthenticator { _, _, _ -> false }
        start()
    }

    private fun certFor(device: KeyPair, port: Int): SshCert {
        val ca = DeviceKey.fromSeed(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val now = Instant.now()
        return SshCert(issueCert(ca, device.public, now.minusSeconds(60), now.plusSeconds(3600)), now.plusSeconds(3600).toEpochMilli(), SshConnection("127.0.0.1", port, "superiorsoft", null))
    }

    /** Runs [block] on a thread whose context class loader is not this plugin's, as a daemon tool thread is. */
    private fun <T> onForeignLoaderThread(block: () -> T): T {
        var out: Result<T>? = null
        val t = Thread { out = runCatching(block) }
        t.contextClassLoader = ClassLoader.getPlatformClassLoader()
        t.start(); t.join(60_000)
        return out!!.getOrThrow()
    }

    @Test
    fun `a host key check that fails inside the daemon is its own plain error, never auth_failed`() {
        val sshd = refusingSshd()
        try {
            val device = DeviceKey.fromSeed(ByteArray(32).also { SecureRandom().nextBytes(it) })
            val logs = mutableListOf<String>()
            val broken = StorageHostKeyPins(object : PluginStorage by RecordingStorage() {
                override fun execute(sql: String, args: List<Any?>): Int = throw IllegalArgumentException("table 'SET' is not owned by this module (prefix 'infantry_sites_')")
            })
            val e = assertFailsWith<SitesException> {
                onForeignLoaderThread { SshExec(broken) { logs += it }.run(SID, device, certFor(device, sshd.port), "id -un", null, SshLimits(15_000, 15_000, 1024, 1024)) }
            }
            assertEquals(SitesException.UNAVAILABLE, e.kind, e.message)
            assertTrue(e.message!!.contains("host key") && e.message!!.contains("table 'SET'"), e.message)
            assertTrue(logs.any { it.startsWith("ssh auth on 127.0.0.1:${sshd.port} failed:") }, logs.toString())
        } finally { sshd.stop(true) }
    }

    @Test
    fun `only a no-more-methods refusal counts as auth_failed`() {
        val refusal = SshException(SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE, "No more authentication methods available")
        assertTrue(SshExec.isAuthRefusal(refusal))
        assertTrue(SshExec.isAuthRefusal(java.io.IOException("wrapped", refusal)))
        assertFalse(SshExec.isAuthRefusal(SshException("Session is being closed")))
        assertFalse(SshExec.isAuthRefusal(SshException("[ssh-connection]: Failed (IllegalArgumentException) to execute", IllegalArgumentException("table 'SET'"))))
        assertFalse(SshExec.isAuthRefusal(java.net.SocketTimeoutException("timeout")))
        assertEquals("IllegalArgumentException: table 'SET'", SshExec.summary(SshException("outer", IllegalArgumentException("table 'SET'"))))
    }

    // ---- the certificate endpoint and its error map ----------------------------------------------------------------------

    private fun stub(status: Int, body: String, seen: MutableList<String>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            seen += "${ex.requestMethod} ${ex.requestURI.path} ${ex.requestHeaders.getFirst("Authorization")} ${ex.requestBody.readBytes().toString(Charsets.UTF_8)}"
            val b = body.toByteArray()
            ex.sendResponseHeaders(status, b.size.toLong().takeIf { it > 0 } ?: -1)
            if (b.isNotEmpty()) ex.responseBody.use { it.write(b) } else ex.close()
        }
        s.start(); server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @Test
    fun `the certificate call posts the public key with the bearer and reads the connection`() {
        val seen = mutableListOf<String>()
        val base = stub(200, """{"certificate":"ssh-ed25519-cert-v01@openssh.com AAAACERT","validAfter":1,"validBefore":99,"connection":{"host":"203.0.113.9","port":2222,"user":"deploy","hostKeyFingerprint":"SHA256:xyz"}}""", seen)
        val svc = SitesService(SshJdkHttp(), TokenSource { SSH_TOKEN }, { base })
        val c = svc.sshCertificate(SID, "ssh-ed25519 AAAAPUB")
        assertEquals(SshConnection("203.0.113.9", 2222, "deploy", "SHA256:xyz"), c.connection)
        assertEquals(99L, c.validBefore)
        val call = seen.single()
        assertTrue(call.startsWith("POST /hosting/v1/servers/$SID/ssh/certificate Bearer $SSH_TOKEN "), call)
        assertTrue("\"publicKey\":\"ssh-ed25519 AAAAPUB\"" in call, call)
    }

    @Test
    fun `each refusal of the certificate endpoint is a plain message`() {
        val cases = mapOf(
            400 to SitesException.BAD_REQUEST, 403 to SitesException.NOT_ENTITLED, 404 to SitesException.NOT_FOUND,
            409 to SitesException.NOT_RUNNING, 429 to SitesException.UNAVAILABLE, 503 to SitesException.NOT_CONFIGURED,
        )
        for ((status, kind) in cases) {
            server?.stop(0)
            val base = stub(status, """{"error":"x"}""", mutableListOf())
            val e = assertFailsWith<SitesException>("HTTP $status") { SitesService(SshJdkHttp(), TokenSource { SSH_TOKEN }, { base }).sshCertificate(SID, "ssh-ed25519 AAAA") }
            assertEquals(kind, e.kind, "HTTP $status")
            assertFalse(SSH_TOKEN in e.message!!)
        }
        server?.stop(0)
        val base = stub(409, "{}", mutableListOf())
        val e = assertFailsWith<SitesException> { SitesService(SshJdkHttp(), TokenSource { SSH_TOKEN }, { base }).sshCertificate(SID, "k") }
        assertEquals("the server is not running; start it on the site", e.message)
        assertFailsWith<IllegalArgumentException> { SitesService(SshJdkHttp(), TokenSource { SSH_TOKEN }, { base }).sshCertificate("../x", "k") }
    }

    // ---- server choice ----------------------------------------------------------------------------------------------

    @Test
    fun `server is optional with one server, matched by id or name otherwise`() {
        var list = listOf(SID to "web")
        val calls = mutableListOf<String>()
        val ssh = SitesSsh({ list }, DeviceKey({ MemSecrets() }), CertCache(CertIssuer { id, _ -> calls += id; throw SitesException(SitesException.NOT_RUNNING, "stop here") }),
            SshExec(MemoryHostKeyPins()), { SshLimits(1000, 1000, 100, 100) })
        assertEquals(SitesException.NOT_RUNNING, assertFailsWith<SitesException> { ssh.run(null, "echo ok", null) }.kind)
        list = listOf(SID to "web", "33333333-3333-4333-8333-333333333333" to "db")
        assertFailsWith<IllegalArgumentException> { ssh.run(null, "echo ok", null) }
        assertFailsWith<SitesException> { ssh.run("db", "echo ok", null) }
        assertFailsWith<SitesException> { ssh.run("WEB", "echo ok", null) }
        assertEquals(SitesException.NOT_FOUND, assertFailsWith<SitesException> { ssh.run("nope", "echo ok", null) }.kind)
        assertEquals(listOf(SID, "33333333-3333-4333-8333-333333333333", SID), calls)
        assertFailsWith<IllegalArgumentException> { ssh.upload("web", "/tmp/x", "a".repeat(101)) }
    }

    @Test
    fun `shell quoting survives quotes`() {
        assertEquals("'a'\\''b'", SshExec.shellQuote("a'b"))
    }

    // ---- acceptance: module.json and tools() list the same tools; no key or cert reaches a log or answer path ------------

    @Test
    fun `module json daemon tools equal the registered tools`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "plugins/official/Infantry_Sites") }.first { it.isDirectory }
        val declared = Json.parseToJsonElement(File(root, "module.json").readText()).jsonObject["daemon"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        val svc = SitesService(SshJdkHttp(), TokenSource { "" }, { "" })
        val ssh = SitesSsh({ emptyList() }, DeviceKey({ MemSecrets() }), CertCache(CertIssuer { _, _ -> error("n/a") }), SshExec(MemoryHostKeyPins()), { SshLimits(1, 1, 1, 1) })
        assertEquals(declared, SitesDaemonPlugin().tools(svc, ssh).map { it.name }.toSet())
    }

    @Test
    fun `no main source logs or answers a private key, seed or certificate`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "plugins/official/Infantry_Sites/daemon/src/main") }.first { it.isDirectory }
        val leak = Regex("""(log|ToolResult|HttpReply|put)\(.*(\.private\b|reveal\(\)|certificate\b|seed)""")
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f -> f.readLines().withIndex().filter { leak.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" } }.toList()
        assertEquals(emptyList(), hits)
    }
}

/**
 * The integration proof against a real OpenSSH server: a local sshd container (image superiorsoft-sshd-test:local, fixed name
 * [NAME], deleted after) trusting a test CA. Runs only with SITES_SSHD_IT=1 (it needs Docker).
 */
class SitesSshIntegrationTest {
    private val name = "superiorsoft-sshd-test-sites"

    private fun docker(vararg args: String): String {
        val p = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(120, TimeUnit.SECONDS)) { "docker ${args.first()} timed out" }
        return out.trim()
    }

    @Test
    fun `a CA-signed certificate logs in and runs echo ok, an expired one and a changed host key are refused`() {
        if (System.getenv("SITES_SSHD_IT") != "1") { println("SitesSshIntegrationTest skipped (set SITES_SSHD_IT=1)"); return }
        val rnd = SecureRandom()
        val ca = DeviceKey.fromSeed(ByteArray(32).also { rnd.nextBytes(it) })
        val caLine = PublicKeyEntry.toString(ca.public).trim()
        val setup = """
            printf '%s\n' '$caLine' > /etc/ssh/ca.pub
            printf '\nTrustedUserCAKeys /etc/ssh/ca.pub\nPasswordAuthentication no\nPubkeyAuthentication yes\n' >> /etc/ssh/sshd_config
        """.trimIndent()
        docker("rm", "-f", name)
        try {
            val id = docker("run", "-d", "--name", name, "-p", "127.0.0.1::22", "-e", "SUPERIORSOFT_SETUP_B64=" + Base64.getEncoder().encodeToString(setup.toByteArray()), "superiorsoft-sshd-test:local")
            println("container ${id.take(12)} $name")
            val port = docker("port", name, "22/tcp").lines().first().substringAfterLast(':').trim().toInt()
            val deadline = System.currentTimeMillis() + 30_000
            while (true) {
                val up = runCatching { Socket("127.0.0.1", port).use { s -> s.soTimeout = 2000; s.getInputStream().read() > 0 } }.getOrDefault(false)
                if (up) break
                check(System.currentTimeMillis() < deadline) { "sshd did not come up: " + docker("logs", name) }
                Thread.sleep(300)
            }

            val secrets = MemSecrets()
            val device = DeviceKey({ secrets })
            val pins = MemoryHostKeyPins()
            val now = Instant.now()
            val conn = SshConnection("127.0.0.1", port, "superiorsoft", null)
            val good = SshCert(issueCert(ca, device.keyPair().public, now.minusSeconds(300), now.plusSeconds(3600)), now.plusSeconds(3600).toEpochMilli(), conn)
            val limits = SshLimits(15_000, 30_000, 64 * 1024, 1024 * 1024)
            val ssh = SitesSsh({ listOf(SID to "web") }, device, CertCache(CertIssuer { _, _ -> good }), SshExec(pins), { limits })

            val (srv, r) = ssh.run(null, "echo ok", null)
            println("echo ok -> server=$srv exit=${r.exitCode} stdout=${r.stdout.trim()} stderr=${r.stderr.trim()}")
            assertEquals(0, r.exitCode)
            assertEquals("ok", r.stdout.trim())
            val pinned = pins.get(SID)
            println("host key pinned on first use: $pinned")
            assertTrue(pinned!!.startsWith("SHA256:"))

            val (_, w) = ssh.upload("web", "/home/superiorsoft/dir with space/it's.txt", "hello\n")
            assertEquals(0, w.exitCode, w.stderr)
            val (_, back) = ssh.run("web", "cat \"/home/superiorsoft/dir with space/it's.txt\"; whoami", null)
            println("upload + cat -> ${back.stdout.trim().replace('\n', '|')}")
            assertEquals("hello\nsuperiorsoft", back.stdout.trim())

            val (_, big) = SitesSsh({ listOf(SID to "web") }, device, CertCache(CertIssuer { _, _ -> good }), SshExec(pins), { limits.copy(maxOutputBytes = 10) }).run(null, "seq 1 1000", null)
            assertTrue(big.truncated && big.stdout.length == 10, "output is capped")

            val (_, slow) = ssh.run(null, "sleep 20", 1)
            assertTrue(slow.timedOut, "timeoutSec ends a long command")

            val expired = SshCert(issueCert(ca, device.keyPair().public, now.minusSeconds(7200), now.minusSeconds(3600)), now.minusSeconds(3600).toEpochMilli(), conn)
            val e1 = assertFailsWith<SitesException> { SshExec(pins).run(SID, device.keyPair(), expired, "echo ok", null, limits) }
            println("expired cert -> ${e1.kind}: ${e1.message}")
            assertEquals(SitesException.AUTH_FAILED, e1.kind)

            val otherCa = DeviceKey.fromSeed(ByteArray(32).also { rnd.nextBytes(it) })
            val foreign = SshCert(issueCert(otherCa, device.keyPair().public, now.minusSeconds(300), now.plusSeconds(3600)), now.plusSeconds(3600).toEpochMilli(), conn)
            assertEquals(SitesException.AUTH_FAILED, assertFailsWith<SitesException> { SshExec(pins).run(SID, device.keyPair(), foreign, "echo ok", null, limits) }.kind)

            pins.pin(SID, "SHA256:not-this-server")
            val e2 = assertFailsWith<SitesException> { ssh.run(null, "echo ok", null) }
            println("changed host key -> ${e2.kind}: ${e2.message}")
            assertEquals(SitesException.HOST_KEY, e2.kind)

            val reported = SshCert(good.certificate, good.validBefore, conn.copy(hostKeyFingerprint = pinned))
            assertEquals(0, SshExec(MemoryHostKeyPins()).run(SID, device.keyPair(), reported, "true", null, limits).exitCode, "a reported fingerprint is trusted without a pin")
            assertNotEquals(pinned, "SHA256:not-this-server")

            val outputs = listOf(r, w, back, big, slow).joinToString { it.toString() } + e1.message + e2.message
            val seed = secrets.map.getValue(DeviceKey.FIELD)
            assertFalse(seed in outputs || good.certificate.substringAfter(' ').take(40) in outputs, "no key or certificate in any answer")
        } finally {
            docker("rm", "-f", name)
        }
    }
}
