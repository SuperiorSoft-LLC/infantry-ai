package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEMO-LOCK (ticket 2f154f5f): the allow-list decisions, for every token (the lock never looks at the tier). */
class DemoLockTest {
    private val a = "session-a"
    private val b = "session-b"
    private fun locked() = DemoLock(enabled = true, notice = "This is a demo.").also { it.adopt("project-demo", mapOf("backend" to a, "pm" to b)) }
    private fun mail(from: String, to: String, cross: Boolean = false) =
        Json.encodeToString(MailSendRequest.serializer(), MailSendRequest(from = from, to = to, body = "hi", allowCrossProject = cross))

    @Test
    fun `off lets everything through and adds nothing to ready`() {
        val off = DemoLock(false)
        assertNull(off.frameRefusal(Envelope(Envelope.PROJECT_SAVE, "{}"), null))
        assertNull(off.frameRefusal(Envelope("new", "{}"), null))
        assertNull(off.httpRefusal("POST", "/update/apply", false))
        assertNull(off.mailRefusal(MailSendRequest(from = "x", to = "y", body = "z")))
        assertEquals("", off.readyFields())
    }

    @Test
    fun `fromEnvironment is off unless AGENTHQ_DEMO_LOCK is set`() {
        assertFalse(DemoLock.fromEnvironment { null }.enabled)
        assertFalse(DemoLock.fromEnvironment { if (it == DemoLock.ENV) "0" else null }.enabled)
        val on = DemoLock.fromEnvironment { mapOf(DemoLock.ENV to "1", DemoLock.NOTICE_ENV to "N", DemoLock.SEATS_ENV to "qa:opencode:m1,pm:pi:m2")[it] }
        assertTrue(on.enabled)
        assertEquals("N", on.notice)
        assertEquals(listOf(DemoLock.Seat("qa", "opencode", "m1"), DemoLock.Seat("pm", "pi", "m2")), on.seats)
    }

    @Test
    fun `ready carries demo=lock and the notice base64url`() {
        val fields = locked().readyFields()
        assertTrue(fields.startsWith(" demo=lock demoNotice="))
        val encoded = fields.substringAfter("demoNotice=")
        assertEquals("This is a demo.", String(java.util.Base64.getUrlDecoder().decode(encoded)))
    }

    @Test
    fun `reads pass, writes and spawns are refused`() {
        val lock = locked()
        for (t in DemoLock.READ_FRAMES) assertNull(lock.frameRefusal(Envelope(t, ""), null), t)
        for (t in listOf("new", Envelope.PROJECT_SAVE, "settings_set", "ticket_save", "role_save", "agent_save", "secret_set",
            "harness_install", "cli_location_set", "session_delete", "image_upload", "pod_submit", "assistant_open", "anything_new")) {
            assertEquals(DemoLock.refusal(t), lock.frameRefusal(Envelope(t, "{}"), a), t)
        }
    }

    @Test
    fun `per-project reads pass only naming the demo project, the git checkout never`() {
        val lock = locked()
        val demo = """{"projectId":"project-demo"}"""
        for (t in DemoLock.PROJECT_READ_FRAMES) {
            assertNull(lock.frameRefusal(Envelope(t, demo), null), t)
            assertEquals(DemoLock.refusal(t), lock.frameRefusal(Envelope(t, """{"projectId":"project-other"}"""), null), t)
            assertEquals(DemoLock.refusal(t), lock.frameRefusal(Envelope(t, "{}"), null), t)
            assertEquals(DemoLock.refusal(t), lock.frameRefusal(Envelope(t, ""), null), t)
            assertEquals(DemoLock.refusal(t), lock.frameRefusal(Envelope(t, """{"projectId":["project-demo"]}"""), null), t)
        }
        // A payload that could name two projects (repeated key, or a \u escape spelling the key) is refused.
        val log = Envelope.PROJECT_GIT_LOG
        assertNotNull(lock.frameRefusal(Envelope(log, """{"projectId":"project-demo","projectId":"project-other"}"""), null))
        assertNotNull(lock.frameRefusal(Envelope(log, """{"projectId":"project-demo","project\u0049d":"project-other"}"""), null))
        assertNotNull(lock.frameRefusal(Envelope(log, """{"projectId":"project-\u0064emo"}"""), null))
        // The bare-id payloads: project_git_status, epic_list and the project-scope memory_list.
        assertNull(lock.frameRefusal(Envelope(Envelope.PROJECT_GIT_STATUS, "project-demo"), null))
        assertNull(lock.frameRefusal(Envelope(Envelope.EPIC_LIST, " project-demo "), null))
        assertNotNull(lock.frameRefusal(Envelope(Envelope.EPIC_LIST, "project-other"), null))
        assertNotNull(lock.frameRefusal(Envelope(Envelope.PROJECT_GIT_CHECKOUT, demo), null))
        assertNotNull(lock.frameRefusal(Envelope(Envelope.EPIC_SAVE, demo), null))
        assertNotNull(lock.frameRefusal(Envelope(Envelope.MEMORY_SAVE, demo), null))
        // Before the seed there is no demo project: nothing names it.
        assertNotNull(DemoLock(enabled = true).frameRefusal(Envelope(Envelope.EPIC_LIST, ""), null))
    }

