package com.superiorsoftllc.agenthq.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UPDATE-1: the updater against a LOCAL fake GitHub Releases server (never the real GitHub). It serves
 * `/repos/{owner}/{repo}/releases/latest` and the release's assets, and records every request's headers.
 */
class UpdaterTest {
    private val servers = mutableListOf<FakeGitHub>()
    private val temp = Files.createTempDirectory("updater-test-").toFile()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop() }
        temp.deleteRecursively()
    }

    private fun fake(): FakeGitHub = FakeGitHub().also { servers += it }

    private fun config(server: FakeGitHub, current: String, token: String? = null) =
        UpdateConfig("superiorsoft/agenthq", SemVer.parse(current)!!, server.base) { token }

    @Test
    fun `a newer tag is offered with its version, notes and the INSTALL-1 msi`() {
        val gh = fake().release("v1.0.4", notes = "Fixes the composer.", msi = "AgentHQ-1.0.4.msi" to "new build".toByteArray())
        val check = Updater(config(gh, "1.0.3")).check()
        val available = assertIs<UpdateCheck.Available>(check, "a newer tag than the running 1.0.3 must be offered, got $check")
        assertEquals(SemVer(1, 0, 4), available.update.latest)
        assertEquals(SemVer(1, 0, 3), available.update.current)
        assertEquals("Fixes the composer.", available.update.notes)
        assertEquals("AgentHQ-1.0.4.msi", available.update.installer.name)
    }

    @Test
    fun `the same or an older tag is not offered`() {
        val gh = fake().release("v1.0.3", msi = "AgentHQ-1.0.3.msi" to "x".toByteArray())
        assertIs<UpdateCheck.UpToDate>(Updater(config(gh, "1.0.3")).check())
        assertIs<UpdateCheck.UpToDate>(Updater(config(gh, "1.1.0")).check())
    }

    @Test
    fun `a private repo gets the token as a Bearer header, and without it the 404 is a short message`() {
        val gh = fake().release("v2.0.0", msi = "AgentHQ-2.0.0.msi" to "private build".toByteArray(), requireToken = "s3cret-token")
        val withToken = Updater(config(gh, "1.0.0", token = "s3cret-token")).check()
        assertIs<UpdateCheck.Available>(withToken, "with the token the private release is visible, got $withToken")
        assertEquals("Bearer s3cret-token", gh.requests.last().authorization)

        val without = Updater(config(gh, "1.0.0")).check()
        val failed = assertIs<UpdateCheck.Failed>(without)
        assertIs<UpdateFailure.NotFound>(failed.failure)
        assertNull(gh.requests.last().authorization, "no token configured: no Authorization header")
        assertFalse("s3cret" in failed.failure.message)
    }

    @Test
    fun `the private asset downloads with the token, and the token never follows a redirect to another host`() {
        val storage = fake()
        val bytes = "the 2.0.0 installer".toByteArray()
        storage.raw("/blob/AgentHQ-2.0.0.msi", bytes)
        val gh = fake().release("v2.0.0", msi = "AgentHQ-2.0.0.msi" to bytes, requireToken = "tok", redirectAssetsTo = storage.base + "/blob")
        val config = config(gh, "1.0.0", token = "tok")
        val update = (Updater(config).check() as UpdateCheck.Available).update
        val got = Updater(config).download(update, File(temp, "dl"))
        val verified = assertIs<Fetched.Ok<VerifiedInstaller>>(got, "got $got").value
        assertTrue(bytes.contentEquals(verified.file.readBytes()))
        assertEquals("Bearer tok", gh.requests.last { it.path.contains("/assets/") }.authorization)
        assertEquals("application/octet-stream", gh.requests.last { it.path.contains("/assets/") }.accept)
        assertNull(storage.requests.single().authorization, "the token must not leave the API host")
    }

    @Test
    fun `rate limited is a short message, not an exception`() {
        val gh = fake().rateLimited()
        val failed = assertIs<UpdateCheck.Failed>(Updater(config(gh, "1.0.0")).check())
        assertIs<UpdateFailure.RateLimited>(failed.failure)
        assertTrue("rate limit" in failed.failure.message, failed.failure.message)
    }

    @Test
    fun `offline is a short message`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val config = UpdateConfig("o/r", SemVer(1, 0, 0), "http://127.0.0.1:$closedPort")
        val failed = assertIs<UpdateCheck.Failed>(Updater(config).check())
        assertIs<UpdateFailure.Offline>(failed.failure)
    }

    @Test
    fun `a verified download matches the published SHA-256`() {
        val bytes = "installer bytes 1.0.4".toByteArray()
        val gh = fake().release("v1.0.4", msi = "AgentHQ-1.0.4.msi" to bytes, sha256 = sha256(bytes))
        val config = config(gh, "1.0.3")
        val update = (Updater(config).check() as UpdateCheck.Available).update
        assertEquals("AgentHQ-1.0.4.msi.sha256", update.checksum?.name)
        val verified = assertIs<Fetched.Ok<VerifiedInstaller>>(Updater(config).download(update, File(temp, "ok"))).value
        assertTrue(verified.checksumVerified)
        assertEquals(sha256(bytes), verified.sha256)
    }

    @Test
    fun `an asset whose SHA-256 does not match is refused and deleted`() {
        val bytes = "tampered installer".toByteArray()
        val gh = fake().release("v1.0.4", msi = "AgentHQ-1.0.4.msi" to bytes, sha256 = sha256("the real one".toByteArray()))
        val config = config(gh, "1.0.3")
        val update = (Updater(config).check() as UpdateCheck.Available).update
        val dir = File(temp, "bad")
        val failed = assertIs<Fetched.Failed>(Updater(config).download(update, dir))
        assertIs<UpdateFailure.BadAsset>(failed.failure)
        assertTrue("SHA-256 mismatch" in failed.failure.message, failed.failure.message)
        assertFalse(File(dir, "AgentHQ-1.0.4.msi").exists(), "a refused installer must not stay on disk")
    }

    @Test
    fun `an asset whose size does not match the release is refused`() {
        val bytes = "short".toByteArray()
        val gh = fake().release("v1.0.4", msi = "AgentHQ-1.0.4.msi" to bytes, reportedSize = bytes.size + 10L)
        val config = config(gh, "1.0.3")
        val update = (Updater(config).check() as UpdateCheck.Available).update
        val failed = assertIs<Fetched.Failed>(Updater(config).download(update, File(temp, "size")))
        assertIs<UpdateFailure.BadAsset>(failed.failure)
    }

    @Test
    fun `a newer release without a Windows installer is a short message`() {
        val gh = fake().release("v1.0.4", msi = "AgentHQ-1.0.4.dmg" to "mac".toByteArray())
        val failed = assertIs<UpdateCheck.Failed>(Updater(config(gh, "1.0.3")).check())
        assertIs<UpdateFailure.NoWindowsAsset>(failed.failure)
    }

    @Test
    fun `a dev build never updates, and an installed one needs a repo`() {
        val dev = UpdateSettings.resolve(installed = false, version = "1.0.0", stateDir = temp, setting = { "o/r" })
        assertIs<UpdateSettings.Disabled>(dev)
        val noRepo = UpdateSettings.resolve(installed = true, version = "1.0.0", stateDir = temp, setting = { null })
        assertIs<UpdateSettings.Disabled>(noRepo)
        File(temp, UpdateSettings.FILE_NAME).writeText("repo=superiorsoft/agenthq\ntoken=from-a-file\n")
        val fromFile = assertIs<UpdateSettings.Enabled>(
            UpdateSettings.resolve(installed = true, version = "1.0.2", stateDir = temp, setting = { null }, env = { null }, credentialStore = { null }),
        )
        assertEquals("superiorsoft/agenthq", fromFile.config.repo)
        assertNull(fromFile.config.token, "a token in the settings file is never read")
        val fromEnv = assertIs<UpdateSettings.Enabled>(
            UpdateSettings.resolve(true, "1.0.2", temp, setting = { null }, env = { if (it == UpdateSettings.TOKEN_ENV) "env-token" else null }, credentialStore = { "store" }),
        )
        assertEquals("env-token", fromEnv.config.token)
        assertFalse("env-token" in fromEnv.config.toString(), "the config must never print its token")
    }

    @Test
    fun `the OS credential store's GitHub login is only ever sent to GitHub, never to an API override`() {
        fun resolve(api: String?) = (UpdateSettings.resolve(
            true, "1.0.2", temp,
            setting = { when (it) { UpdateSettings.REPO -> "o/r"; UpdateSettings.API -> api; else -> null } },
            env = { null }, credentialStore = { "owner-login-token" },
        ) as UpdateSettings.Enabled).config
        assertEquals("owner-login-token", resolve(null).token)
        assertNull(resolve("http://127.0.0.1:9").token, "a stored GitHub credential must not go to a non-GitHub host")
    }

    @Test
    fun `semver orders tags`() {
        assertTrue(SemVer.parse("v1.0.10")!! > SemVer.parse("1.0.9")!!)
        assertTrue(SemVer.parse("1.0.0")!! > SemVer.parse("1.0.0-rc.1")!!)
        assertTrue(SemVer.parse("1.0.0-rc.2")!! > SemVer.parse("1.0.0-rc.1")!!)
        assertEquals(SemVer.parse("1.2.3+build.5"), SemVer(1, 2, 3))
        assertNull(SemVer.parse("latest"))
    }

    @Test
    fun `the handoff script waits for the app, upgrades into the same dir and restarts it`() {
        val script = MsiHandoff.script(4242, File("C:/t/AgentHQ-1.0.4.msi"), File("C:/Apps/AgentHQ"), File("C:/Apps/AgentHQ/AgentHQ.exe"), File("C:/t/msiexec.log"))
        assertTrue("PID eq 4242" in script)
        assertTrue(Regex("""msiexec /i ".*AgentHQ-1\.0\.4\.msi" INSTALLDIR=".*Apps.AgentHQ" /passive""").containsMatchIn(script), script)
        assertTrue(script.trimEnd().lines().dropLast(1).last().endsWith("AgentHQ.exe\""), script)
        assertEquals("exit", script.trimEnd().lines().last())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** A recorded request to the fake server. */
data class Seen(val path: String, val authorization: String?, val accept: String?)

/** UPDATE-1 test fixture: a local stand-in for api.github.com on a free loopback port. */
class FakeGitHub {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base: String get() = "http://127.0.0.1:${server.address.port}"
    val requests = CopyOnWriteArrayList<Seen>()
    private val routes = mutableMapOf<String, (HttpExchange) -> Unit>()

    init {
        server.createContext("/") { ex ->
            requests += Seen(ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), ex.requestHeaders.getFirst("Accept"))
            val route = routes[ex.requestURI.path]
            if (route == null) respond(ex, 404, """{"message":"Not Found"}""".toByteArray()) else route(ex)
        }
        server.start()
    }

    fun stop() = server.stop(0)

    fun raw(path: String, bytes: ByteArray) = apply { routes[path] = { respond(it, 200, bytes) } }

    fun rateLimited() = apply {
        routes["/repos/superiorsoft/agenthq/releases/latest"] = { ex ->
            ex.responseHeaders.add("x-ratelimit-remaining", "0")
            ex.responseHeaders.add("x-ratelimit-reset", "1900000000")
            respond(ex, 403, """{"message":"API rate limit exceeded"}""".toByteArray())
        }
    }

    fun release(
        tag: String,
        notes: String = "notes",
        msi: Pair<String, ByteArray>,
        sha256: String? = null,
        requireToken: String? = null,
        reportedSize: Long = msi.second.size.toLong(),
        redirectAssetsTo: String? = null,
    ) = apply {
        fun gated(handler: (HttpExchange) -> Unit): (HttpExchange) -> Unit = { ex ->
            if (requireToken != null && ex.requestHeaders.getFirst("Authorization") != "Bearer $requireToken") {
                respond(ex, 404, """{"message":"Not Found"}""".toByteArray())
            } else {
                handler(ex)
            }
        }
        val assets = buildList {
            add(Triple(1, msi.first, reportedSize))
            if (sha256 != null) add(Triple(2, "${msi.first}.sha256", (sha256 + "  " + msi.first + "\n").length.toLong()))
        }
        val json = assets.joinToString(",", "[", "]") { (id, name, size) ->
            """{"name":"$name","size":$size,"url":"$base/repos/superiorsoft/agenthq/releases/assets/$id","browser_download_url":"$base/dl/$name"}"""
        }
        routes["/repos/superiorsoft/agenthq/releases/latest"] = gated { ex ->
            respond(ex, 200, """{"tag_name":"$tag","name":"$tag","body":"$notes","html_url":"$base/r","draft":false,"prerelease":false,"assets":$json}""".toByteArray())
        }
        routes["/repos/superiorsoft/agenthq/releases/assets/1"] = gated { ex ->
            if (redirectAssetsTo != null) {
                ex.responseHeaders.add("Location", "$redirectAssetsTo/${msi.first}")
                ex.sendResponseHeaders(302, -1)
                ex.close()
            } else {
                respond(ex, 200, msi.second)
            }
        }
        if (sha256 != null) routes["/repos/superiorsoft/agenthq/releases/assets/2"] = gated { respond(it, 200, (sha256 + "  " + msi.first + "\n").toByteArray()) }
    }

    private fun respond(ex: HttpExchange, code: Int, bytes: ByteArray) {
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
