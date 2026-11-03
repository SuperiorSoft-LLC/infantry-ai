// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.persistence.AgentRepository
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.MessageOrigin
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.settings.SettingsService
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MAIL-TRUNC-1: what a mail wake injects per mail.deliveryMode. */
class MailDeliveryModeTest {
    private class Repo : AgentRepository {
        private val p = mutableMapOf<String?, AgentProfile>()
        override fun save(profile: AgentProfile, at: Long) { p[profile.id] = profile }
        override fun get(id: String) = p[id]
        override fun all() = p.values.toList()
        override fun delete(id: String) = p.remove(id) != null
    }

    private fun router(max: Int = 50, total: Int = 400): MailRouter {
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(), tempSessionStore(), killGraceMs = 50)
        return MailRouter(manager, Mailbox(MemMailStore(), MailLimits()), AgentProfiles(Repo(), manager),
            injectMaxChars = { max }, injectTotalChars = { total })
    }

    private fun msg(id: String, body: String, subject: String = "subj $id", from: String = "worker") =
        Message(id = id, threadId = "t", seq = 1, from = from, to = "pm", subject = subject, body = body, sentAt = 1L)

    private fun src(m: Message) = MailSource(m, MessageOrigin.Owner)
    private fun render(r: MailRouter, mode: String, vararg ms: Message) =
        r.renderTurn(mode, ms.toList(), src(ms[0])) { src(it) }

    @Test
    fun `notify lists count, one line per mail and the read command, no body`() {
        val out = render(router(), "notify", msg("aaaaaaaa1111", "SECRETBODY"), msg("bbbbbbbb2222", "OTHERBODY"), msg("cccccccc3333", "x"))
        assertTrue(out.startsWith("NEW MAIL (3): read with `infantry inbox` / `infantry read <id>`"), out)
        assertTrue("infantry_read" in out)
        assertTrue("- aaaaaaaa from worker: subj aaaaaaaa1111" in out, out)
        assertEquals(3, out.lines().count { it.startsWith("- ") })
        assertFalse("SECRETBODY" in out || "OTHERBODY" in out)
    }

    @Test
    fun `truncate cuts long bodies with a marker and keeps short ones`() {
        val long = "y".repeat(120)
        val out = render(router(max = 50, total = 100000), "truncate", msg("aaaaaaaa1111", long), msg("bbbbbbbb2222", "short body"))
        assertTrue("y".repeat(50) + "\n... [70 more chars: infantry read aaaaaaaa]" in out, out)
        assertFalse("y".repeat(51) in out)
        assertTrue("short body" in out && "more chars: infantry read bbbbbbbb" !in out)
        assertTrue("subj aaaaaaaa1111" in out, "from-line and subject kept")
    }

    @Test
    fun `truncate caps the whole turn and lists the rest as notify lines`() {
        val ms = (1..6).map { msg("id${it}xxxxxxxx", "b".repeat(40)) }
        val out = render(router(max = 50, total = 400), "truncate", *ms.toTypedArray())
        assertTrue("NEW MAIL (" in out, out)
        assertTrue(out.indexOf("NEW MAIL (") > 0)
    }

    @Test
    fun `full is unchanged`() {
        val a = msg("aaaaaaaa1111", "z".repeat(5000)); val b = msg("bbbbbbbb2222", "second")
        val out = render(router(max = 10), "full", a, b)
        assertEquals(src(a).render() + "\n\n---\n\n" + src(b).render(), out)
    }

    @Test
    fun `deliveryMode resolves session over role over global, default notify`() {
        val dir: File = Files.createTempDirectory("infantry-mt1").toFile()
        val s = SettingsService(SqlInfantryStore.open(File(dir, "infantry.db").absolutePath))
        assertEquals("notify", s.deliveryMode("pm", "s1"))
        s.set("mail.deliveryMode", JsonPrimitive("truncate"))
        s.set("mail.deliveryMode.pm", JsonPrimitive("full"))
        s.set("mail.deliveryMode.s1", JsonPrimitive("notify"))
        assertEquals("notify", s.deliveryMode("pm", "s1"))
        assertEquals("full", s.deliveryMode("pm", "s2"))
        assertEquals("truncate", s.deliveryMode("backend", "s2"))
        assertFailsWith<IllegalArgumentException> { s.set("mail.deliveryMode", JsonPrimitive("bogus")) }
    }
}
