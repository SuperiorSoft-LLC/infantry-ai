// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.sites.daemon

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.HttpCall
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import com.superiorsoftllc.infantry.daemon.api.Routes
import com.superiorsoftllc.infantry.daemon.api.ServiceEndpoint
import com.superiorsoftllc.infantry.daemon.api.ServicesApi
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.Tools
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityHandler
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityInfo
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityResult
import com.superiorsoftllc.infantry.daemon.api.capabilities.Capabilities
import com.superiorsoftllc.infantry.daemon.api.http.Http
import com.superiorsoftllc.infantry.daemon.api.http.HttpException
import com.superiorsoftllc.infantry.daemon.api.http.HttpResult
import com.superiorsoftllc.infantry.daemon.api.http.HttpSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.URI
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

private const val TOKEN = "bearer-secret-token"
private const val ID1 = "11111111-1111-4111-8111-111111111111"

private class JdkHttp : Http {
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

/** A local stand-in for the hosting API (design 3.2) on port 0. */
private class StubApi {
    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base get() = "http://127.0.0.1:${server.address.port}"
    val calls = mutableListOf<String>()
    val auth = mutableListOf<String?>()
    val idemKeys = mutableListOf<String?>()
    val bodies = mutableListOf<String>()
    var forced: Int? = null
    var forcedBody = """{"error":"forced"}"""
    var retryAfter: String? = null
    var reclaimableUntil: Long? = null
    var servers = mutableListOf(mapOf("id" to ID1, "name" to "web", "status" to "active", "ip" to "203.0.113.5", "region" to "nyc3", "size" to "s-1vcpu-2gb", "createdAt" to "2026-11-14T10:00:00Z"))

    init {
        server.createContext("/hosting/v1/servers") { ex ->
            calls += "${ex.requestMethod} ${ex.requestURI.path}"
            auth += ex.requestHeaders.getFirst("Authorization")
            idemKeys += ex.requestHeaders.getFirst("Idempotency-Key")
            val body = ex.requestBody.readBytes().decodeToString().also { bodies += it }
            val f = forced
            val path = ex.requestURI.path
            when {
                f != null -> { retryAfter?.let { ex.responseHeaders.add("Retry-After", it) }; reply(ex, f, forcedBody) }
                ex.requestMethod == "GET" && path == "/hosting/v1/servers" -> reply(ex, 200, Json.encodeToString(JsonArray.serializer(), JsonArray(servers.map { s -> JsonObject(s.mapValues { JsonPrimitive(it.value) }) })))
                ex.requestMethod == "POST" && path == "/hosting/v1/servers" -> {
                    val name = Json.parseToJsonElement(body).jsonObject["name"]!!.jsonPrimitive.content
                    reply(ex, 201, """{"id":"22222222-2222-4222-8222-222222222222","name":"$name","status":"new","ip":"","region":"nyc3","size":"s-1vcpu-2gb","createdAt":"2026-11-14T11:00:00Z"}""")
                }
                ex.requestMethod == "POST" && path.contains("/actions/") -> reply(ex, 200, "{}")
                ex.requestMethod == "DELETE" -> reply(ex, 204, "")
                else -> reply(ex, 404, "{}")
            }
        }
        server.createContext("/core/v1/account") { ex ->
            calls += "${ex.requestMethod} ${ex.requestURI.path}"
            reply(ex, 200, """{"reclaimableUntil":${reclaimableUntil ?: "null"}}""")
        }
        server.start()
    }

    private fun reply(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
        if (status != 204) ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    fun stop() = server.stop(0)
}

class SitesTest {
    private val stub = StubApi()

    @AfterTest fun stop() = stub.stop()

    private fun service(url: String = stub.base) = SitesService(JdkHttp(), TokenSource { TOKEN }, { url })

