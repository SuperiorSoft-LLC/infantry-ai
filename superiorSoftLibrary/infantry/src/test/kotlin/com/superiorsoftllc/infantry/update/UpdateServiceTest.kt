// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** UPDATE-1: the flow the window renders, against the local fake GitHub ([FakeGitHub]). */
class UpdateServiceTest {
    private val gh = FakeGitHub()
    private val scope = CoroutineScope(SupervisorJob())
    private val handedOff = mutableListOf<VerifiedInstaller>()
    private var exits = 0

    @AfterTest
    fun cleanup() {
        scope.cancel()
        gh.stop()
    }

    private fun service(current: String = "1.0.3", settings: UpdateSettings? = null) = UpdateService(
        settings ?: UpdateSettings.Enabled(UpdateConfig("superiorsoft/agenthq", SemVer.parse(current)!!, gh.base)),
        scope,
        updaterFor = { config -> Updater(config) },
        handoff = { handedOff += it },
        exit = { exits++ },
        log = {},
    )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a startup check that finds nothing newer stays silent, a manual one says so`() = runBlocking<Unit> {
        gh.release("v1.0.3", msi = "AgentHQ-1.0.3.msi" to "x".toByteArray())
        val s = service()
        s.check(manual = false)!!.join()
        assertEquals(UpdateState.Idle, s.state.value)
        s.check(manual = true)!!.join()
        assertIs<UpdateState.UpToDate>(s.state.value)
    }

    @Test
    fun `Update downloads, verifies, hands the installer over and exits`() = runBlocking<Unit> {
        val bytes = "verified 1.0.4".toByteArray()
        gh.release("v1.0.4", msi = "AgentHQ-1.0.4.msi" to bytes, sha256 = sha256(bytes))
        val s = service()
        s.check(manual = false)!!.join()
        assertIs<UpdateState.Available>(s.state.value)
        s.update()!!.join()
        assertIs<UpdateState.Restarting>(s.state.value)
        assertEquals(1, handedOff.size)
        assertTrue(handedOff.single().checksumVerified)
        assertEquals(1, exits)
        handedOff.single().file.parentFile.deleteRecursively()
    }

    @Test
    fun `a checksum mismatch is refused - no handoff, no exit, a short message`() = runBlocking<Unit> {
        gh.release("v1.0.4", msi = "AgentHQ-1.0.4.msi" to "evil".toByteArray(), sha256 = sha256("good".toByteArray()))
        val s = service()
        s.check(manual = false)!!.join()
        s.update()!!.join()
        val message = assertIs<UpdateState.Message>(s.state.value)
        assertTrue("SHA-256 mismatch" in message.text, message.text)
        assertEquals(0, handedOff.size)
        assertEquals(0, exits)
    }

    @Test
    fun `a dev build says updates are off when checked by hand, and never asks GitHub`() {
        val s = service(settings = UpdateSettings.resolve(installed = false, version = "1.0.0", stateDir = Files.createTempDirectory("u").toFile(), setting = { null }))
        assertEquals(null, s.check(manual = false))
        assertEquals(UpdateState.Idle, s.state.value)
        s.check(manual = true)
        assertTrue((s.state.value as UpdateState.Message).text.startsWith("Updates are off"))
        assertTrue(gh.requests.isEmpty())
    }
}
