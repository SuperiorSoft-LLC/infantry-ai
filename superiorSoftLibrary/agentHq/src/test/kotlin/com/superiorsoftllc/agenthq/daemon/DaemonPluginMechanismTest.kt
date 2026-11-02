package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.daemon.plugins.DaemonPluginHost
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): the mechanism end to end, on a real daemon loading the reference plugin
 * `plugins/examples/Acme_DaemonHello` from its BUILT jar as a community module. Its tool is listed and callable, its route
 * answers, its ready hook adds its key; a throwing hook disables only the plugin; without the grants it is refused; and
 * removing its folder removes all of it. (The MCP side, `agenthq mcp serve` listing and calling plugin tools, is
 * McpServeTest's board-tools test.)
 */
class DaemonPluginMechanismTest {
    private val fixtures = ArrayList<DaemonFixture>()
    private val roots = ArrayList<File>()
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newHttpClient()

    private val granted = """{"Acme_DaemonHello":{"enabled":true,"allow":["daemon.code","daemon.tool","daemon.route","daemon.hook.ready","daemon.env:ACME_HELLO_FAIL"]}}"""

    @AfterTest
    fun tearDown() {
        fixtures.forEach { it.stop(); it.deleteState() }
        roots.forEach { it.deleteRecursively() }
    }

    private fun root(modulesJson: String = granted): File =
        TestDaemonPlugins.root("examples/Acme_DaemonHello", folder = "community", tier = "community", modulesJson = modulesJson).also { roots += it }

    private fun launch(
        root: File,
        env: (String) -> String? = { null },
        stateDir: File = Files.createTempDirectory("agenthq-daemon-plugin-mechanism").toFile(),
    ): Pair<DaemonFixture, Envelope> {
        val fixture = DaemonFixture.launch(
            stateDir = stateDir, pluginsRoot = root,
            pluginTrust = DaemonPluginHost.Trust(emptyList(), devTrust = false, bundledRoot = null), pluginEnv = env,
        ).also { fixtures += it }
        return fixture to fixture.client.await(Envelope.READY)
    }

    private fun DaemonFixture.tools(): List<String> {
        send(Envelope(DaemonPluginHost.PLUGIN_TOOLS, "", requestId = "tools-${System.nanoTime()}"))
        return json.parseToJsonElement(client.await(DaemonPluginHost.PLUGIN_TOOLS, occurrence = toolLists.merge(this, 1, Int::plus)!! - 1).data).jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
    }
    private val toolLists = HashMap<DaemonFixture, Int>()

    private fun DaemonFixture.callTool(name: String, arguments: String): JsonObject {
        val id = "call-${System.nanoTime()}"
        send(Envelope(DaemonPluginHost.PLUGIN_TOOL_CALL, """{"name":"$name","arguments":$arguments}""", requestId = id))
        val answer = client.awaitWhere("the $name answer") { it.requestId == id }
        assertEquals(DaemonPluginHost.PLUGIN_TOOL_RESULT, answer.type, answer.data)
        return json.parseToJsonElement(answer.data).jsonObject
    }

    /** The `ready` frame's `key=value` pairs. */
    private fun readyKeys(ready: Envelope): Map<String, String> =
        ready.data.split(' ').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun DaemonFixture.get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create("http://$connectHost:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    /** The module's `GET /plugins` entry, or null when the daemon does not list it. */
    private fun DaemonFixture.module(): JsonObject? =
        json.parseToJsonElement(get("/plugins").body()).jsonObject["modules"]!!.jsonArray.map { it.jsonObject }
            .firstOrNull { it["name"]!!.jsonPrimitive.content == MODULE }

    @Test
    fun `the plugin loads - its tool is listed and callable, its route answers, its hook runs`() {
        val (fixture, ready) = launch(root())
        assertEquals("hello", readyKeys(ready)["acmeHello"], "the ready hook's key: ${ready.data}")

        assertEquals(listOf(TOOL), fixture.tools())
        val result = fixture.callTool(TOOL, """{"name":"Ada"}""")
        assertEquals("Hello, Ada! This daemon has 0 session(s).", result["text"]!!.jsonPrimitive.content)
        assertEquals(false, result["isError"]!!.jsonPrimitive.content.toBoolean())

        val route = fixture.get("/plugins/$MODULE/hello")
        assertEquals(200, route.statusCode(), route.body())
        assertEquals("""{"hello":"world","module":"$MODULE"}""", route.body())

        val module = fixture.module()!!
        assertEquals("loaded", module["daemon"]!!.jsonPrimitive.content)
        assertEquals(listOf(TOOL), module["tools"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a throwing hook disables only its plugin and the daemon carries on`() {
        val (fixture, ready) = launch(root(), env = { if (it == "ACME_HELLO_FAIL") "1" else null })
        // The ready hook threw on this very ready: fail open (no key), the frame still came.
        assertNull(readyKeys(ready)["acmeHello"], ready.data)

        val status = fixture.module()!!["daemon"]!!.jsonPrimitive.content
        assertTrue(status.startsWith("failed: ") && "ACME_HELLO_FAIL is set" in status, status)
        assertEquals(emptyList(), fixture.tools(), "a disabled plugin's tools are gone")
        val call = fixture.callTool(TOOL, "{}")
        assertEquals(true, call["isError"]!!.jsonPrimitive.content.toBoolean(), "$call")
        assertEquals(404, fixture.get("/plugins/$MODULE/hello").statusCode(), "a disabled plugin's route is gone")
        // The daemon still answers, and a second client connects as usual.
        assertEquals(200, fixture.get("/health").statusCode())
        fixture.connectClient()
    }

    @Test
    fun `a community plugin without its grants is refused`() {
        val (none, _) = launch(root("""{"Acme_DaemonHello":{"enabled":true}}"""))
        assertEquals("refused: community daemon code needs the 'daemon.code' grant in modules.json", none.module()!!["daemon"]!!.jsonPrimitive.content)
        assertEquals(emptyList(), none.tools())
        assertEquals(404, none.get("/plugins/$MODULE/hello").statusCode())

        val (partial, ready) = launch(root("""{"Acme_DaemonHello":{"enabled":true,"allow":["daemon.code","daemon.tool"]}}"""))
        assertEquals("refused: missing grant 'daemon.hook.ready' in modules.json", partial.module()!!["daemon"]!!.jsonPrimitive.content)
        assertEquals(emptyList(), partial.tools())
        assertNull(readyKeys(ready)["acmeHello"])
    }

    @Test
    fun `a declared daemon half whose jar is missing is refused as jar missing`() {
        // PACKAGE-1: what a package that did not ship the half looks like: module.json declares daemon.jar, the jar is not there.
        val root = root()
        assertTrue(File(root, "community/$MODULE/daemon").deleteRecursively())
        val (fixture, _) = launch(root)
        val status = fixture.module()!!["daemon"]!!.jsonPrimitive.content
        assertTrue(status.startsWith("refused: jar missing: daemon.jar daemon/build/libs/$MODULE-daemon.jar"), status)
        assertEquals(emptyList(), fixture.tools())
        assertEquals(200, fixture.get("/health").statusCode())
    }

    @Test
    fun `removing the folder removes everything`() {
        val root = root()
        val state = Files.createTempDirectory("agenthq-daemon-plugin-remove").toFile()
        val (first, _) = launch(root, stateDir = state)
        assertEquals(listOf(TOOL), first.tools())
        first.stop()
        fixtures.remove(first)

        assertTrue(File(root, "community/$MODULE").deleteRecursively())
        val (second, ready) = launch(root, stateDir = state)
        assertNull(readyKeys(ready)["acmeHello"])
        assertEquals(emptyList(), second.tools())
        assertNull(second.module(), "GET /plugins no longer lists it")
        assertEquals(404, second.get("/plugins/$MODULE/hello").statusCode())
        assertEquals(true, second.callTool(TOOL, "{}")["isError"]!!.jsonPrimitive.content.toBoolean())
    }

    /** PLUGIN-RECOVER-1: three slow calls used to unregister the plugin until a restart; now the breaker opens, refuses fast, probes and closes. */
    @Test
    fun `three timed-out tool calls trip the breaker, the plugin recovers after the cooldown without a restart`() {
        val state = Files.createTempDirectory("agenthq-plugin-recover").toFile()
        val root = root()
        val (first, _) = launch(root, stateDir = state)
        for ((k, v) in listOf("limits.plugins.toolTimeoutMs" to 300, "$GUARD.timeoutMs" to 300, "$GUARD.strikes" to 3, "$GUARD.cooldownMs" to 2000)) {
            first.send(Envelope(Envelope.SETTINGS_SET, """{"key":"$k","value":$v}"""))
            first.client.awaitWhere("settings frame with $k") { it.type == Envelope.SETTINGS && json.parseToJsonElement(it.data).jsonObject[k]?.jsonPrimitive?.content == v.toString() }
        }
        first.stop(); fixtures.remove(first)
        val (daemon, _) = launch(root, stateDir = state)   // the limits are read at plugin load

        repeat(3) { assertEquals(true, daemon.callTool(TOOL, """{"sleepMs":3000}""")["isError"]!!.jsonPrimitive.content.toBoolean()) }
        val status = daemon.module()!!["daemon"]!!.jsonPrimitive.content
        assertEquals("loaded", status, "the plugin must not be unregistered for good: $status")
        val refused = daemon.callTool(TOOL, """{"name":"Ada"}""")
        assertTrue("cooling down" in refused["text"]!!.jsonPrimitive.content, "$refused")
        assertTrue(TOOL in daemon.tools(), "the tool stays registered while cooling down")

        Thread.sleep(2_300)
        val probe = daemon.callTool(TOOL, """{"name":"Ada"}""")
        assertEquals(false, probe["isError"]!!.jsonPrimitive.content.toBoolean(), "$probe")
        assertEquals("Hello, Ada! This daemon has 0 session(s).", probe["text"]!!.jsonPrimitive.content)
    }

    private companion object {
        const val GUARD = "plugin.guard"
        const val MODULE = "Acme_DaemonHello"
        const val TOOL = "acme_hello"
    }
}