    @Test
    fun `list create start stop and destroy round-trip against the stub and carry the bearer and an Idempotency-Key`() {
        val s = service()
        val list = s.list()
        assertEquals(ID1, list.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("203.0.113.5", list.single().jsonObject["ip"]!!.jsonPrimitive.content)

        val made = s.create("site-1", "ssh-ed25519 AAAAC3Nza key@host")
        assertEquals("new", made["status"]!!.jsonPrimitive.content)
        val key = stub.idemKeys.last()
        assertNotNull(key)
        assertTrue(SitesService.isUuid(key), "the Idempotency-Key is a UUID")
        assertTrue("sshPublicKey" in stub.bodies.last())

        s.power(ID1, "start"); s.power(ID1, "stop"); s.destroy(ID1)
        assertEquals(listOf("GET /hosting/v1/servers", "POST /hosting/v1/servers", "POST /hosting/v1/servers/$ID1/actions/start", "POST /hosting/v1/servers/$ID1/actions/stop", "DELETE /hosting/v1/servers/$ID1"), stub.calls)
        assertTrue(stub.auth.all { it == "Bearer $TOKEN" })
    }

    @Test
    fun `409 is the plan limit, 403 not entitled, 401 signed out`() {
        val s = service()
        stub.forced = 409
        assertEquals(SitesException.LIMIT, assertFailsWith<SitesException> { s.create("a", null) }.kind)
        stub.forced = 403
        assertEquals(SitesException.NOT_ENTITLED, assertFailsWith<SitesException> { s.list() }.kind)
        stub.forced = 401
        assertEquals(SitesException.SIGNED_OUT, assertFailsWith<SitesException> { s.list() }.kind)
    }

    private fun failure(status: Int, body: String, op: (SitesService) -> Unit = { it.list() }): SitesException {
        stub.forced = status
        stub.forcedBody = body
        return assertFailsWith<SitesException> { op(service()) }
    }

    @Test
    fun `403 splits on the error field, email_unverified vs forbidden`() {
        val e = failure(403, """{"error":"email_unverified","message":"x"}""") { it.create("a", null) }
        assertEquals(SitesException.EMAIL_UNVERIFIED, e.kind)
        assertEquals("Verify your email address to create a server.", e.message)
        assertEquals(SitesException.NOT_ENTITLED, failure(403, """{"error":"forbidden","message":"email_unverified"}""").kind)
        assertEquals(SitesException.NOT_ENTITLED, failure(403, "").kind)
    }

    @Test
    fun `502 provider_error and 500 are unavailable with their own text`() {
        val e = failure(502, """{"error":"provider_error"}""")
        assertEquals(SitesException.UNAVAILABLE, e.kind)
        assertEquals("The hosting provider had a problem. Try again in a minute.", e.message)
        val g = failure(500, """{"error":"internal"}""")
        assertEquals(SitesException.UNAVAILABLE, g.kind)
        assertTrue("HTTP 500" in g.message!!)
    }

    @Test
    fun `429 says slow down and includes Retry-After seconds when present`() {
        stub.retryAfter = "17"
        assertTrue("try again in 17 s" in failure(429, """{"error":"rate_limited"}""").message!!)
        stub.retryAfter = null
        val e = failure(429, """{"error":"rate_limited"}""")
        assertTrue("slow down" in e.message!! && " s" !in e.message!!)
        assertEquals(2, stub.calls.count { it == "GET /hosting/v1/servers" }, "one call per attempt, no retry loop")
    }

    @Test
    fun `reclaimableUntil is read from the account answer`() {
        assertNull(service().reclaimableUntil())
        stub.reclaimableUntil = 1_795_000_000_000L
        assertEquals(1_795_000_000_000L, service().reclaimableUntil())
        assertTrue(stub.auth.all { it == "Bearer $TOKEN" })
    }

    @Test
    fun `a 403 from the servers list with a reclaim window shows the reclaim state, not only not entitled`() {
        stub.reclaimableUntil = 1_795_000_000_000L
        stub.forced = 403
        stub.forcedBody = """{"error":"forbidden"}"""
        val w = wire(mapOf("sites.apiUrl" to stub.base))
        val st = Json.parseToJsonElement(w.routes.getValue("GET state")(HttpCall("GET", "state")).body).jsonObject
        assertEquals("not_entitled", st["state"]!!.jsonPrimitive.content)
        assertEquals("1795000000000", st["reclaimableUntil"]!!.jsonPrimitive.content)
        // plan not entitled per the account hub too: the same window is read
        val free = wire(mapOf("sites.apiUrl" to stub.base), entitled = false)
        val fs = Json.parseToJsonElement(free.routes.getValue("GET state")(HttpCall("GET", "state")).body).jsonObject
        assertEquals("1795000000000", fs["reclaimableUntil"]!!.jsonPrimitive.content)
        // no window: the field is absent
        stub.reclaimableUntil = null
        val none = Json.parseToJsonElement(w.routes.getValue("GET state")(HttpCall("GET", "state")).body).jsonObject
        assertNull(none["reclaimableUntil"])
    }

    @Test
    fun `a malformed id, name or key is refused before any call`() {
        val s = service()
        for (bad in listOf("", "1", "../etc", "11111111-1111-4111-8111-11111111111", "11111111-1111-4111-8111-111111111111/x", "not-a-uuid-at-all-really-not-here-1234")) {
            assertFailsWith<IllegalArgumentException> { s.destroy(bad) }
            assertFailsWith<IllegalArgumentException> { s.power(bad, "start") }
        }
        assertFailsWith<IllegalArgumentException> { s.power(ID1, "power_cycle") }
        assertFailsWith<IllegalArgumentException> { s.create("Bad_Name!", null) }
        assertFailsWith<IllegalArgumentException> { s.create("ok", "not a key") }
        assertFailsWith<IllegalArgumentException> { s.create("ok", null, "not-a-uuid") }
        assertEquals(emptyList(), stub.calls, "the stub saw nothing")
    }

    @Test
    fun `an unset or clear-text address calls nothing`() {
        assertEquals(SitesException.NOT_CONFIGURED, assertFailsWith<SitesException> { service("").list() }.kind)
        assertEquals(SitesException.NOT_CONFIGURED, assertFailsWith<SitesException> { service("http://example.org").list() }.kind)
        assertEquals(emptyList(), stub.calls)
    }

    // ---- the plugin wired to a stand-in DaemonApi -------------------------------------------------------------------

    private class Wired(val tools: MutableMap<String, DaemonTool>, val routes: MutableMap<String, (HttpCall) -> HttpReply>, val endpoints: MutableMap<String, ServiceEndpoint>, val capCalls: MutableList<String>)

    private fun wire(config: Map<String, String>, signedIn: Boolean = true, entitled: Boolean = true): Wired {
        val tools = HashMap<String, DaemonTool>()
        val routes = HashMap<String, (HttpCall) -> HttpReply>()
        val endpoints = HashMap<String, ServiceEndpoint>()
        val capCalls = mutableListOf<String>()
        val caps = object : Capabilities {
            override fun handle(id: String, handler: CapabilityHandler): AutoCloseable = AutoCloseable {}
            override fun call(id: String, args: JsonObject, target: String?): CapabilityResult {
                capCalls += id
                return when (id) {
                    "account.token" -> if (signedIn) CapabilityResult.Ok(buildJsonObject { put("accessToken", TOKEN); put("expiresAt", 1) }, "daemon:Infantry_Account") else CapabilityResult.Failed("not signed in to Infantry AI Hosting")
                    "account.status" -> CapabilityResult.Ok(buildJsonObject {
                        put("providers", buildJsonArray {
                            if (signedIn || true) add(buildJsonObject {
                                put("id", "infantry"); put("signedIn", signedIn); put("signUpUrl", "https://plans.example/hosting")
                                put("permissions", buildJsonArray { if (entitled) add(kotlinx.serialization.json.JsonPrimitive("hosting.servers.read")) })
                                put("limits", buildJsonObject { put("servers.max", 2) })
                                put("services", buildJsonArray { add(buildJsonObject { put("id", "hosting"); put("entitled", entitled) }) })
                            })
                        })
                    }, "daemon:Infantry_Account")
                    "account.entitlement" -> CapabilityResult.Ok(buildJsonObject { put("entitled", entitled); put("limit", 2) }, "daemon:Infantry_Account")
                    else -> CapabilityResult.Failed("unknown")
                }
            }
            override fun list(): List<CapabilityInfo> = emptyList()
        }
        val services = object : ServicesApi {
            override fun <T : Any> provide(type: kotlin.reflect.KClass<T>, impl: T) = AutoCloseable {}
            override fun <T : Any> find(type: kotlin.reflect.KClass<T>): T? = null
            override fun publishEndpoint(id: String, endpoint: ServiceEndpoint): AutoCloseable { endpoints[id] = endpoint; return AutoCloseable { endpoints.remove(id) } }
        }
        val routing = object : Routes {
            override fun handle(method: String, path: String, handler: (HttpCall) -> HttpReply) { routes["$method $path"] = handler }
        }
        val toolsApi = object : Tools { override fun register(tool: DaemonTool) { tools[tool.name] = tool } }
        val handler = InvocationHandler { _, m, a ->
            when (m.name) {
                "getCapabilities" -> caps
                "getServices" -> services
                "getRoutes" -> routing
                "getTools" -> toolsApi
                "getHttp" -> JdkHttp()
                "schedule" -> AutoCloseable {}
                "config" -> config[a[0] as String]
                "log" -> null
                "getModuleId" -> "Infantry_Sites"
                else -> throw UnsupportedOperationException(m.name)
            }
        }
        val api = Proxy.newProxyInstance(DaemonApi::class.java.classLoader, arrayOf(DaemonApi::class.java), handler) as DaemonApi
        SitesDaemonPlugin().start(api)
        return Wired(tools, routes, endpoints, capCalls)
    }

    private fun callTool(w: Wired, name: String, vararg args: Pair<String, String>) = w.tools.getValue(name).call(object : ToolCall {
        override val arguments = JsonObject(args.associate { it.first to JsonPrimitive(it.second) })
        override val caller = Caller(owner = false, agent = true)
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long) = error("n/a")
    })

