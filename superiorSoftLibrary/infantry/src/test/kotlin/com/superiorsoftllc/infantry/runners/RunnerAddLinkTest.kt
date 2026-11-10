// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.runners.LinkAnswer
import com.superiorsoftllc.infantry.daemon.api.runners.LinkFailure
import com.superiorsoftllc.infantry.daemon.api.runners.LinkHandler
import com.superiorsoftllc.infantry.daemon.api.runners.LinkServing
import com.superiorsoftllc.infantry.daemon.api.runners.LinkTlsMaterial
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkTransport
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-ADD-LINK-1 (docs/wiki/RunnerPairing-design.md, step 2): `infantry runner add '<link>'` tries every host of the link in
 * order with the pinned certificate, skips a host that does not answer or whose certificate is not the pinned one (no code is ever
 * sent there), stops at the first PAIR refusal, and turns `runners.enabled` on at the first successful add.
 */
class RunnerAddLinkTest {
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val store = RunnerLinkStore(LinkVault.InMemory())
    private val link = RunnerPairLink(
        name = "t1",
        hosts = listOf("10.255.255.1", "127.0.0.1"),
        port = 8790,
        code = "ABCD-EFGH-JKLM-NPQR",
        fingerprint = "abababababababababababababababababababababababababababababababab",
    )

    /** What one host of the link does when the transport reaches it. */
    private sealed interface Reached {
        /** Nothing listening (a timeout or a refused connection): no code sent. */
        class Unreachable(val timedOut: Boolean = false) : Reached

        /** The certificate is not the pinned one: the handshake refuses, so no code sent. */
        object WrongCertificate : Reached

        /** PAIR arrived and the runner said no (wrong, expired or burned code; another orchestrator). */
        class Refused(val reason: String) : Reached

        /** PAIR arrived and the runner paired. */
        object Paired : Reached
    }

    /** A [LinkCall] that plays the transport: it records what it reached and what it actually sent. */
    private class FakeLink(private val behaviour: (String) -> Reached) : LinkCall {
        /** Every URL the transport was asked to reach. */
        val reached = mutableListOf<String>()

        /** Only the calls that got as far as sending PAIR (the pin matched): url -> code. */
        val sent = mutableListOf<Pair<String, String>>()
        val timeouts = mutableListOf<Long>()

        override fun call(url: String, fingerprint: String?, orchestratorId: String?, credential: String?, type: String, data: String, timeoutSeconds: Long): RunnerLinkServer.Answer {
            reached += url
            timeouts += timeoutSeconds
            when (val r = behaviour(url)) {
                is Reached.Unreachable ->
                    throw LinkFailure(if (r.timedOut) "the runner link at $url did not answer within ${timeoutSeconds}s" else "cannot reach the runner link at $url: Connection refused")
                Reached.WrongCertificate ->
                    throw LinkFailure("certificate fingerprint mismatch: pinned $fingerprint, the runner presented OTHER (refused)", "OTHER")
                is Reached.Refused -> return RunnerLinkServer.Answer(F.PAIR, refusal(r.reason))
                Reached.Paired -> {
                    sent += url to F.json.decodeFromString(F.PairRequest.serializer(), data).code
                    return RunnerLinkServer.Answer(F.REPLY, paired())
                }
            }
        }

        private fun paired(): String = F.json.encodeToString(F.PairAnswer.serializer(), F.PairAnswer("inf-runner-1", "t1", "credential-1"))

        private fun refusal(reason: String): String =
            JsonObject(mapOf("ok" to JsonPrimitive(false), "error" to JsonPrimitive(reason))).toString()
    }

    private fun add(link: RunnerPairLink = this.link, allowPlain: Boolean = false): AddOutcome =
        RunnerPeers(store, FakeLink { Reached.Paired }).addLink(link, link.name, "orch", allowPlain)

    @Test
    fun `the first unreachable host is skipped and the second pairs`() {
        val fake = FakeLink { url -> if (url.contains("10.255.255.1")) Reached.Unreachable() else Reached.Paired }
        val outcome = RunnerPeers(store, fake).addLink(link, "t1", "orch", allowPlain = false)

        assertEquals("wss://127.0.0.1:8790", outcome.runner.url)
        assertEquals(listOf("wss://10.255.255.1:8790", "wss://127.0.0.1:8790"), fake.reached)
        assertEquals(listOf("wss://127.0.0.1:8790" to "ABCD-EFGH-JKLM-NPQR"), fake.sent)
        assertEquals(1, outcome.skipped.size, outcome.skipped.toString())
        assertTrue(outcome.skipped.single().startsWith("10.255.255.1:8790 unreachable"), outcome.skipped.single())
        // Each host gets limits.runners.addHostTimeoutSec (default 5), never the whole link call timeout.
        assertEquals(listOf(LimitRegistry.RUNNERS_ADD_HOST_TIMEOUT_SEC.default, LimitRegistry.RUNNERS_ADD_HOST_TIMEOUT_SEC.default), fake.timeouts)
        assertEquals("t1", store.runner("t1")?.name)
        assertEquals("credential-1", store.runner("t1")?.credential)
        assertEquals("abababababababababababababababababababababababababababababababab", store.runner("t1")?.fingerprint)
    }

