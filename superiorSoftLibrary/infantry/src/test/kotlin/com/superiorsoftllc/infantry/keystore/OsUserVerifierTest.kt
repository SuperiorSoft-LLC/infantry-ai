// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.keystore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** VAULT-REVEAL-1: the verifier port's platform pick and the Windows helper's one-line answers. Never shows a prompt. */
class OsUserVerifierTest {
    @Test
    fun `the helper's lines parse`() {
        assertEquals(Verification(Verification.Outcome.VERIFIED, "windows-hello"), OsUserVerifiers.parse("verified:windows-hello\n"))
        assertEquals(Verification(Verification.Outcome.VERIFIED, "windows-password"), OsUserVerifiers.parse("noise\r\nverified:windows-password\r\n"))
        assertEquals(Verification(Verification.Outcome.DENIED, "windows-hello", "Canceled"), OsUserVerifiers.parse("denied:windows-hello:Canceled"))
        assertEquals(Verification.Outcome.UNAVAILABLE, OsUserVerifiers.parse("unavailable:no desktop").outcome)
        assertEquals(Verification.Outcome.UNAVAILABLE, OsUserVerifiers.parse("").outcome)
        assertEquals(Verification.Outcome.UNAVAILABLE, OsUserVerifiers.parse("Verified").outcome, "only the exact protocol verifies")
    }

    @Test
    fun `windows gets the Windows verifier, macOS and Linux say not available yet`() {
        assertIs<OsUserVerifiers.Windows>(OsUserVerifiers.detect("windows 11"))
        for (os in listOf("mac os x", "linux")) {
            val v = OsUserVerifiers.detect(os)
            assertIs<OsUserVerifiers.NotYet>(v)
            assertFalse(v.verify("x").verified)
            assertTrue("not available" in v.check().detail)
        }
    }

    @Test
    fun `the helper script ships as a resource`() {
        val text = OsUserVerifiers::class.java.getResourceAsStream("/com/superiorsoftllc/infantry/keystore/os-user-verify.ps1")!!.use { it.readBytes().decodeToString() }
        assertTrue("UserConsentVerifier" in text && "CredUIPromptForWindowsCredentials" in text)
    }
}
