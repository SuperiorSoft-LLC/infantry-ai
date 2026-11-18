// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.shell.LinkReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** VAULT-REVEAL-1: a copied value leaves the clipboard after the daemon's clear time, unless something else was copied since. */
class SecretClipboardTest {
    private class FakeClip { var text: String? = "" }

    @Test
    fun `the copied value is cleared after the delay`() = runBlocking {
        val clip = FakeClip()
        val c = SecretClipboard(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        c.copy("sk-copied", 50, read = { clip.text }, write = { clip.text = it })
        assertEquals("sk-copied", clip.text)
        delay(400)
        assertEquals("", clip.text)
    }

    @Test
    fun `something copied since is left alone`() = runBlocking {
        val clip = FakeClip()
        val c = SecretClipboard(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        c.copy("sk-copied", 50, read = { clip.text }, write = { clip.text = it })
        clip.text = "the user's own copy"
        delay(400)
        assertEquals("the user's own copy", clip.text)
    }

    @Test
    fun `a newer copy restarts the timer`() = runBlocking {
        val clip = FakeClip()
        val c = SecretClipboard(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        c.copy("first", 50, read = { clip.text }, write = { clip.text = it })
        c.copy("second", 300, read = { clip.text }, write = { clip.text = it })
        delay(150)
        assertEquals("second", clip.text)
        delay(500)
        assertEquals("", clip.text)
    }

    @Test
    fun `refusals and answers decode without the value in toString`() {
        val refused = SecretVaultWire.result(SecretVaultWire.REVEAL, LinkReply("error", "secret_reveal refused: Windows Hello verification was not passed (Canceled)"), SecretVaultWire::revealed)
        assertTrue(refused.isFailure)
        assertEquals("Windows Hello verification was not passed (Canceled)", refused.exceptionOrNull()!!.message)
        val ok = SecretVaultWire.result(SecretVaultWire.REVEAL, LinkReply("secret_reveal", """{"name":"A","field":"value","value":"sk-x","method":"windows-hello","showSeconds":5,"clearSeconds":7}"""), SecretVaultWire::revealed)
        val v = ok.getOrThrow()
        assertEquals("sk-x", v.value)
        assertEquals(7, v.clearSeconds)
        assertFalse("sk-x" in v.toString())
        val imported = SecretVaultWire.result(SecretVaultWire.IMPORT, LinkReply("secret_backup_import", """{"applied":false,"conflicts":["A","p/B"]}"""), SecretVaultWire::imported).getOrThrow()
        assertFalse(imported.applied)
        assertEquals(listOf("A", "p/B"), imported.conflicts)
    }
}
