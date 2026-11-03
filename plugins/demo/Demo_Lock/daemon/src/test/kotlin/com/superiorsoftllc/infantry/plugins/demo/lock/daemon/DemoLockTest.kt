package com.superiorsoftllc.infantry.plugins.demo.lock.daemon

import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.mailbox.MailSendRequest
import com.superiorsoftllc.infantry.policy.PolicyProvider
import com.superiorsoftllc.infantry.policy.PolicyRuntime
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEMO-LOCK (ticket 2f154f5f), moved out of core by DAEMON-PLUGINS-1: the lockdown's decisions, for every token (the lock
 * never looks at the tier), against core's real catalog and the demo preset this module ships. [PresetPolicy.gate] is the
 * daemon's order: the preset's own rules (core), then this plugin's scoping.
 */
class DemoLockTest {
    private val a = "session-a"
    private val b = "session-b"
    private val policy = PresetPolicy(DemoPreset.preset)
    private fun locked() = DemoPreset.lock(notice = "This is a demo.").also { it.adopt("project-demo", mapOf("backend" to a, "pm" to b)) }
    private fun mail(from: String, to: String, cross: Boolean = false) =
        Json.encodeToString(MailSendRequest.serializer(), MailSendRequest(from = from, to = to, body = "hi", allowCrossProject = cross))
    private fun DemoLock.gate(type: String, data: String = "", session: String? = null, active: String? = null) = policy.gate(this, Frame(type, data, session), active)
    private fun refusal(type: String) = policy.refusalText(type)

    /** The frozen read lists of the pre-POLICY-1 lock, the parity baseline (core's DemoPresetFixture holds the same). */
    private val readFrames = setOf(
        Envelope.SESSIONS, Envelope.PROJECT_LIST, Envelope.PROJECT_GET, Envelope.WORKSPACE_LIST,
        Envelope.SETTINGS_GET, Envelope.LIMIT_CATALOG, Envelope.LIMITS, Envelope.USAGE_GET,
        Envelope.TICKET_LIST, Envelope.TRIGGER_LIST, Envelope.COMMAND_LIST, Envelope.ROLE_LIST,
        Envelope.PROVIDERS, Envelope.MODELS, Envelope.SESSION_STATS_GET, Envelope.QUESTION_LIST,
        Envelope.MAIL_INBOX, Envelope.MAIL_OUTBOX, Envelope.MAIL_THREAD, Envelope.MAIL_THREADS,
        Envelope.MAIL_WAITING, Envelope.MAIL_READ,
        Envelope.AGENT_LIST, Envelope.ROLE_BINDING_LIST, Envelope.TRAIT_LIST, Envelope.ROLE_TRAITS_LIST,
        Envelope.ATTENTION_LIST, Envelope.PROVIDER_STATES, Envelope.PROMPT_LIST, Envelope.SKILL_LIST,
        Envelope.USAGE_METER_GET, Envelope.CLI_LOCATIONS, Envelope.TOOL_APPROVAL, Envelope.APPROVAL_RULES, Envelope.ROLE_BINDING_GET,
        Envelope.PROMPT_GET, Envelope.SKILL_GET, Envelope.TRAIT_GET, Envelope.ROLE_TRAITS_GET, Envelope.AGENT_ROSTER, Envelope.TICKET_GET,
        Envelope.TRIGGER_EVENTS, Envelope.POD_QUERY,
    )
    private val projectReadFrames = setOf(
        Envelope.PROJECT_GIT_STATUS, Envelope.PROJECT_GIT_LOG, Envelope.PROJECT_GIT_DIFF,
        Envelope.PROJECT_GIT_BRANCHES, Envelope.PROJECT_GIT_BRANCH_CHANGES, Envelope.PROJECT_GIT_BRANCH_DIFF,
        Envelope.PROJECT_GIT_WORKTREES, Envelope.PROJECT_GIT_WORKTREE_STATUS,
        Envelope.EPIC_LIST, Envelope.MEMORY_LIST, Envelope.MEMORY_SEARCH, Envelope.MEMORY_GET, Envelope.PIPELINE_SHOW,
    )

