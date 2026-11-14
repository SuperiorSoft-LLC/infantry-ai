// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.mcp

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpModelTest {
    private val list = """{"servers":[
      {"name":"a","source":"harness","target":"claude","scope":"user","transport":"stdio","command":["x"]},
      {"name":"b","source":"harness","target":"claude","scope":"project","transport":"http","url":"http://h"},
      {"name":"c","source":"harness","target":"opencode","transport":"stdio"},
      {"name":"d","source":"infantry_user","target":"infantry","scope":"app","transport":"stdio","enabled":false},
      {"name":"e","source":"plugin","target":"infantry","transport":"stdio"}],
      "targets":[{"id":"claude","canAdd":true,"canRemove":true,"canEdit":true,"scopes":["user","project","local"]},{"id":"opencode"}],
      "grants":[{"id":"g1","server":"a","scope":"role","target":"dev","purpose":"p","approvedBy":"owner","approvedAt":1}]}"""

    @Test fun `servers group by source in a stable order with read-only CLIs marked`() {
        val groups = groupMcpServers(parseMcpSnapshot(list))
        assertEquals(listOf("Claude (user)", "Claude (project)", "Opencode", "Infantry-owned", "Plugins"), groups.map { it.title })
        assertEquals(listOf(false, false, true, false, true), groups.map { it.readOnly })
    }

    @Test fun `a server not granted reads as walled`() {
        val s = parseMcpSnapshot(list)
        assertEquals("role dev", grantSummary(s.servers[0], s.grants))
        assertEquals("Not granted (walled)", grantSummary(s.servers[1], s.grants))
    }

    @Test fun `a grant without a purpose is refused and never sent`() = runBlocking {
        val sent = mutableListOf<String>()
        val vm = McpViewModel({ t, _ -> sent += t; null }, { null })
        assertNotNull(grantProblem(GrantDraft("a", target = "dev", purpose = " ")))
        assertEquals(false, vm.grant(GrantDraft("a", target = "dev", purpose = "")))
        assertTrue(sent.isEmpty())
        assertNull(grantProblem(GrantDraft("a", target = "dev", purpose = "scrape docs")))
    }

    @Test fun `remove sends nothing until confirmed then calls the backend once`() = runBlocking {
        val sent = mutableListOf<Pair<String, String>>()
        val vm = McpViewModel({ t, d -> sent += t to d; McpWire.SERVERS to list }, { "p1" })
        vm.refresh(); sent.clear()
        val s = vm.snapshot.servers.first { it.name == "a" }
        vm.askRemove(s)
        assertTrue(sent.isEmpty())
        vm.cancelRemove(); vm.confirmRemove()
        assertTrue(sent.isEmpty())
        vm.askRemove(s); vm.confirmRemove()
        assertEquals(1, sent.size)
        assertEquals(McpWire.REMOVE, sent[0].first)
        assertTrue("\"name\":\"a\"" in sent[0].second && "\"target\":\"claude\"" in sent[0].second && "\"scope\":\"user\"" in sent[0].second)
        assertNull(vm.pendingRemove)
    }

    @Test fun `a refusal lands in error and an add builds the server request`() = runBlocking {
        val vm = McpViewModel({ _, _ -> "error" to "nope" }, { null })
        vm.refresh()
        assertEquals("nope", vm.error)
        val req = serverRequest(ServerDraft(name = "s", command = "npx -y srv", env = "A=1"), null).toString()
        assertTrue("\"command\":[\"npx\",\"-y\",\"srv\"]" in req && "\"env\":{\"A\":\"1\"}" in req)
        assertNotNull(serverProblem(ServerDraft(name = "")))
        Unit
    }

    private val grants = parseMcpSnapshot("""{"grants":[
      {"id":"g1","server":"a","scope":"role","target":"dev","purpose":"p","approvedBy":"owner","approvedAt":1700000000000},
      {"id":"g2","server":"b","scope":"agent","target":"agent-1","purpose":"q","approvedBy":"owner","approvedAt":1800000000000},
      {"id":"g3","server":"a","scope":"ticket","target":"t1","purpose":"r","approvedBy":"owner"}]}""").grants

    @Test fun `grants filter by server or target and list newest first`() {
        assertEquals(listOf("g2", "g1", "g3"), filterGrants(grants, GrantFilter()).map { it.id })
        assertEquals(listOf("g1", "g3"), filterGrants(grants, GrantFilter(server = "a")).map { it.id })
        assertEquals(listOf("g2"), filterGrants(grants, GrantFilter(target = "agent-1")).map { it.id })
        assertTrue(filterGrants(grants, GrantFilter(server = "b", target = "dev")).isEmpty())
    }

    @Test fun `grant dates are utc days and blank when unknown`() {
        assertEquals("2023-11-14", grantDate(1700000000000))
        assertEquals("", grantDate(0))
    }

    @Test fun `compact overflow holds the card actions and read-only groups only grant`() {
        assertEquals(listOf(CardAction.GRANT, CardAction.TOGGLE, CardAction.EDIT, CardAction.REMOVE), overflowActions(false, true))
        assertEquals(listOf(CardAction.GRANT, CardAction.EDIT, CardAction.REMOVE), overflowActions(false, false))
        assertEquals(listOf(CardAction.GRANT), overflowActions(true, false))
    }

    @Test fun `revoke sends the grant id once`() = runBlocking {
        val sent = mutableListOf<Pair<String, String>>()
        val vm = McpViewModel({ t, d -> sent += t to d; McpWire.SERVERS to list }, { null })
        vm.revoke(grants[0])
        assertEquals(McpWire.REVOKE, sent[0].first)
        assertTrue("\"grantId\":\"g1\"" in sent[0].second)
    }
}
