// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.sun.net.httpserver.HttpServer
import com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 7): an agent with no code orchestration, end to end on a real daemon. The example
 * `plugins/examples/Acme_NewsAgent` (community, from its BUILT jar) beside the official Infantry_Agents (the registry tools): the
 * agent is listed from its module.json, its feed tool fetches a local plain-HTTP RSS feed, the fetch re-contributes the agent with
 * the headlines, and the user's disable / enable round-trips. Without the `agents.contribute` grant the agent is not listed.
 */
class NewsAgentPluginTest {
    private val fixtures = ArrayList<DaemonFixture>()
    private val cleanup = ArrayList<File>()
    private val json = Json { ignoreUnknownKeys = true }
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        fixtures.forEach { it.stop(); it.deleteState() }
        cleanup.forEach { it.deleteRecursively() }
        server?.stop(0)
    }

    private fun feed(): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/feed.xml") { ex ->
            val body = """<?xml version="1.0"?><rss><channel><title>Acme wire</title>
                <item><title>Kettle boils &amp; sings</title></item><item><title><![CDATA[Toast lands butter-side up]]></title></item>
                </channel></rss>""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/rss+xml")
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/feed.xml"
    }

    private fun root(newsGrants: String): File {
        val root = Files.createTempDirectory("news-agent-root").toFile().also { cleanup += it }
        File(root, "modules.json").writeText(
            """{"folders":[{"path":"official","tier":"official"},{"path":"community","tier":"community"}],
               "modules":{"Acme_NewsAgent":{"enabled":true,"allow":[$newsGrants]}}}""",
        )
        TestDaemonPlugins.copy(File(TestDaemonPlugins.repo, "plugins/official/Infantry_Agents"), File(root, "official/Infantry_Agents"))
        TestDaemonPlugins.copy(File(TestDaemonPlugins.repo, "plugins/examples/Acme_NewsAgent"), File(root, "community/Acme_NewsAgent"))
        return root
    }

    private fun launch(root: File, env: (String) -> String? = { null }): DaemonFixture {
        val fixture = DaemonFixture.launch(
            stateDir = Files.createTempDirectory("infantry-news-agent").toFile(), pluginsRoot = root,
            pluginTrust = TestDaemonPlugins.trustAll, pluginEnv = env,
        ).also { fixtures += it }
        fixture.client.await(Envelope.READY)
        return fixture
    }

    private fun DaemonFixture.tools(): List<String> {
        val id = "tools-${System.nanoTime()}"
        send(Envelope(DaemonPluginHost.PLUGIN_TOOLS, "", requestId = id))
        return json.parseToJsonElement(client.awaitWhere("plugin_tools") { it.requestId == id }.data).jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    }

    private fun DaemonFixture.call(name: String, arguments: String = "{}"): Pair<String, Boolean> {
        val id = "call-${System.nanoTime()}"
        send(Envelope(DaemonPluginHost.PLUGIN_TOOL_CALL, """{"name":"$name","arguments":$arguments}""", requestId = id))
        val answer = client.awaitWhere("the $name answer") { it.requestId == id }
        assertEquals(DaemonPluginHost.PLUGIN_TOOL_RESULT, answer.type, answer.data)
        val o: JsonObject = json.parseToJsonElement(answer.data).jsonObject
        return o["text"]!!.jsonPrimitive.content to o["isError"]!!.jsonPrimitive.content.toBoolean()
    }

    @Test
    fun `the news agent is listed, fetches its feed, carries the headlines and can be disabled`() {
        val url = feed()
        val d = launch(root(""""daemon.code","daemon.tool","daemon.env:ACME_NEWS_FEED_URL","agents.contribute""""), env = { if (it == "ACME_NEWS_FEED_URL") url else null })
        val tools = d.tools()
        assertTrue(tools.containsAll(listOf("acme_news_fetch", "infantry_agent_list", "infantry_agent_get", "infantry_agent_enable", "infantry_agent_disable")), tools.toString())

        val (list, listErr) = d.call("infantry_agent_list")
        assertFalse(listErr, list)
        assertTrue("acme-news\tNews\t(Acme_NewsAgent/manifest)" in list, list)
        assertTrue(list.lines().first().startsWith("architect\t"), "the built-in agents come first, unchanged: $list")

        val (fetched, fetchErr) = d.call("acme_news_fetch")
        assertFalse(fetchErr, fetched)
        assertEquals("- Kettle boils & sings\n- Toast lands butter-side up", fetched)

        val (got, _) = d.call("infantry_agent_get", """{"id":"acme-news"}""")
        val entry = json.parseToJsonElement(got).jsonObject
        assertEquals("service", entry["source"]!!.jsonPrimitive.content, got)
        assertTrue("- Toast lands butter-side up" in entry["effectiveInstructions"]!!.jsonPrimitive.content, got)
        assertEquals(60, entry["definition"]!!.jsonObject["schedule"]!!.jsonObject["everyMinutes"]!!.jsonPrimitive.content.toInt())

        assertEquals("agent 'acme-news' is disabled", d.call("infantry_agent_disable", """{"id":"acme-news"}""").first)
        assertTrue("acme-news\tNews\t[disabled]" in d.call("infantry_agent_list").first)
        assertEquals("agent 'acme-news' is enabled", d.call("infantry_agent_enable", """{"id":"acme-news"}""").first)
        assertTrue(d.call("infantry_agent_get", """{"id":"nobody"}""").second)
    }

    @Test
    fun `an agent caller may not disable agents`() {
        val d = launch(root(""""daemon.code","daemon.tool","agents.contribute""""))
        val id = "call-${System.nanoTime()}"
        d.send(Envelope(DaemonPluginHost.PLUGIN_TOOL_CALL, """{"name":"infantry_agent_disable","arguments":{"id":"pm"},"client":{"sessionId":"s-1"}}""", requestId = id))
        val answer = json.parseToJsonElement(d.client.awaitWhere("disable answer") { it.requestId == id }.data).jsonObject
        assertTrue(answer["isError"]!!.jsonPrimitive.content.toBoolean(), answer.toString())
        assertTrue(d.call("infantry_agent_list").first.lines().none { "[disabled]" in it })
    }

    @Test
    fun `without the agents grant the agent is not listed`() {
        val d = launch(root(""""daemon.code","daemon.tool""""))
        val (list, _) = d.call("infantry_agent_list")
        assertFalse("acme-news" in list, list)
        assertTrue("architect" in list)
    }
}