    @Test
    fun `off lets everything through`() {
        val off = DemoLock(false)
        assertNull(off.frameRefusal(Frame(Envelope.PROJECT_SAVE, "{}"), null, policy))
        assertNull(off.frameRefusal(Frame("new", "{}"), null, policy))
        assertNull(off.httpRefusal("POST", "/update/apply", false))
        assertNull(off.mailRefusal("x", "y", false))
    }

    @Test
    fun `the lock is the preset's, each seat value overridable by the deployment's env`() {
        val state = Files.createTempDirectory("demo-lock-of").toFile()
        try {
            val env = mapOf(DemoLock.NOTICE_ENV to "N", DemoLock.SEATS_ENV to "qa:opencode:m1,pm:pi:m2", DemoLock.DIR_ENV to "/tmp/d")
            val on = DemoLock.of(DemoPreset.preset, { env[it] }, state)
            assertTrue(on.enabled)
            assertEquals("N", on.notice)
            assertEquals("/tmp/d", on.projectDir)
            assertEquals(listOf(DemoLock.Seat("qa", "opencode", "m1"), DemoLock.Seat("pm", "pi", "m2")), on.seats)
            val plain = DemoLock.of(DemoPreset.preset, { null }, state)
            assertEquals(DemoLock.parseSeats(DemoPreset.preset.seats!!.seats), plain.seats)
            assertEquals(DemoPreset.preset.seats!!.projectDir, plain.projectDir)
            assertEquals(DemoPreset.preset.seats!!.warmUpPrompt, plain.warmUpPrompt)
        } finally { state.deleteRecursively() }
    }

    @Test
    fun `ready carries demo=lock and the plugin's notice base64url`() {
        val lock = locked()
        val runtime = PolicyRuntime(listOf(PolicyProvider { listOf(DemoPreset.preset) }), { if (it == PolicyRuntime.POLICY_ENV) "demo" else null }, null, Files.createTempDirectory("demo-ready").toFile())
        runtime.attach(object : PolicyRuntime.Extensions { override fun notice() = lock.notice; override fun seatsClaimed() = true })
        val fields = runtime.readyFields()
        assertTrue(fields.startsWith(" demo=lock access=restricted notices=demo_limit notice="), fields)
        assertEquals("This is a demo.", String(java.util.Base64.getUrlDecoder().decode(fields.substringAfter("notice="))))
    }

    @Test
    fun `reads pass, writes and spawns are refused`() {
        val lock = locked()
        for (t in readFrames) assertNull(lock.gate(t), t)
        for (t in listOf("new", Envelope.PROJECT_SAVE, "settings_set", "ticket_save", "role_save", "agent_save", "secret_set",
            "harness_install", "cli_location_set", "session_delete", "image_upload", "pod_submit", "assistant_open", "anything_new")) {
            assertEquals(refusal(t), lock.gate(t, "{}", a), t)
        }
    }

    @Test
    fun `per-project reads pass only naming the demo project, the git checkout never`() {
        val lock = locked()
        val demo = """{"projectId":"project-demo"}"""
        for (t in projectReadFrames) {
            assertNull(lock.gate(t, demo), t)
            assertEquals(refusal(t), lock.gate(t, """{"projectId":"project-other"}"""), t)
            assertEquals(refusal(t), lock.gate(t, "{}"), t)
            assertEquals(refusal(t), lock.gate(t, ""), t)
            assertEquals(refusal(t), lock.gate(t, """{"projectId":["project-demo"]}"""), t)
        }
        // A payload that could name two projects (repeated key, or a \u escape spelling the key) is refused.
        val log = Envelope.PROJECT_GIT_LOG
        assertNotNull(lock.gate(log, """{"projectId":"project-demo","projectId":"project-other"}"""))
        assertNotNull(lock.gate(log, """{"projectId":"project-demo","project\u0049d":"project-other"}"""))
        assertNotNull(lock.gate(log, """{"projectId":"project-\u0064emo"}"""))
        // The bare-id payloads: project_git_status, epic_list and the project-scope memory_list.
        assertNull(lock.gate(Envelope.PROJECT_GIT_STATUS, "project-demo"))
        assertNull(lock.gate(Envelope.EPIC_LIST, " project-demo "))
        assertNotNull(lock.gate(Envelope.EPIC_LIST, "project-other"))
        assertNotNull(lock.gate(Envelope.PROJECT_GIT_CHECKOUT, demo))
        assertNotNull(lock.gate(Envelope.EPIC_SAVE, demo))
        assertNotNull(lock.gate(Envelope.MEMORY_SAVE, demo))
        // Before the seed there is no demo project: nothing names it.
        assertNotNull(DemoPreset.lock().gate(Envelope.EPIC_LIST, ""))
    }

