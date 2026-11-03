// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import io.ktor.http.HttpStatusCode
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The access decisions, with no sockets: what is admitted, what is refused, and why. The same decisions
 * arriving over a real socket, with the status a client actually sees, are pinned in [RemoteDaemonTest].
 */
class AccessPolicyTest {

    private val goodToken = "correct-horse-battery-staple-0123456789"

    // ----- P9.6: signature-admitted webhook route ------------------------------------------------

    @Test
    fun aTokenGatedDaemonAdmitsTheHookRouteWithoutTheBearerButNothingElse() {
        val policy = AccessPolicy.of("0.0.0.0", goodToken)
        assertNull(policy.check("/hooks/trg-1a2b3c4d", "10.0.0.5:8787", origin = null, authorization = null), "hook route is signature-admitted")
        for (path in listOf("/mail/send", "/attention", "/ws", "/hooks", "/hooks/")) {
            assertEquals(HttpStatusCode.Unauthorized, policy.check(path, "10.0.0.5:8787", null, null)?.status, path)
        }
    }

    @Test
    fun theHookExemptionCannotBeBorrowedByTraversalNestingOrEncoding() {
        val policy = AccessPolicy.of("0.0.0.0", goodToken)
        val paths = listOf(
            "/hooks/../mail/send", "/hooks/..%2Fmail%2Fsend", "/hooks/a/b", "/hooks/a/../../mail/send",
            "/hooks/.", "/hooks/%2e%2e", "/hooks//mail/send", "/hooks/x;y", "/hooks/" + "a".repeat(65),
            "/HOOKS/trg-1", "/x/hooks/trg-1", "/hooks/trg-1/",
        )
        for (path in paths) {
            assertEquals(HttpStatusCode.Unauthorized, policy.check(path, "10.0.0.5:8787", null, null)?.status, path)
        }
    }

    @Test
    fun theHookRouteStillRefusesAnOriginHeaderAndAWrongHostWhenThereIsNoToken() {
        val tokenPolicy = AccessPolicy.of("0.0.0.0", goodToken)
        assertEquals(HttpStatusCode.Forbidden, tokenPolicy.check("/hooks/trg-1", "h:1", origin = "https://evil.example", authorization = null)?.status)
        val loopback = AccessPolicy.of("127.0.0.1", token = null)
        assertEquals(HttpStatusCode.Forbidden, loopback.check("/hooks/trg-1", "evil.example", null, null)?.status)
        assertNull(loopback.check("/hooks/trg-1", "127.0.0.1:8787", null, null))
    }

    // ----- startup configuration -------------------------------------------------------------------

    @Test
    fun loopbackWithoutATokenIsAdmitted() {
        for (host in listOf("127.0.0.1", "localhost", "::1", "[::1]")) {
            val policy = AccessPolicy.of(host, token = null)
            assertFalse(policy.tokenRequired, host)
            assertTrue(policy.loopbackBind, host)
        }
    }

    @Test
    fun aNonLoopbackBindWithoutATokenIsRefused() {
        for (host in listOf("0.0.0.0", "::", "192.168.1.20", "10.0.0.5", "", "   ")) {
            val error = assertFailsWith<AccessConfigurationException> { AccessPolicy.of(host, token = null) }
            assertTrue(error.message!!.contains("without a bearer token"), "host '$host': ${error.message}")
        }
    }

    @Test
    fun aTokenMayAccompanyAnyBindAddress() {
        assertTrue(AccessPolicy.of("0.0.0.0", goodToken).tokenRequired)
        assertTrue(AccessPolicy.of("127.0.0.1", goodToken).tokenRequired)
    }

    @Test
    fun aTokenMustBeLongEnoughAndFreeOfWhitespaceAndControlCharacters() {
        assertFailsWith<AccessConfigurationException> { AccessPolicy.of("127.0.0.1", "too-short") }
        assertFailsWith<AccessConfigurationException> { AccessPolicy.of("127.0.0.1", "x".repeat(MIN_TOKEN_LENGTH - 1)) }
        assertTrue(AccessPolicy.of("127.0.0.1", "x".repeat(MIN_TOKEN_LENGTH)).tokenRequired)
        assertFailsWith<AccessConfigurationException> { AccessPolicy.of("127.0.0.1", "x".repeat(40) + " ") }
        assertFailsWith<AccessConfigurationException> {
            AccessPolicy.of("127.0.0.1", "x".repeat(20) + "\n" + "y".repeat(20))
        }
        assertFailsWith<AccessConfigurationException> {
            AccessPolicy.of("127.0.0.1", "x".repeat(20) + "\u0007" + "y".repeat(20))
        }
    }

