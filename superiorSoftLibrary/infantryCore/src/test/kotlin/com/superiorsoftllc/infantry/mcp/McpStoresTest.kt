// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.McpGrantInput
import com.superiorsoftllc.infantry.protocol.McpServerInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MCP-REGISTRY-1: the owned-server and grant stores, their limits and validation, the state-dir files. */
class McpStoresTest {
    @Test
    fun `owned servers refuse plain credentials, reserved names and mixed transports`() {
        val owned = McpOwnedServers(MemoryMcpStorage()) { 10 }
        assertFailsWith<IllegalArgumentException> { owned.put(McpServerInput("gh", url = "https://x/mcp", headers = mapOf("Authorization" to "Bearer abc")), null) }
        assertFailsWith<IllegalArgumentException> { owned.put(McpServerInput("s", command = listOf("x"), env = mapOf("GITHUB_TOKEN" to "abc")), null) }
        assertFailsWith<IllegalArgumentException> { owned.put(McpServerInput("infantry", command = listOf("x")), null) }
        assertFailsWith<IllegalArgumentException> { owned.put(McpServerInput("both", command = listOf("x"), url = "https://x"), null) }
        assertFailsWith<IllegalArgumentException> { owned.put(McpServerInput("bad name", command = listOf("x")), null) }
        owned.put(McpServerInput("ok", command = listOf("x"), envSecrets = mapOf("GITHUB_TOKEN" to "gh")), null)
        assertEquals(listOf("ok"), owned.all().map { it.server.name })
    }

    @Test
    fun `a project server shadows the app one of the same name, and the per-scope limit is a setting`() {
        val owned = McpOwnedServers(MemoryMcpStorage()) { 2 }
        owned.put(McpServerInput("a", url = "https://app/a"), null)
        owned.put(McpServerInput("a", url = "https://p1/a"), "p1")
        assertEquals("https://p1/a", owned.visible("p1").single { it.server.name == "a" }.server.url)
        assertEquals("https://app/a", owned.visible("p2").single().server.url)
        owned.put(McpServerInput("b", url = "https://app/b"), null)
        val e = assertFailsWith<IllegalArgumentException> { owned.put(McpServerInput("c", url = "https://app/c"), null) }
        assertTrue(e.message!!.contains("limits.mcp.serversMax"), e.message)
        assertFailsWith<IllegalArgumentException> { owned.put(McpServerInput("a", url = "https://app/a2"), null) }
        owned.put(McpServerInput("a", url = "https://app/a2"), null, replace = true)
        assertTrue(owned.setEnabled("a", null, false))
        assertFalse(owned.visible(null).single { it.server.name == "a" }.server.enabled)
        assertTrue(owned.remove("a", "p1"))
        assertFalse(owned.remove("a", "p1"))
    }

    @Test
    fun `a grant needs a purpose and an approver, the same grant again replaces it, and the count is limited`() {
        var now = 1L
        val grants = McpGrants(MemoryMcpStorage(), max = { 2 }, purposeChars = { 20 }, clock = { now++ })
        assertFailsWith<IllegalArgumentException> { grants.grant(McpGrantInput("pw", "ticket", "t-1", purpose = " ", approvedBy = "owner")) }
        assertFailsWith<IllegalArgumentException> { grants.grant(McpGrantInput("pw", "ticket", "t-1", purpose = "x", approvedBy = "")) }
        assertFailsWith<IllegalArgumentException> { grants.grant(McpGrantInput("pw", "everyone", "t-1", purpose = "x", approvedBy = "owner")) }
        assertFailsWith<IllegalArgumentException> { grants.grant(McpGrantInput("pw", "ticket", "t-1", purpose = "x".repeat(21), approvedBy = "owner")) }
        val first = grants.grant(McpGrantInput("pw", "ticket", "t-1", purpose = "browser", approvedBy = "owner"))
        val again = grants.grant(McpGrantInput("pw", "ticket", "t-1", purpose = "browser again", approvedBy = "owner"))
        assertEquals(listOf(again), grants.list())
        assertTrue(again.approvedAt > first.approvedAt)
        grants.grant(McpGrantInput("pw", "role", "qa", purpose = "tests", approvedBy = "owner"))
        val full = assertFailsWith<IllegalArgumentException> { grants.grant(McpGrantInput("pw", "agent", "a-1", purpose = "x", approvedBy = "owner")) }
        assertTrue(full.message!!.contains("limits.mcp.grantsMax"), full.message)
        assertTrue(grants.revoke(again.id))
        assertFalse(grants.revoke(again.id))
    }