    @Test
    fun `a timeout on the first host reads as a timeout and the second pairs`() {
        val fake = FakeLink { url -> if (url.contains("10.255.255.1")) Reached.Unreachable(timedOut = true) else Reached.Paired }
        val outcome = RunnerPeers(store, fake).addLink(link, "t1", "orch", allowPlain = false)
        assertEquals("10.255.255.1:8790 unreachable (timed out after 5s)", outcome.skipped.single())
        assertEquals("wss://127.0.0.1:8790", outcome.runner.url)
    }

    @Test
    fun `a host whose certificate does not match is skipped without sending it the code`() {
        val fake = FakeLink { url -> if (url.contains("10.255.255.1")) Reached.WrongCertificate else Reached.Paired }
        val outcome = RunnerPeers(store, fake).addLink(link, "t1", "orch", allowPlain = false)

        assertEquals("wss://127.0.0.1:8790", outcome.runner.url)
        assertEquals("10.255.255.1:8790 certificate does not match the link", outcome.skipped.single())
        // The security property: the pin is checked in the handshake, so the wrong host never saw PAIR.
        assertEquals(listOf("wss://127.0.0.1:8790"), fake.sent.map { it.first })
    }

    @Test
    fun `a PAIR refusal stops at once and the next host is not tried with the used code`() {
        val fake = FakeLink { url -> if (url.contains("10.255.255.1")) Reached.Refused("PAIR refused: wrong pairing code") else Reached.Paired }
        val e = assertFailsWith<LinkFailure> { RunnerPeers(store, fake).addLink(link, "t1", "orch", allowPlain = false) }

        assertTrue(e.message!!.contains("10.255.255.1:8790 refused"), e.message)
        assertTrue(e.message!!.contains("wrong pairing code"), e.message)
        assertFalse(e.message!!.contains("ABCD-EFGH-JKLM-NPQR"), "the code must never be printed: ${e.message}")
        assertEquals(listOf("wss://10.255.255.1:8790"), fake.reached)
        assertNull(store.runner("t1"))
    }

    @Test
    fun `every host failing lists each one with its reason`() {
        val fake = FakeLink { url -> if (url.contains("10.255.255.1")) Reached.Unreachable(timedOut = true) else Reached.WrongCertificate }
        val e = assertFailsWith<LinkFailure> { RunnerPeers(store, fake).addLink(link, "t1", "orch", allowPlain = false) }
        assertTrue(
            e.message.orEmpty().startsWith("no host of the link answered: 10.255.255.1:8790 unreachable (timed out after 5s); 127.0.0.1:8790 certificate does not match the link. Try:"),
            e.message,
        )
        // RUNNER-LINK-HOST-1: the hint names the link's pairs and the Docker published-port route.
        assertTrue(e.message.orEmpty().contains("docker ps") && e.message.orEmpty().contains("10.255.255.1:8790"), e.message)
        assertNull(store.runner("t1"))
    }

    @Test
    fun `a plain link is refused while runners allowPlainTailnet is off here`() {
        val plain = RunnerPairLink("t1", listOf("100.64.0.5"), 8790, "ABCD-EFGH-JKLM-NPQR", fingerprint = null, plain = true)
        val fake = FakeLink { Reached.Paired }
        val e = assertFailsWith<IllegalArgumentException> { RunnerPeers(store, fake).addLink(plain, "t1", "orch", allowPlain = false) }
        assertTrue(e.message!!.contains("runners.allowPlainTailnet"), e.message)
        assertTrue(fake.reached.isEmpty(), "nothing may be dialled for a plain link this side refuses")

        // With the setting on here too, the plain link pairs over ws://.
        val outcome = RunnerPeers(store, fake).addLink(plain, "t1", "orch", allowPlain = true)
        assertEquals("ws://100.64.0.5:8790", outcome.runner.url)
    }

    @Test
    fun `an already paired name is refused before any host is dialled`() {
        add()
        val fake = FakeLink { Reached.Paired }
        val e = assertFailsWith<IllegalArgumentException> { RunnerPeers(store, fake).addLink(link, "t1", "orch", allowPlain = false) }
        assertTrue(e.message!!.contains("already paired"), e.message)
        assertTrue(fake.reached.isEmpty())
    }