    // ----- the decisions ----------------------------------------------------------------------------

    @Test
    fun originIsRefusedInEveryConfigurationEvenWithTheRightToken() {
        val loopback = AccessPolicy.of("127.0.0.1", token = null)
        val tokened = AccessPolicy.of("0.0.0.0", goodToken)
        for (policy in listOf(loopback, tokened)) {
            val refusal = policy.check(
                "/ws",
                host = "127.0.0.1:8787",
                origin = "https://evil.example",
                authorization = "Bearer $goodToken",
            )
            assertNotNull(refusal, "a request carrying an Origin must never be admitted")
            assertEquals(HttpStatusCode.Forbidden, refusal.status)
        }
    }

    @Test
    fun loopbackModeAdmitsOnlyLoopbackHostNames() {
        val policy = AccessPolicy.of("127.0.0.1", token = null)
        for (host in listOf("127.0.0.1:8787", "localhost:8787", "LOCALHOST", "[::1]:8787", "::1")) {
            assertNull(policy.check("/ws", host, origin = null, authorization = null), "host '$host' should be admitted")
        }
        // A DNS-rebinding page reaches this socket under ITS OWN name, so that name must be refused.
        for (host in listOf("evil.example:8787", "10.0.0.5:8787", "127.0.0.1.evil.example:8787", "", null)) {
            val status = policy.check("/ws", host, origin = null, authorization = null)?.status
            assertEquals(HttpStatusCode.Forbidden, status, "host '$host' should be refused")
        }
    }

    @Test
    fun aTokenConfiguredDaemonLeavesOnlyHealthOpen() {
        val policy = AccessPolicy.of("0.0.0.0", goodToken)
        assertNull(policy.check("/health", "10.0.0.5:8787", origin = null, authorization = null), "/health is open")
        for (path in listOf("/ws", "/mail/send", "/mail/read", "/anything")) {
            val refusal = policy.check(path, "10.0.0.5:8787", origin = null, authorization = null)
            assertEquals(HttpStatusCode.Unauthorized, refusal?.status, path)
            assertTrue(refusal!!.challengeBearer, "$path must challenge for a bearer token")
        }
        assertNull(policy.check("/ws", "10.0.0.5:8787", origin = null, authorization = "Bearer $goodToken"))
    }

    @Test
    fun theBearerSchemeAndCredentialAreCheckedStrictly() {
        val policy = AccessPolicy.of("0.0.0.0", goodToken)
        val refused = listOf(
            null,
            "",
            "Bearer",
            "Bearer ",
            "Bearer wrong-$goodToken",
            "Basic $goodToken",
            "Token $goodToken",
            goodToken,
            "Bearer ${goodToken.dropLast(1)}",
            "Bearer $goodToken-extra",
        )
        for (header in refused) {
            val status = policy.check("/ws", "h", null, header)?.status
            assertEquals(HttpStatusCode.Unauthorized, status, "header '$header'")
        }
        // The scheme name is case-insensitive (RFC 7235); the credential itself is exact.
        assertNull(policy.check("/ws", "h", null, "bearer $goodToken"))
        assertNull(policy.check("/ws", "h", null, "  Bearer   $goodToken  "))
    }

    @Test
    fun describeNamesWhatAdmitsACallerWithoutRevealingTheToken() {
        val tokened = AccessPolicy.of("0.0.0.0", goodToken).describe()
        assertTrue(tokened.contains("bearer token required"), tokened)
        assertFalse(tokened.contains(goodToken), "the startup banner must never print the token")
        assertTrue(AccessPolicy.of("127.0.0.1", token = null).describe().contains("loopback only"))
    }

    // ----- host classification ----------------------------------------------------------------------

