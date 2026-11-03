// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteAccessTest {
    private fun dir() = Files.createTempDirectory("infantry-remote-").toFile()
    private val noEnv: (String) -> String? = { null }

    @Test
    fun `no file means loopback and no token`() {
        val d = dir()
        assertEquals("127.0.0.1", RemoteAccess.resolveHost(d, noEnv))
        assertNull(RemoteAccess.resolveToken(d, noEnv))
        assertNull(RemoteAccess.clientToken(getenv = { if (it == "INFANTRY_STATE_DIR") d.path else null }))
    }

    @Test
    fun `the test JVM default state dir is isolated from the real home`() {
        val real = java.io.File(System.getProperty("user.home"), ".infantry").canonicalFile
        val state = com.superiorsoftllc.infantry.session.stateDir().canonicalFile
        assertNotEquals(real, state)
        // remote=true written ONLY into the injected state dir is what a default build()/CLI would pick up; the
        // real dir (which may hold the owner's remote=true) must not be consulted.
        RemoteAccess.save(state, RemoteAccess.Settings(remote = true))
        try {
            assertEquals("0.0.0.0", RemoteAccess.resolveHost(state, noEnv))
        } finally {
            RemoteAccess.settingsFile(state).delete()
            RemoteAccess.tokenFile(state).delete()
        }
        assertNull(RemoteAccess.clientToken(noEnv))
    }

    @Test
    fun `settings parse`() {
        val d = dir()
        RemoteAccess.settingsFile(d).writeText("remote=true\nhost=100.64.1.2\nport=8788\n")
        assertEquals(RemoteAccess.Settings(true, "100.64.1.2", 8788), RemoteAccess.load(d))
        RemoteAccess.settingsFile(d).writeText("remote=maybe\nport=zzz\n")
        assertEquals(RemoteAccess.Settings(false, null, null), RemoteAccess.load(d))
    }

    @Test
    fun `remote false stays loopback`() {
        val d = dir()
        RemoteAccess.save(d, RemoteAccess.Settings(remote = false, host = "0.0.0.0"))
        assertEquals("127.0.0.1", RemoteAccess.resolveHost(d, noEnv))
        assertNull(RemoteAccess.resolveToken(d, noEnv))
    }

    @Test
    fun `remote true generates a token once and reuses it`() {
        val d = dir()
        RemoteAccess.save(d, RemoteAccess.Settings(remote = true))
        assertEquals("0.0.0.0", RemoteAccess.resolveHost(d, noEnv))
        val first = RemoteAccess.resolveToken(d, noEnv)!!
        assertTrue(first.length >= 40)
        assertEquals(first, RemoteAccess.resolveToken(d, noEnv))
        assertEquals(first, RemoteAccess.clientToken(getenv = { if (it == "INFANTRY_STATE_DIR") d.path else null }))
        // the generated token and the wildcard host satisfy the unchanged AccessPolicy
        AccessPolicy.of(RemoteAccess.resolveHost(d, noEnv), first)
    }

    @Test
    fun `environment overrides the file`() {
        val d = dir()
        RemoteAccess.save(d, RemoteAccess.Settings(remote = true, host = "100.64.1.2"))
        val env = mapOf("INFANTRY_HOST" to "127.0.0.1", "INFANTRY_TOKEN" to "x".repeat(40))
        assertEquals("127.0.0.1", RemoteAccess.resolveHost(d) { env[it] })
        assertEquals("x".repeat(40), RemoteAccess.resolveToken(d, getenv = { env[it] }))
        assertNull(RemoteAccess.storedToken(d), "an env token generates nothing")
    }

    @Test
    fun `policy still refuses a non-loopback bind without a token`() {
        assertFailsWith<AccessConfigurationException> { AccessPolicy.of("0.0.0.0", null) }
    }

    @Test
    fun `tailscale range detection`() {
        assertTrue(RemoteAccess.isTailscaleAddress("100.101.2.3"))
        assertTrue(!RemoteAccess.isTailscaleAddress("100.128.0.1"))
        assertTrue(!RemoteAccess.isTailscaleAddress("192.168.1.2"))
        assertNotEquals(RemoteAccess.ensureToken(dir()), RemoteAccess.ensureToken(dir()))
    }
}