    @Test
    fun `the provider is registered only when an address is set, and describes the hosting service`() {
        assertTrue(wire(emptyMap()).endpoints.isEmpty(), "unset address: no provider, nothing to call")
        val w = wire(mapOf("sites.apiUrl" to stub.base, "sites.siteUrl" to "https://plans.example"))
        val d = Json.parseToJsonElement(w.endpoints.getValue("account.provider/infantry").call("describe", "{}")).jsonObject
        assertEquals("infantry", d["id"]!!.jsonPrimitive.content)
        assertEquals("Infantry AI Hosting", d["name"]!!.jsonPrimitive.content)
        assertEquals("Infantry_Sites", d["consumers"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("hosting", d["services"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("${stub.base}/auth/oauth/token", d["signIn"]!!.jsonObject["tokenUrl"]!!.jsonPrimitive.content)
    }

    @Test
    fun `agent tools work the API themselves and neither the tools nor the routes ever expose the token`() {
        val w = wire(mapOf("sites.apiUrl" to stub.base))
        assertEquals(setOf("infantry_sites_list", "infantry_sites_create", "infantry_sites_start", "infantry_sites_stop", "infantry_sites_destroy"), w.tools.keys)
        val outputs = mutableListOf<String>()
        outputs += callTool(w, "infantry_sites_list").text
        outputs += callTool(w, "infantry_sites_create", "name" to "from-agent").text
        outputs += callTool(w, "infantry_sites_start", "id" to ID1).text
        outputs += callTool(w, "infantry_sites_stop", "id" to ID1).text
        outputs += callTool(w, "infantry_sites_destroy", "id" to ID1).text
        assertTrue(callTool(w, "infantry_sites_destroy", "id" to "nope").isError, "a malformed id is an error result")
        outputs += w.routes.getValue("GET state")(HttpCall("GET", "state")).body
        assertTrue(outputs.none { TOKEN in it }, "no output holds the token")
        assertTrue(stub.auth.isNotEmpty() && stub.auth.all { it == "Bearer $TOKEN" }, "the plugin itself sent the bearer")
        assertEquals(4, stub.calls.count { it != "GET /hosting/v1/servers" }, "create, start, stop and destroy reached the stub")
    }

    @Test
    fun `state is signed out, not entitled or ready, and a limit answer on create reaches the UI as limit`() {
        val out = wire(mapOf("sites.apiUrl" to stub.base), signedIn = false)
        assertEquals("signed_out", Json.parseToJsonElement(out.routes.getValue("GET state")(HttpCall("GET", "state")).body).jsonObject["state"]!!.jsonPrimitive.content)
        assertTrue(callTool(out, "infantry_sites_list").isError)
        assertEquals(emptyList(), stub.calls, "signed out: the API was not called")

        val free = wire(mapOf("sites.apiUrl" to stub.base), entitled = false)
        val st = Json.parseToJsonElement(free.routes.getValue("GET state")(HttpCall("GET", "state")).body).jsonObject
        assertEquals("not_entitled", st["state"]!!.jsonPrimitive.content)
        assertEquals("https://plans.example/hosting", st["signUpUrl"]!!.jsonPrimitive.content)

        val ready = wire(mapOf("sites.apiUrl" to stub.base))
        val rs = Json.parseToJsonElement(ready.routes.getValue("GET state")(HttpCall("GET", "state")).body).jsonObject
        assertEquals("ready", rs["state"]!!.jsonPrimitive.content)
        assertEquals(1, rs["servers"]!!.jsonArray.size)
        assertEquals("2", rs["limit"]!!.jsonPrimitive.content)

        stub.forced = 409
        val r = Json.parseToJsonElement(ready.routes.getValue("POST create")(HttpCall("POST", "create", body = """{"name":"x"}""")).body).jsonObject
        assertEquals("limit", r["state"]!!.jsonPrimitive.content)
        assertEquals(400, ready.routes.getValue("POST destroy")(HttpCall("POST", "destroy", body = """{"id":"bad"}""")).status)

        val unset = wire(emptyMap())
        assertEquals("not_configured", Json.parseToJsonElement(unset.routes.getValue("GET state")(HttpCall("GET", "state")).body).jsonObject["state"]!!.jsonPrimitive.content)
        assertNull(unset.endpoints["account.provider/infantry"])
    }

    @Test
    fun `the UI strings name no cloud vendor or company, only copyright headers do`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "plugins/official/Infantry_Sites") }.first { it.isDirectory }
        val banned = Regex(listOf("digital" + "ocean", "drop" + "let", "str" + "ipe", "superior" + "soft").joinToString("|"), RegexOption.IGNORE_CASE)
        val hits = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "json", "kts") && "build" !in it.relativeTo(root).path.split(File.separatorChar) && !it.name.endsWith("Test.kt") }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) -> !line.startsWith("// Copyright (c) Superior" + "Soft LLC") }
                    .filter { (_, line) -> banned.containsMatchIn(line.replace("com.superiorsoftllc", "").replace("superiorSoftLibrary", "")) }
                    .map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
            }.toList()
        assertEquals(emptyList(), hits)
    }
}
