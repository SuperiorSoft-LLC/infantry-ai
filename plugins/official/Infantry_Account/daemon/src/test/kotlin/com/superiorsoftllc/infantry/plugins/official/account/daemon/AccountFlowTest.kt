// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.account.daemon

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.HttpCall
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Routes
import com.superiorsoftllc.infantry.daemon.api.ServiceEndpoint
import com.superiorsoftllc.infantry.daemon.api.ServicesApi
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityCall
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityHandler
import com.superiorsoftllc.infantry.daemon.api.capabilities.Capabilities
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityInfo
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityResult
import com.superiorsoftllc.infantry.daemon.api.http.Http
import com.superiorsoftllc.infantry.daemon.api.http.HttpException
import com.superiorsoftllc.infantry.daemon.api.http.HttpResult
import com.superiorsoftllc.infantry.daemon.api.http.HttpSpec
import com.superiorsoftllc.infantry.daemon.api.pluginsecrets.PluginSecrets
import com.superiorsoftllc.infantry.daemon.api.pluginsecrets.SecretValue
import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.Notifications
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A keystore entry in memory: what `DaemonApi.pluginSecrets` gives the plugin. */
class MemSecrets : PluginSecrets {
    val fields = HashMap<String, String>()
    override fun get(field: String) = fields[field]?.let { SecretValue(it) }
    override fun set(field: String, value: SecretValue) { fields[field] = value.reveal() }
    override fun clear(field: String) = fields.remove(field) != null
}