    // ---- the daemon op: `runner_admin add {link}` and the first add turning runners.enabled on ----

    /** No TLS listener needed here: this fake answers every link call as a runner that pairs, and nothing serves. */
    private object PairingTransport : RunnerLinkTransport {
        override fun call(url: String, fingerprint: String?, allowPlain: Boolean, orchestratorId: String?, credential: String?, type: String, data: String, timeoutSeconds: Long): LinkAnswer {
            if (type == F.DETACH) return LinkAnswer(F.REPLY, "{}")
            return LinkAnswer(F.REPLY, F.json.encodeToString(F.PairAnswer.serializer(), F.PairAnswer("inf-runner-$type", "t1", "credential-$type")))
        }

        override fun serve(handler: LinkHandler, host: String, port: Int, tls: LinkTlsMaterial?, callTimeoutMs: Int): LinkServing =
            throw UnsupportedOperationException("the add op never serves")

        override fun fingerprint(tls: LinkTlsMaterial): String = "AB:CD:EF:01"
    }

    private fun service(dir: java.io.File): RunnerLinkService = RunnerLinkService(
        dir, { it.default },
        settings = { RunnerLinkSettings.Values() },
        runner = null,
        transport = { PairingTransport },
        host = "127.0.0.1",
        log = {},
    )

    private fun request(vararg fields: Pair<String, String?>): JsonObject =
        JsonObject(fields.filter { it.second != null }.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `the first add from a link turns runners enabled on and a later add does not`() {
        val dir = Files.createTempDirectory("runner-add-link").toFile()
        val svc = service(dir)
        var enabled = false
        svc.enabledNow = { enabled }
        svc.setEnabled = { enabled = it }

        val first = svc.admin(request("op" to "add", "link" to link.toUri(), "orchestratorName" to "orch"))
        assertEquals("true", RunnerLinkService.field(first, "ok"), first.toString())
        assertEquals("true", RunnerLinkService.field(first, "enabledTurnedOn"), first.toString())
        assertEquals("t1", RunnerLinkService.field(first, "name"))
        // The transport fake answers on the FIRST host of the link, which is therefore the one stored.
        assertEquals("wss://10.255.255.1:8790", RunnerLinkService.field(first, "url"))
        assertTrue(enabled, "the first add must turn runners.enabled on")

        val second = svc.admin(request("op" to "add", "link" to link.toUri(), "name" to "t2", "orchestratorName" to "orch"))
        assertEquals("true", RunnerLinkService.field(second, "ok"), second.toString())
        assertEquals("t2", RunnerLinkService.field(second, "name"))
        assertEquals("false", RunnerLinkService.field(second, "enabledTurnedOn"), second.toString())

        // Detaching the last runner leaves the switch on: no surprise toggle back off.
        svc.admin(request("op" to "detach", "name" to "t1"))
        svc.admin(request("op" to "detach", "name" to "t2"))
        assertTrue(enabled, "detaching must not turn runners.enabled off")
    }

    @Test
    fun `a bad link answers the codec's reason and stores nothing`() {
        val dir = Files.createTempDirectory("runner-add-link-bad").toFile()
        val svc = service(dir)
        var enabled = false
        svc.enabledNow = { enabled }
        svc.setEnabled = { enabled = it }

        val answer = svc.admin(request("op" to "add", "link" to "infantry://runner?v=9&name=t1&host=10.0.0.1&port=8790&code=ABCD-EFGH-JKLM-NPQR&fp=abababababababababababababababababababababababababababababababab"))
        assertEquals("false", RunnerLinkService.field(answer, "ok"), answer.toString())
        val error = RunnerLinkService.field(answer, "error").orEmpty()
        assertTrue(error.contains("newer runner"), error)
        assertFalse(error.contains("ABCD-EFGH-JKLM-NPQR"), error)
        assertFalse(enabled, "a failed add must not turn runners.enabled on")
        assertNull(RunnerLinkStore.open(dir).runner("t1"))
    }

    @Test
    fun `the older name url code form still works and also turns runners enabled on`() {
        val dir = Files.createTempDirectory("runner-add-old").toFile()
        val svc = service(dir)
        var enabled = false
        svc.enabledNow = { enabled }
        svc.setEnabled = { enabled = it }

        val answer = svc.admin(
            request("op" to "add", "name" to "t1", "url" to "wss://127.0.0.1:8790", "code" to "ABCD-EFGH-JKLM-NPQR", "fingerprint" to "AB:CD:EF:01"),
        )
        assertEquals("true", RunnerLinkService.field(answer, "ok"), answer.toString())
        assertEquals("true", RunnerLinkService.field(answer, "enabledTurnedOn"), answer.toString())
        assertTrue(enabled)
    }
}