    @Test
    fun aBlankHostIsNeverLoopbackAndIpv6LiteralsAreRecognised() {
        assertFalse(AccessPolicy.isLoopbackHost(""))
        assertFalse(AccessPolicy.isLoopbackHost("   "))
        assertFalse(AccessPolicy.isLoopbackHost("0.0.0.0"))
        assertFalse(AccessPolicy.isLoopbackHost("::"))
        assertFalse(AccessPolicy.isLoopbackHost("10.0.0.5"))
        assertTrue(AccessPolicy.isLoopbackHost("127.0.0.1"))
        assertTrue(AccessPolicy.isLoopbackHost("LocalHost"))
        assertTrue(AccessPolicy.isLoopbackHost("::1"))
        assertTrue(AccessPolicy.isLoopbackHost("[::1]"))
    }

    @Test
    fun agentsDialTheAddressTheDaemonIsActuallyListeningOn() {
        assertEquals("127.0.0.1", AccessPolicy.agentReachableHost(""))
        assertEquals("127.0.0.1", AccessPolicy.agentReachableHost("0.0.0.0"))
        assertEquals("127.0.0.1", AccessPolicy.agentReachableHost("::"))
        assertEquals("127.0.0.1", AccessPolicy.agentReachableHost("127.0.0.1"))
        assertEquals("10.0.0.5", AccessPolicy.agentReachableHost("10.0.0.5"))
        assertEquals("[fe80::1]", AccessPolicy.agentReachableHost("fe80::1"))
        assertEquals("[fe80::1]", AccessPolicy.agentReachableHost("[fe80::1]"))
    }

    // ----- token sources ----------------------------------------------------------------------------

    @Test
    fun aTokenFileWithAByteOrderMarkAndTrailingNewlineYieldsTheBareToken() {
        val file = Files.createTempFile("infantry-token", ".txt").toFile()
        try {
            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            file.writeBytes(bom + (goodToken + "\r\n").toByteArray(Charsets.UTF_8))
            assertEquals(goodToken, AccessPolicy.readTokenFile(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun aMissingOrEmptyTokenFileIsAConfigurationErrorThatNamesTheFile() {
        val missing = File(Files.createTempDirectory("infantry-token").toFile(), "absent.txt")
        val missingError = assertFailsWith<AccessConfigurationException> { AccessPolicy.readTokenFile(missing) }
        assertTrue(missingError.message!!.contains(missing.path), missingError.message)

        val empty = Files.createTempFile("infantry-token", ".txt").toFile()
        try {
            empty.writeText("  \r\n")
            val emptyError = assertFailsWith<AccessConfigurationException> { AccessPolicy.readTokenFile(empty) }
            assertTrue(emptyError.message!!.contains("is empty"), emptyError.message)
        } finally {
            empty.delete()
        }
    }

    @Test
    fun theEnvironmentSuppliesOneTokenSourceAndRefusesTwo() {
        val file = Files.createTempFile("infantry-token", ".txt").toFile()
        try {
            file.writeText(goodToken)
            val fromFile = AccessPolicy.tokenFromEnvironment { name ->
                if (name == AccessPolicy.TOKEN_FILE_ENV) file.path else null
            }
            assertEquals(goodToken, fromFile)

            val fromVariable = AccessPolicy.tokenFromEnvironment { name ->
                if (name == AccessPolicy.TOKEN_ENV) "  $goodToken  " else null
            }
            assertEquals(goodToken, fromVariable)

            assertNull(AccessPolicy.tokenFromEnvironment { null })

            // A blank file variable is not "set": the direct variable is the one in use.
            val blankFile = AccessPolicy.tokenFromEnvironment { name ->
                when (name) {
                    AccessPolicy.TOKEN_FILE_ENV -> "   "
                    AccessPolicy.TOKEN_ENV -> goodToken
                    else -> null
                }
            }
            assertEquals(goodToken, blankFile)

            val both = assertFailsWith<AccessConfigurationException> {
                AccessPolicy.tokenFromEnvironment { name ->
                    if (name == AccessPolicy.TOKEN_FILE_ENV) file.path else goodToken
                }
            }
            assertTrue(both.message!!.contains("not both"), both.message)
        } finally {
            file.delete()
        }
    }
}