private class JdkHttp : Http {
    private val client = HttpClient.newHttpClient()
    override fun request(spec: HttpSpec): HttpResult {
        val b = HttpRequest.newBuilder(URI(spec.url))
        (spec.headers + spec.secretHeaders).forEach { (k, v) -> b.header(k, v) }
        b.method(spec.method, spec.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        val r = try { client.send(b.build(), HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { throw HttpException("no answer") }
        return HttpResult(r.statusCode(), emptyMap(), r.body())
    }
    override fun json(spec: HttpSpec): JsonElement = Json.parseToJsonElement(request(spec).body)
}

/** A device-code provider on a local port (port 0): approves the code on the [approveAfter]th token poll. */
class StubProvider(private val approveAfter: Int = 2) {
    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base get() = "http://127.0.0.1:${server.address.port}"
    var polls = 0
    var refreshes = 0
    var revoked = false
    var refreshError: String? = null
    var denyDevice = false
    val seenAuth = mutableListOf<String?>()

    init {
        server.createContext("/device") { ex -> reply(ex, 200, """{"device_code":"dev-secret","user_code":"WDJB-MJHT","verification_uri":"$base/activate","expires_in":600,"interval":5}""") }
        server.createContext("/token") { ex ->
            val form = form(ex)
            when (form["grant_type"]) {
                "refresh_token" -> {
                    refreshes++
                    val err = refreshError
                    if (err != null) reply(ex, 400, """{"error":"$err"}""")
                    else reply(ex, 200, """{"access_token":"access-$refreshes","refresh_token":"refresh-rot-$refreshes","expires_in":3600}""")
                }
                else -> {
                    polls++
                    when {
                        denyDevice -> reply(ex, 400, """{"error":"access_denied"}""")
                        polls < approveAfter -> reply(ex, 400, """{"error":"authorization_pending"}""")
                        else -> reply(ex, 200, """{"access_token":"access-first","refresh_token":"refresh-first","expires_in":3600}""")
                    }
                }
            }
        }
        server.createContext("/revoke") { ex -> revoked = true; reply(ex, 200, "{}") }
        server.createContext("/account") { ex ->
            seenAuth += ex.requestHeaders.getFirst("Authorization")
            reply(ex, 200, """{"user":{"id":"u_1","email":"alice@example.org","name":"Alice"},"plan":{"id":"basic","name":"Basic","status":"active"},"entitlements":[{"id":"widgets","limit":2,"used":1,"attrs":{"size":"s"}}]}""")
        }
        server.start()
    }

    fun stop() = server.stop(0)

    private fun form(ex: HttpExchange): Map<String, String> = ex.requestBody.readBytes().decodeToString().split("&").filter { it.isNotEmpty() }
        .associate { it.substringBefore("=").let(::dec) to it.substringAfter("=", "").let(::dec) }
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    private fun reply(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    fun describe(consumers: List<String> = listOf("Consumer_Mod")) = """
        {"id":"stub","name":"Stub Cloud","icon":"cloud",
         "signIn":{"method":"device_code","deviceAuthorizationUrl":"$base/device","tokenUrl":"$base/token","revokeUrl":"$base/revoke","clientId":"stub-app","scopes":["account"]},
         "accountUrl":"$base/account","billingUrl":"$base/billing","signUpUrl":"$base/signup",
         "consumers":${consumers.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},
         "services":[{"id":"widgets","name":"Widgets","entitlement":"widgets"}]}
    """.trimIndent()
}

class AccountFlowTest {
    private val stub = StubProvider()
    private val secrets = MemSecrets()
    private var clock = 1_000_000L
    private val notices = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private var uuids = 0

    private fun service(describe: String = stub.describe()) = AccountService(
        http = JdkHttp(), secrets = secrets,
        endpoints = { mapOf("stub" to ServiceEndpoint { op, _ -> check(op == "describe"); describe }) },
        notify = { title, _, key -> notices += "$key|$title" }, log = { logs += it }, refreshMinutes = { 15 },
        now = { clock }, newId = { "00000000-0000-4000-8000-${(++uuids).toString().padStart(12, '0')}" },
    )

    @AfterTest fun stop() = stub.stop()

    @Test
    fun `a device-code sign-in against a stub provider ends signed in with the refresh token in the keystore only`() {
        val s = service()
        val flow = s.signIn("stub")
        assertEquals("WDJB-MJHT", flow.userCode)
        assertTrue(flow.verificationUri.endsWith("/activate"))
        assertEquals("00000000-0000-4000-8000-000000000001", flow.id, "the flow id is a UUID from the id source")
        assertFalse(s.signedIn(s.provider("stub")))

        s.tick() // before the first interval: nothing is asked
        assertEquals(0, stub.polls)
        clock += 5_000; s.tick() // pending
        assertEquals(1, stub.polls)
        assertEquals("pending", s.flow("stub")!!.state)
        clock += 5_000; s.tick() // approved
        assertEquals(2, stub.polls)
        assertNull(s.flow("stub"), "the flow is over")

        assertEquals("refresh-first", secrets.get("stub_refresh")!!.reveal(), "the refresh token is in the keystore")
        assertEquals(setOf("stub_refresh"), secrets.fields.keys, "and nothing else is stored")
        val status = s.status().toString()
        assertTrue(status.contains("\"signedIn\":true"))
        assertTrue(status.contains("a***@example.org"), "the e-mail is shown masked")
        assertTrue("\"entitled\":true" in status)
        listOf("refresh-first", "access-first", "dev-secret").forEach { assertFalse(it in status, "$it must not appear in an answer") }
        assertEquals("Bearer access-first", stub.seenAuth.single(), "the account lookup carried the access token")
        assertTrue(logs.none { "refresh-first" in it || "access-first" in it })

        val ent = s.entitlement("stub", "widgets")
        assertEquals(true, ent["entitled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("2", ent["limit"]!!.jsonPrimitive.content)
        assertEquals(false, s.entitlement("stub", "nope")["entitled"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `an expired access token is refreshed and a rotated refresh token replaces the old one`() {
        val s = service()
        s.signIn("stub"); clock += 5_000; s.tick(); clock += 5_000; s.tick()
        assertEquals("access-first", s.accessToken("stub").first.reveal())
        clock += 3_600_000
        assertEquals("access-1", s.accessToken("stub").first.reveal())
        assertEquals("refresh-rot-1", secrets.get("stub_refresh")!!.reveal())
    }

    @Test
    fun `a refresh that fails invalid_grant signs out and posts one notice`() {
        val s = service()
        s.signIn("stub"); clock += 5_000; s.tick(); clock += 5_000; s.tick()
        clock += 3_600_000
        stub.refreshError = "invalid_grant"
        val e = assertFailsWith<IllegalStateException> { s.accessToken("stub") }
        assertFalse("refresh" in e.message.orEmpty().lowercase().replace("refresh the", ""), "no token in the message")
        assertNull(secrets.get("stub_refresh"))
        assertEquals(listOf("account.signedout.stub|Signed out of Stub Cloud"), notices)
    }

    @Test
    fun `a denied sign-in ends in the denied state and sign out revokes and clears`() {
        stub.denyDevice = true
        val s = service()
        s.signIn("stub"); clock += 5_000; s.tick()
        assertEquals("denied", s.flow("stub")!!.state)
        assertNull(secrets.get("stub_refresh"))

        stub.denyDevice = false
        s.signIn("stub"); clock += 5_000; s.tick() // poll 2 approves
        assertNotNull(secrets.get("stub_refresh"))
        assertTrue(s.signOut("stub"))
        assertTrue(stub.revoked)
        assertTrue(secrets.fields.isEmpty())
        assertFalse(s.signOut("stub"))
    }

    @Test
    fun `a descriptor is refused for a bad id, plain http off this machine, or an unknown method`() {
        fun bad(json: String) = assertFailsWith<IllegalArgumentException> { ProviderDescriptor.parseText(json) }
        val ok = stub.describe()
        bad(ok.replace("\"id\":\"stub\"", "\"id\":\"Bad-Id\""))
        bad(ok.replace(stub.base + "/token", "http://example.org/token"))
        bad(ok.replace("device_code", "magic"))
        assertEquals("stub_refresh", ProviderDescriptor.parseText(ok).refreshField)
        // A broken provider is skipped, not fatal.
        assertTrue(service(describe = "{}").providers().isEmpty())
        assertTrue(logs.single().startsWith("provider 'stub' skipped"))
    }

    // ---- the plugin's capabilities, through a stand-in DaemonApi ---------------------------------------------------

    private class Wired(val handlers: MutableMap<String, CapabilityHandler>, val routes: MutableMap<String, (HttpCall) -> HttpReply>, val notices: MutableList<Notification>)

    private fun wire(describe: String): Wired {
        val handlers = HashMap<String, CapabilityHandler>()
        val routes = HashMap<String, (HttpCall) -> HttpReply>()
        val notes = mutableListOf<Notification>()
        val caps = object : Capabilities {
            override fun handle(id: String, handler: CapabilityHandler): AutoCloseable { handlers[id] = handler; return AutoCloseable { handlers.remove(id) } }
            override fun call(id: String, args: JsonObject, target: String?): CapabilityResult = CapabilityResult.Failed("n/a")
            override fun list(): List<CapabilityInfo> = emptyList()
        }
        val services = object : ServicesApi {
            override fun <T : Any> provide(type: kotlin.reflect.KClass<T>, impl: T) = AutoCloseable {}
            override fun <T : Any> find(type: kotlin.reflect.KClass<T>): T? = null
            override fun endpoints(prefix: String) = mapOf("account.provider/stub" to ServiceEndpoint { _, _ -> describe })
        }
        val routing = object : Routes {
            override fun handle(method: String, path: String, handler: (HttpCall) -> HttpReply) { routes["$method $path"] = handler }
        }
        val handler = InvocationHandler { _, m, a ->
            when (m.name) {
                "getCapabilities" -> caps
                "getServices" -> services
                "getRoutes" -> routing
                "getHttp" -> JdkHttp()
                "getPluginSecrets" -> secrets
                "getNotifications" -> object : Notifications { override fun notify(title: String, body: String) {}; override fun notify(notification: Notification): String { notes += notification; return "" } }
                "schedule" -> AutoCloseable {}
                "config" -> null
                "log" -> null
                "getModuleId" -> "Infantry_Account"
                else -> throw UnsupportedOperationException(m.name)
            }
        }
        val api = Proxy.newProxyInstance(DaemonApi::class.java.classLoader, arrayOf(DaemonApi::class.java), handler) as DaemonApi
        AccountDaemonPlugin().start(api)
        return Wired(handlers, routes, notes)
    }

    private fun call(w: Wired, id: String, source: String, vararg args: Pair<String, String>) =
        runCatching { w.handlers.getValue(id).call(CapabilityCall(id, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }), Caller(owner = true), source)) }

    @Test
    fun `account token is refused to a session, the CLI and a script and allowed to a listed consumer module`() {
        val w = wire(stub.describe())
        assertEquals(setOf("account.status", "account.entitlement", "account.token", "account.signIn", "account.signOut"), w.handlers.keys)
        // Sign in as the owner's CLI (a session may not).
        assertTrue(call(w, "account.signIn", "session:s1", "provider" to "stub").isFailure)
        assertTrue(call(w, "account.signIn", "script:x", "provider" to "stub").isFailure)
        val started = call(w, "account.signIn", "cli", "provider" to "stub").getOrThrow().jsonObject
        assertEquals("WDJB-MJHT", started["userCode"]!!.jsonPrimitive.content)
        assertFalse("dev-secret" in started.toString())
        // Finish the sign-in on the service side by polling the stub directly through a second service sharing the keystore.
        val s = service(); s.signIn("stub"); clock += 5_000; s.tick(); clock += 5_000; s.tick()
        assertNotNull(secrets.get("stub_refresh"))

        for (source in listOf("session:s1", "cli", "script:x", "plugin:Other_Mod")) {
            val r = call(w, "account.token", source, "provider" to "stub")
            assertTrue(r.isFailure, "$source must be refused")
            assertFalse("access" in r.exceptionOrNull()!!.message.orEmpty().substringAfter("consumers;"), "the refusal holds no token")
        }
        val ok = call(w, "account.token", "plugin:Consumer_Mod", "provider" to "stub").getOrThrow().jsonObject
        assertTrue(ok["accessToken"]!!.jsonPrimitive.content.startsWith("access-"))
        assertNotNull(ok["expiresAt"])

        // status and entitlement are open to anyone and hold no token.
        val status = call(w, "account.status", "session:s1").getOrThrow().toString()
        assertTrue("\"signedIn\":true" in status)
        assertFalse("access-" in status || "refresh-" in status)
        assertEquals("true", call(w, "account.entitlement", "session:s1", "provider" to "stub", "entitlement" to "widgets").getOrThrow().jsonObject["entitled"]!!.jsonPrimitive.content)

        // The UI's routes answer display data only.
        val state = w.routes.getValue("GET state")(HttpCall("GET", "state"))
        assertEquals(200, state.status)
        assertTrue("refresh-" !in state.body && "access-" !in state.body && "dev-secret" !in state.body)
        assertEquals(1, Json.parseToJsonElement(state.body).jsonObject["providers"]!!.jsonArray.size)
        assertEquals(400, w.routes.getValue("POST signin")(HttpCall("POST", "signin", body = "{}")).status)
    }

    @Test
    fun `the plugin's sources name no provider, company or product`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "plugins/official/Infantry_Account") }.first { it.isDirectory }
        val banned = Regex(listOf("superior" + "soft", "host" + "ing", "str" + "ipe", "digital" + "ocean", "drop" + "let").joinToString("|"), RegexOption.IGNORE_CASE)
        val hits = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "json", "kts", "md") && "build" !in it.relativeTo(root).path.split(File.separatorChar) }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) -> !line.startsWith("// Copyright (c) Superior" + "Soft LLC") }
                    .filter { (_, line) -> banned.containsMatchIn(line.replace("com.superiorsoftllc", "").replace("superiorSoftLibrary", "")) }
                    .map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
            }.toList()
        assertEquals(emptyList(), hits)
    }
}