    @Test
    fun `prompt and replay only for the two seeded sessions, interrupt and kill never`() {
        val lock = locked()
        assertNull(lock.frameRefusal(Envelope(Envelope.PROMPT, "hello", a), null))
        assertNull(lock.frameRefusal(Envelope(Envelope.PROMPT, "hello", b), null))
        assertNotNull(lock.frameRefusal(Envelope(Envelope.PROMPT, "hello"), null), "a prompt naming no session would create one")
        assertNotNull(lock.frameRefusal(Envelope(Envelope.PROMPT, "hello", "session-c"), null))
        assertNotNull(lock.frameRefusal(Envelope(Envelope.PROMPT, "/model x", a), null), "slash commands are refused")
        assertNull(lock.frameRefusal(Envelope(Envelope.RESUME, "$a\nhello"), null))
        assertNotNull(lock.frameRefusal(Envelope(Envelope.RESUME, "session-c\nhello"), null))
        assertNotNull(lock.frameRefusal(Envelope(Envelope.RESUME, "$a\n/model paid"), null))
        assertNotNull(lock.frameRefusal(Envelope(Envelope.INTERRUPT, a), null))
        assertNotNull(lock.frameRefusal(Envelope(Envelope.KILL, "$b force"), null))
        assertNotNull(lock.frameRefusal(Envelope(Envelope.KILL, "session-c"), null))
        assertNull(lock.frameRefusal(Envelope("replay_request", "{}", a), null))
        assertNotNull(lock.frameRefusal(Envelope("replay_request", "{}", "session-c"), null))
    }

    @Test
    fun `mail only between the two seeded sessions, by id or role`() {
        val lock = locked()
        assertNull(lock.frameRefusal(Envelope(Envelope.MAIL_SEND, mail("", b), a), null))
        assertNull(lock.frameRefusal(Envelope(Envelope.MAIL_SEND, mail("", "backend"), b), null))
        assertNull(lock.frameRefusal(Envelope(Envelope.MAIL_SEND, mail("", "pm"), null), active = a))
        assertNotNull(lock.frameRefusal(Envelope(Envelope.MAIL_SEND, mail("", a), a), null), "not to itself")
        assertNotNull(lock.frameRefusal(Envelope(Envelope.MAIL_SEND, mail("owner", a)), null), "not from the owner")
        assertNotNull(lock.frameRefusal(Envelope(Envelope.MAIL_SEND, mail("", "qa"), a), null), "not to another role")
        assertNotNull(lock.frameRefusal(Envelope(Envelope.MAIL_SEND, mail("", b, cross = true), a), null))
        assertNotNull(lock.frameRefusal(Envelope(Envelope.MAIL_SEND, "not json", a), null))
        assertNull(lock.mailRefusal(MailSendRequest(from = a, to = b, body = "x")))
        assertNotNull(lock.mailRefusal(MailSendRequest(from = "user", to = b, body = "x")))
    }

    @Test
    fun `http allows health, the socket, the bundle and the agent mail API only`() {
        val lock = locked()
        assertNull(lock.httpRefusal("GET", "/health", false))
        assertNull(lock.httpRefusal("GET", "/ws", false))
        assertNull(lock.httpRefusal("GET", "/attention", false), "the web bundle checks its token with it")
        assertNull(lock.httpRefusal("GET", "/app.js", true))
        assertNull(lock.httpRefusal("POST", "/mail/send", false))
        assertNull(lock.httpRefusal("GET", "/mail/thread/pair:a|b", false))
        for ((m, p) in listOf("POST" to "/update/apply", "POST" to "/update/check", "GET" to "/update/status", "POST" to "/mail/fanout",
            "POST" to "/hooks/abc", "GET" to "/mail/send", "POST" to "/window/activate")) {
            assertNotNull(lock.httpRefusal(m, p, false), "$m $p")
        }
    }
}