    @Test
    fun `file storage keeps the documents in the state dir across instances`() {
        val dir = Files.createTempDirectory("mcp-store").toFile()
        try {
            McpGrants(FileMcpStorage(dir), { 10 }, { 100 }).grant(McpGrantInput("pw", "role", "qa", purpose = "tests", approvedBy = "owner"))
            assertEquals(1, McpGrants(FileMcpStorage(dir), { 10 }, { 100 }).list().size)
            assertTrue(java.io.File(dir, "grants.json").isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `session config files live under the given root, one folder per session, deleted with it`() {
        val root = Files.createTempDirectory("mcp-sessions").toFile()
        try {
            val files = McpConfigFiles(root)
            val spec = McpServerSpec("gh", McpTransport.Http("https://gh/mcp", mapOf("Authorization" to "Bearer x")), McpSource.INFANTRY_USER)
            val stdio = McpServerSpec("fs", McpTransport.Stdio(listOf("node", "fs.js", "--root", "."), mapOf("MODE" to "ro")), McpSource.PLUGIN)
            val f = files.write("sess-1", "mcp.json", McpConfigFiles.standardJson(listOf(spec, stdio)))
            assertEquals(java.io.File(root, "sess-1").canonicalFile, f.parentFile.canonicalFile)
            val servers = Json.parseToJsonElement(f.readText()).jsonObject.getValue("mcpServers").jsonObject
            assertEquals(setOf("gh", "fs"), servers.keys)
            assertTrue(servers.getValue("fs").toString().contains("\"args\":[\"fs.js\",\"--root\",\".\"]"), servers.toString())
            assertFailsWith<IllegalArgumentException> { files.write("../escape", "mcp.json", "{}") }
            assertFailsWith<IllegalArgumentException> { files.write("sess-1", "../x.json", "{}") }
            files.write("sess-2", "mcp.json", "{}")
            assertEquals(1, files.sweep(keep = setOf("sess-1")))
            assertTrue(files.delete("sess-1"))
            assertFalse(java.io.File(root, "sess-1").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `frames route a CLI target to its HarnessMcp and refuse what it cannot do`() {
        val storage = MemoryMcpStorage()
        val owned = McpOwnedServers(storage) { 10 }
        val cli = object : com.superiorsoftllc.infantry.harness.api.HarnessMcp {}
        val registry = McpRegistry(
            owned, { listOf("cli-a") }, { if (it == "cli-a") cli else null }, { "home" }, { _, _ -> null },
            com.superiorsoftllc.infantry.provider.ListingCache({ 0 }, { 0 }, executor = { it.run() }),
        )
        val svc = McpService(registry, owned, McpGrants(storage, { 10 }, { 100 }), { if (it == "cli-a") cli else null }, { true }, { _, _ -> null })
        val add = svc.handle(Envelope.MCP_ADD, """{"target":"infantry","server":{"name":"docs","url":"https://d/mcp"}}""")
        assertTrue(add.changed)
        assertTrue(add.data.contains("\"name\":\"docs\""), add.data)
        val refused = assertFailsWith<IllegalArgumentException> {
            svc.handle(Envelope.MCP_ADD, """{"target":"cli-a","scope":"user","server":{"name":"x","url":"https://x/mcp"}}""")
        }
        assertTrue(refused.message!!.contains("cannot add"), refused.message)
        assertFailsWith<IllegalArgumentException> { svc.handle(Envelope.MCP_SET_ENABLED, """{"target":"cli-a","name":"x","enabled":false}""") }
        val assign = svc.handle(Envelope.MCP_ASSIGN, """{"grant":{"server":"docs","scope":"role","target":"qa","purpose":"docs","approvedBy":"owner"}}""")
        assertEquals(Envelope.MCP_GRANTS, assign.type)
        assertTrue(assign.changed)
    }
}