    @Test
    fun `prompt and replay only for the two seeded sessions, interrupt and kill never`() {
        val lock = locked()
        assertNull(lock.gate(Envelope.PROMPT, "hello", a))
        assertNull(lock.gate(Envelope.PROMPT, "hello", b))
        assertNotNull(lock.gate(Envelope.PROMPT, "hello"), "a prompt naming no session would create one")
        assertNotNull(lock.gate(Envelope.PROMPT, "hello", "session-c"))
        assertNotNull(lock.gate(Envelope.PROMPT, "/model x", a), "slash commands are refused")
        assertNull(lock.gate(Envelope.RESUME, "$a\nhello"))
        assertNotNull(lock.gate(Envelope.RESUME, "session-c\nhello"))
        assertNotNull(lock.gate(Envelope.RESUME, "$a\n/model paid"))
        assertNotNull(lock.gate(Envelope.INTERRUPT, a))
        assertNotNull(lock.gate(Envelope.KILL, "$b force"))
        assertNotNull(lock.gate(Envelope.KILL, "session-c"))
        assertNull(lock.gate("replay_request", "{}", a))
        assertNotNull(lock.gate("replay_request", "{}", "session-c"))
    }

    @Test
    fun `mail only between the two seeded sessions, by id or role`() {
        val lock = locked()
        assertNull(lock.gate(Envelope.MAIL_SEND, mail("", b), a))
        assertNull(lock.gate(Envelope.MAIL_SEND, mail("", "backend"), b))
        assertNull(lock.gate(Envelope.MAIL_SEND, mail("", "pm"), active = a))
        assertNotNull(lock.gate(Envelope.MAIL_SEND, mail("", a), a), "not to itself")
        assertNotNull(lock.gate(Envelope.MAIL_SEND, mail("owner", a)), "not from the owner")
        assertNotNull(lock.gate(Envelope.MAIL_SEND, mail("", "qa"), a), "not to another role")
        assertNotNull(lock.gate(Envelope.MAIL_SEND, mail("", b, cross = true), a))
        assertNotNull(lock.gate(Envelope.MAIL_SEND, "not json", a))
        assertNull(lock.mailRefusal(a, b, false))
        assertEquals(DemoPreset.preset.seats!!.mailRefusal, lock.mailRefusal("user", b, false))
    }

    @Test
    fun `http allows health, the socket, the bundle, the visitor token and the agent mail API only`() {
        val lock = locked()
        assertNull(lock.httpRefusal("GET", "/health", false))
        assertNull(lock.httpRefusal("GET", "/ws", false))
        assertNull(lock.httpRefusal("GET", "/attention", false), "the web bundle checks its token with it")
        assertNull(lock.httpRefusal("GET", "/app.js", true))
        assertNull(lock.httpRefusal("GET", DemoLock.VISITOR_TOKEN_PATH, false))
        assertNull(lock.httpRefusal("POST", "/mail/send", false))
        assertNull(lock.httpRefusal("GET", "/mail/thread/pair:a|b", false))
        for ((m, p) in listOf("POST" to "/update/apply", "POST" to "/update/check", "GET" to "/update/status", "POST" to "/mail/fanout",
            "POST" to "/hooks/abc", "GET" to "/mail/send", "POST" to "/window/activate", "GET" to "/plugins")) {
            assertEquals("demo: $m $p is disabled in this demo", lock.httpRefusal(m, p, false), "$m $p")
        }
    }
}
