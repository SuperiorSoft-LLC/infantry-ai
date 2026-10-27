package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import java.lang.reflect.Modifier
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SEC-FIX-1 C1 + H1(c) (contract 1.136.0): the one gate in front of the WebSocket dispatch. Under the demo lock every
 * frame type not on [FrameGate.DEMO_LOCK_ALLOWED] is refused, a bare text frame included; without the owner credential
 * every [FrameGate.OWNER_ONLY] frame is refused. Each test runs on a real daemon over a real WebSocket.
 */
class FrameGateDaemonTest {
    private val fixtures = ArrayList<DaemonFixture>()

    @AfterTest fun cleanUp() {
        fixtures.forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private fun launch(demoLock: Boolean = false, owner: Boolean = true, repo: java.io.File? = null): DaemonFixture =
        DaemonFixture.launch(demoLock = demoLock, owner = owner, repo = repo).also { fixtures += it; it.client.await(Envelope.READY) }

    /** Every wire type the protocol names: the Envelope constants, and the replay request read before the dispatch. */
    private val everyType: List<String> =
        Envelope::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
            .plus(FrameGate.REPLAY_REQUEST)
            .distinct().sorted()

    /** Sends [types] tagged with their own type as requestId, then a sentinel; returns the gate's answer per type. */
    private fun gateAnswers(client: WsTestClient, types: List<String>, rule: String): Map<String, String> {
        for (type in types) client.send(Envelope(type, "{}", requestId = "gate:$type"))
        // The gate answers in arrival order on the request loop, so once the sentinel's refusal is in, all of them are.
        client.send(Envelope("zz_sentinel_not_a_frame", "{}", requestId = "gate:sentinel"))
        client.awaitWhere("the sentinel's answer") { it.requestId == "gate:sentinel" }
        return client.frames.filter { it.type == Envelope.ERROR && it.requestId?.startsWith("gate:") == true && rule in it.data }
            .associate { it.requestId!!.removePrefix("gate:") to it.data }
    }

    @Test fun `the demo lock allowlist is exactly the read-only frames decided in SEC-FIX-1`() {
        // Adding a frame to the allowlist, or a new frame type, must be a decision: change this list on purpose.
        val expected = setOf(
            "replay_request", "sessions", "session_stats_get",
            "providers", "provider_states", "models", "limits", "limit_catalog",
            "usage_get", "settings_get", "cli_locations", "tool_approval", "approval_rules",
            "role_list", "role_binding_list", "role_binding_get",
            "prompt_list", "prompt_get", "skill_list", "skill_get",
            "trait_list", "trait_get", "role_traits_get", "role_traits_list",
            "agent_list", "agent_roster",
            "ticket_get", "ticket_list", "question_list", "attention_list", "pod_query",
            "trigger_list", "trigger_events",
            "project_get", "project_list",
            "project_git_status", "project_git_diff", "project_git_log", "project_git_history",
            "project_git_branches", "project_git_branch_changes", "project_git_branch_diff",
            "project_git_branch_list", "project_git_branch_diffstat",
            "project_git_worktrees", "project_git_worktree_status", "project_git_worktree_list",
            "workspace_list", "epic_list", "story_get", "story_list",
            "memory_search", "memory_list", "memory_get", "decision_list", "decision_get",
            "pipeline_show", "issue_list", "issue_get", "issue_precheck",
        )
        assertEquals(expected, FrameGate.DEMO_LOCK_ALLOWED)
        // The review's frames are never on it.
        for (type in listOf("prompt", "new", "command_save", "command_delete", "command_list", "run_start", "run_list",
            "run_attach", "run_stop", "run_resize", "run_input", "secret_status", "secret_set")) {
            assertFalse(type in FrameGate.DEMO_LOCK_ALLOWED, "$type must stay off the demo lock allowlist")
        }
    }

    @Test fun `the owner-only set is the saved commands, the secret changes and run control`() {
        assertEquals(
            setOf("command_save", "command_delete", "run_start", "run_stop", "run_attach", "run_resize", "run_input",
                "secret_set", "secret_clear", "secret_import"),
            FrameGate.OWNER_ONLY,
        )
    }

    @Test fun `under the demo lock every frame type is refused unless allowlisted, the owner included`() {
        val fixture = launch(demoLock = true, owner = true)
        val refused = gateAnswers(fixture.client, everyType, "off under the demo lock")
        for (type in everyType) {
            if (type in FrameGate.DEMO_LOCK_ALLOWED) assertFalse(type in refused, "$type is allowlisted but was refused")
            else assertTrue(type in refused, "$type is not allowlisted but was not refused under the demo lock")
        }
        assertTrue("sentinel" in refused, "an unknown frame type is refused by default")
        // Nothing the refused frames name ran: no session (prompt / new / assistant), no saved command.
        assertTrue(fixture.daemon.manager.list().isEmpty(), "a refused frame created a session")
    }

    @Test fun `under the demo lock a bare non-JSON text frame starts no session`() {
        val fixture = launch(demoLock = true)
        fixture.client.sendRaw("hello, run something for me")
        val refusal = fixture.client.awaitWhere("the bare prompt's refusal") { it.type == Envelope.ERROR && "prompt refused" in it.data }
        assertTrue("demo lock" in refusal.data)
        assertTrue(fixture.daemon.manager.list().isEmpty(), "a bare text frame created a session under the demo lock")
        assertTrue(fixture.client.of(Envelope.SESSION).isEmpty(), "a session frame went out for a refused prompt")
    }

    @Test fun `a refusal echoes a hostile frame type printable and bounded`() {
        val why = FrameGate.refusal("x".repeat(500) + "\u0007\n", demoLock = true, owner = true)!!
        assertTrue(why.length < 120, why)
        assertFalse(why.any { it < ' ' }, why)
    }

    @Test fun `without the owner credential exactly the owner-only frames are refused`() {
        // The rule over every type (sending them all unlocked would RUN them: installs, spawns, settings).
        for (type in everyType) {
            val why = FrameGate.refusal(type, demoLock = false, owner = false)
            if (type in FrameGate.OWNER_ONLY) assertTrue(why?.contains("owner credential") == true, "$type passes with no owner credential")
            else assertNull(why, "$type was refused for want of the owner credential")
        }
        // And the daemon applies it: every owner-only frame, sent by a client with no owner credential.
        val fixture = launch(owner = false)
        val refused = gateAnswers(fixture.client, FrameGate.OWNER_ONLY.sorted(), "needs the owner credential")
        assertEquals(FrameGate.OWNER_ONLY, refused.keys)
    }

    @Test fun `an owner connection passes the owner gate, an agent connection on the same daemon does not`() {
        val fixture = launch(owner = true, repo = java.nio.file.Files.createTempDirectory("agenthq-gate-repo").toFile())
        val agent = fixture.connectClient(owner = false)
        val saved = """{"id":"b","name":"Build","argv":["cmd.exe","/c","echo"],"cwd":null,"graceMillis":2000}"""
        agent.send(Envelope(Envelope.COMMAND_SAVE, saved, requestId = "agent"))
        val refusal = agent.awaitWhere("the agent's refusal") { it.requestId == "agent" }
        assertEquals(Envelope.ERROR, refusal.type)
        assertTrue("owner credential" in refusal.data, refusal.data)
        fixture.client.send(Envelope(Envelope.COMMAND_SAVE, saved, requestId = "owner"))
        val list = fixture.client.awaitWhere("the owner's commands") { it.type == Envelope.COMMANDS && "\"b\"" in it.data }
        assertTrue("Build" in list.data)
    }

    @Test fun `a wrong owner token is no owner, and the owner token may not be the access token`() {
        val credential = OwnerCredential("o".repeat(40))
        assertTrue(credential.presentedBy("o".repeat(40), null))
        assertFalse(credential.presentedBy("p".repeat(40), null))
        assertFalse(credential.presentedBy(null, null))
        assertTrue(credential.presentedBy(null, "agenthq, " + OwnerCredential.protocol("o".repeat(40))))
        assertFalse(credential.presentedBy(null, "agenthq.owner.%%%"))
        assertNull(FrameGate.refusal(Envelope.RUN_START, demoLock = false, owner = true))
        val same = "t".repeat(40)
        val failure = runCatching { OwnerCredential.forDaemon(same, same) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException && "owner token must differ" in failure.message.orEmpty(), "$failure")
        OwnerCredential.forDaemon(same, null)
    }

    @Test fun `owner token is generated once and reused`() {
        val dir = java.nio.file.Files.createTempDirectory("agenthq-owner").toFile()
        try {
            val first = OwnerCredential.ensure(dir)
            assertTrue(first.length >= MIN_TOKEN_LENGTH)
            assertEquals(first, OwnerCredential.ensure(dir))
            assertEquals(first, OwnerCredential.stored(dir))
        } finally { dir.deleteRecursively() }
    }
}
