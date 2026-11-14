// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.credential

import com.superiorsoftllc.infantry.keystore.Keystore
import com.superiorsoftllc.infantry.keystore.KeystoreHandler
import com.superiorsoftllc.infantry.keystore.KnownSecret
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ONBOARD-1: the in-app harness sign-in (the first-run "Log in with your subscription"), over a fake CLI. */
class HarnessLoginsTest {
    /** A login CLI the test drives: what it printed, what it was fed, and how it ends. */
    private class FakeLogin : ProcessRunner, RunningProcess {
        override val id = "fake"
        var spec: ProcessSpec? = null
        lateinit var listener: ProcessListener
        val fed = mutableListOf<String>()
        var alive = true
        var killed = false
        var printOnStart: List<String> = emptyList()
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            this.spec = spec; this.listener = listener
            printOnStart.forEach(listener::onStdout) // before start() returns, as a fast CLI does
            return this
        }
        override val isAlive get() = alive
        override fun writeInput(text: String) { fed += text }
        override fun closeInput() {}
        override fun killTree() { killed = true; alive = false }
        override fun awaitExit() = 0
        fun exit(code: Int) { alive = false; listener.onExit(code) }
    }

    private val claude = HarnessCredential("claude", "ANTHROPIC_API_KEY", "Claude Code", authModes = listOf("api-key", "subscription-login"), probeArgv = listOf("claude", "auth", "status"), loginArgv = listOf("claude", "auth", "login"))
    private val link = "https://claude.com/cai/oauth/authorize?code=true&client_id=abc&state=xyz"

    private fun logins(fake: FakeLogin, signedIn: Boolean? = true, changes: MutableList<String> = mutableListOf()): HarnessLogins {
        val ks = Keystore(Files.createTempDirectory("login-test").toFile(), env = { null }, vault = null)
        lateinit var l: HarnessLogins
        l = HarnessLogins(HarnessCredentials(ks) { listOf(claude) }, verify = { signedIn }, runner = fake, windows = false, onChange = { changes += l.state("claude").state })
        return l
    }

    @Test
    fun `start runs the declared login with the key blanked and shows the link the CLI printed, terminal escapes removed`() {
        val fake = FakeLogin().apply { printOnStart = listOf("Opening browser to sign in…", "If the browser didn't open, visit: \u001B]8;;$link\u0007$link\u001B]8;;\u0007") }
        val l = logins(fake)
        l.start("claude")
        assertEquals(listOf("claude", "auth", "login"), fake.spec!!.command)
        assertEquals(mapOf("ANTHROPIC_API_KEY" to ""), fake.spec!!.env, "a subscription login must never see an API key")
        assertEquals(HarnessLoginState.RUNNING, l.state("claude").state)
        assertEquals(link, l.state("claude").url)
    }

    @Test
    fun `a pasted code goes to the CLI's stdin as one line, and a clean exit that the probe confirms is done`() {
        val fake = FakeLogin()
        val changes = mutableListOf<String>()
        val l = logins(fake, changes = changes)
        l.start("claude")
        l.input("claude", "  abc123#state456\r\n")
        assertEquals(listOf("abc123#state456\n"), fake.fed)
        fake.listener.onStdout("Login successful.")
        fake.exit(0)
        assertEquals(HarnessLoginState.DONE, l.state("claude").state)
        assertTrue(HarnessLoginState.DONE in changes, "every change is pushed")
    }

    @Test
    fun `a refused code is shown while the login still waits, and a failed exit says why`() {
        val fake = FakeLogin()
        val l = logins(fake)
        l.start("claude")
        fake.listener.onStderr("Invalid code. Please make sure the full code was copied.")
        assertEquals(HarnessLoginState.RUNNING, l.state("claude").state)
        assertTrue(l.state("claude").message.startsWith("Invalid code"))
        fake.listener.onStderr("Login failed: the code was refused")
        fake.exit(1)
        assertEquals(HarnessLoginState.FAILED, l.state("claude").state)
        assertEquals("Login failed: the code was refused", l.state("claude").message)
    }

    @Test
    fun `exit 0 that the probe says is still signed out is a failure, not a false done`() {
        val fake = FakeLogin()
        val l = logins(fake, signedIn = false)
        l.start("claude")
        fake.exit(0)
        assertEquals(HarnessLoginState.FAILED, l.state("claude").state)
    }

    @Test
    fun `cancel kills the CLI and forgets the state, input with none running is refused, a harness without a login is refused`() {
        val fake = FakeLogin()
        val l = logins(fake)
        l.start("claude")
        l.cancel("claude")
        assertTrue(fake.killed)
        assertEquals(HarnessLoginState.NONE, l.state("claude"))
        fake.exit(1) // the killed CLI's exit after the cancel changes nothing
        assertEquals(HarnessLoginState.NONE, l.state("claude"))
        assertFailsWith<IllegalArgumentException> { l.input("claude", "abc#def") }
        val ks = Keystore(Files.createTempDirectory("login-test").toFile(), env = { null }, vault = null)
        val none = HarnessLogins(HarnessCredentials(ks) { listOf(claude.copy(loginArgv = emptyList())) }, runner = FakeLogin(), windows = false)
        assertFailsWith<IllegalArgumentException> { none.start("claude") }
    }

    @Test
    fun `on Windows the npm shim is started through cmd`() {
        val fake = FakeLogin()
        val ks = Keystore(Files.createTempDirectory("login-test").toFile(), env = { null }, vault = null)
        HarnessLogins(HarnessCredentials(ks) { listOf(claude) }, runner = fake, windows = true).start("claude")
        assertEquals(listOf("cmd", "/c", "claude", "auth", "login"), fake.spec!!.command)
    }

    @Test
    fun `secret_login frame routes to the login, refuses a key without one, never echoes the code, and rows carry the state`() {
        val ks = Keystore(Files.createTempDirectory("login-test").toFile(), env = { null }, vault = null)
        val calls = mutableListOf<String>()
        val state = HarnessLoginState(HarnessLoginState.RUNNING, link, "")
        val h = KeystoreHandler(
            ks, env = { null },
            extraKnown = { listOf(KnownSecret("harness.claude.key", "Claude Code API key", canLogin = true, login = { state }), KnownSecret("harness.pi.key", "Pi API key")) },
            login = { name, action, text -> calls += "$name $action ${text.length}" },
        )
        val code = "secretcode123#state"
        val reply = h.answer(Envelope(Envelope.SECRET_LOGIN, """{"name":"harness.claude.key","action":"input","text":"$code"}"""))!!
        assertEquals(Envelope.SECRET_STATE, reply.type)
        assertFalse(reply.data.contains(code))
        assertEquals(listOf("harness.claude.key input ${code.length}"), calls)
        assertTrue(reply.data.contains(""""loginState":"running""""))
        assertTrue(reply.data.contains(""""canLogin":true"""))
        assertEquals(Envelope.ERROR, h.answer(Envelope(Envelope.SECRET_LOGIN, """{"name":"harness.pi.key","action":"start"}"""))!!.type)
        assertEquals(Envelope.ERROR, h.answer(Envelope(Envelope.SECRET_LOGIN, """{"name":"harness.claude.key","action":"rm"}"""))!!.type)
        val locked = KeystoreHandler(ks, env = { null }, restricted = { true }, extraKnown = { listOf(KnownSecret("harness.claude.key", "x", canLogin = true)) })
        assertEquals(Envelope.ERROR, locked.answer(Envelope(Envelope.SECRET_LOGIN, """{"name":"harness.claude.key","action":"start"}"""))!!.type)
    }
}
