// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.runners.LinkAnswer
import com.superiorsoftllc.infantry.daemon.api.runners.LinkHandler
import com.superiorsoftllc.infantry.daemon.api.runners.LinkServing
import com.superiorsoftllc.infantry.daemon.api.runners.LinkTlsMaterial
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkTransport
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-PAIR-1: "Connect a runner" in one step (`runner_admin` op `connect`): the runner-side `runner pair` runs through the
 * owner's command, its link is redeemed by the same path as `add '<link>'`, and the link (with its one-time code) never leaves
 * the daemon: not in the answer, not in an error text.
 */
class RunnerConnectTest {
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val code = "ABCD-EFGH-JKLM-NPQR"
    private val link = RunnerPairLink(
        name = "box", hosts = listOf("127.0.0.1"), port = 18921, code = code,
        fingerprint = "abababababababababababababababababababababababababababababababab",
    ).toUri()

    /** Every link call answers as a runner that pairs; nothing serves. */
    private object PairingTransport : RunnerLinkTransport {
        val reached = mutableListOf<String>()
        @Volatile var helloFails = false
        override fun call(url: String, fingerprint: String?, allowPlain: Boolean, orchestratorId: String?, credential: String?, type: String, data: String, timeoutSeconds: Long): LinkAnswer {
            reached += url
            if (type == F.DETACH) return LinkAnswer(F.REPLY, "{}")
            if (type == F.HELLO && helloFails) return LinkAnswer(F.ERROR, "credential refused")
            return LinkAnswer(F.REPLY, F.json.encodeToString(F.PairAnswer.serializer(), F.PairAnswer("inf-runner-1", "box", "credential-1")))
        }
        override fun serve(handler: LinkHandler, host: String, port: Int, tls: LinkTlsMaterial?, callTimeoutMs: Int): LinkServing =
            throw UnsupportedOperationException("connect never serves on the orchestrator")
        override fun fingerprint(tls: LinkTlsMaterial): String = "AB:CD"
    }

    /** An [RunnerConnect.Issuer] that records the argv and plays the runner's output. */
    private class FakeIssuer(private val answer: (List<String>) -> RunnerConnect.Issued) : RunnerConnect.Issuer {
        val argv = mutableListOf<List<String>>()
        override fun issue(argv: List<String>, timeoutMs: Long): RunnerConnect.Issued { this.argv += argv; return answer(argv) }
    }

    private fun service(issuer: RunnerConnect.Issuer, dir: java.io.File = Files.createTempDirectory("runner-connect").toFile()): RunnerLinkService =
        RunnerLinkService(
            dir, { it.default }, settings = { RunnerLinkSettings.Values() }, runner = null, transport = { PairingTransport },
            host = "127.0.0.1", log = {}, connectIssuer = issuer,
        )

    private fun connect(vararg fields: Pair<String, String?>): JsonObject =
        JsonObject((listOf("op" to "connect") + fields).filter { it.second != null }.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `the owner's command is split into words, quotes keep spaces`() {
        assertEquals(listOf("docker", "exec", "my-runner"), RunnerConnect.splitVia("  docker   exec my-runner "))
        assertEquals(listOf("ssh", "-i", "C:\\keys\\my key", "me@host"), RunnerConnect.splitVia("ssh -i \"C:\\keys\\my key\" me@host"))
        assertEquals(listOf("a", "", "b"), RunnerConnect.splitVia("a '' b"))
        assertFailsWith<IllegalArgumentException> { RunnerConnect.splitVia("   ") }
        assertFailsWith<IllegalArgumentException> { RunnerConnect.splitVia("ssh 'me@host") }
    }

    @Test
    fun `the runner side runs the existing pair, granting every project and harness`() {
        assertEquals(
            listOf("x", "y", "infantry", "runner", "pair", "--name", "box", "--allow-projects", "*", "--allow-harnesses", "*"),
            RunnerConnect.pairCommand(listOf("x", "y"), "box"),
        )
        assertFailsWith<IllegalArgumentException> { RunnerConnect.pairCommand(emptyList(), "box") }
        assertEquals("localhost" to 18921, RunnerConnect.address(" localhost ", "18921"))
        assertEquals(null to null, RunnerConnect.address("  ", ""))
        assertFailsWith<IllegalArgumentException> { RunnerConnect.address(null, "99999") }
        assertFailsWith<IllegalArgumentException> { RunnerConnect.address("a b", null) }
    }

    @Test
    fun `the address from this machine is applied here, so an older runner that cannot take --port still pairs`() {
        val printed = RunnerPairLink("box", listOf("172.17.0.2"), 8790, code, "abababababababababababababababababababababababababababababababab")
        val reached = RunnerConnect.reachAt(printed, "localhost", 18921)
        assertEquals(listOf("localhost", "172.17.0.2"), reached.hosts)
        assertEquals(18921, reached.port)
        assertEquals(code, reached.code)
        assertEquals(printed.fingerprint, reached.fingerprint)
        assertEquals(8790, RunnerConnect.reachAt(printed, "localhost", null).port)
        assertTrue(RunnerConnect.reachAt(printed, null, null) === printed)
    }

    @Test
    fun `the link is found in the runner's own output line, and no line carrying it is ever repeated`() {
        assertEquals(link, RunnerConnect.findLink("Paste this into Settings > Runners > Add, or run on your PC: infantry runner add '$link'"))
        assertNull(RunnerConnect.findLink("pair code:   $code   (one use)"))
        assertFalse(RunnerConnect.safeLine("infantry runner add '$link'"))
        assertFalse(RunnerConnect.safeLine("pair code:   $code"))
        assertFalse(RunnerConnect.safeLine("  infantry runner add box wss://x:8790 --code $code"))
        assertTrue(RunnerConnect.safeLine("error: something"))
    }

    @Test
    fun `failures read as a plain reason with the next step`() {
        val paired = RunnerConnect.explain("docker", listOf("error: this runner is already paired with orchestrator other; one orchestrator only. `infantry runner detach` first."), 1, 60, null)
        assertTrue(paired.startsWith("that runner is already paired with another orchestrator") && "infantry runner detach" in paired, paired)
        val missing = RunnerConnect.explain("nosuchtool", emptyList(), null, 0, "Cannot run program \"nosuchtool\"")
        assertTrue(missing.startsWith("could not run 'nosuchtool'"), missing)
        val silent = RunnerConnect.explain("ssh", listOf("hello"), null, 60, null)
        assertTrue("no pairing link within 60 s" in silent, silent)
        val ended = RunnerConnect.explain("ssh", listOf("pair code: $code", "Error response from daemon: not running"), 1, 60, null)
        assertTrue("exit 1" in ended && "not running" in ended && code !in ended, ended)
        val port = RunnerConnect.explain("x", listOf("error: cannot serve the runner link on port 8790 (in use)"), 1, 60, null)
        assertTrue(port.startsWith("the runner could not open its link port"), port)
    }

    @Test
    fun `connect pairs from the printed link, turns runners on, and the answer never carries the link or its code`() {
        var finished = false
        val issuer = FakeIssuer { RunnerConnect.Issued(link, null) { finished = true } }
        val svc = service(issuer)
        var enabled = false
        svc.enabledNow = { enabled }
        svc.setEnabled = { enabled = it }

        val answer = svc.admin(connect("name" to "box", "via" to "docker exec efd-runner2", "host" to "localhost", "port" to "18921"))

        assertEquals("true", RunnerLinkService.field(answer, "ok"), answer.toString())
        assertEquals("box", RunnerLinkService.field(answer, "name"))
        assertEquals("true", RunnerLinkService.field(answer, "enabledTurnedOn"))
        assertTrue(enabled)
        assertTrue(finished, "the runner-side command is always finished")
        assertEquals(
            listOf("docker", "exec", "efd-runner2", "infantry", "runner", "pair", "--name", "box", "--allow-projects", "*", "--allow-harnesses", "*"),
            issuer.argv.single(),
        )
        assertFalse(code in answer.toString() || "infantry://" in answer.toString(), "the link must never leave the daemon: $answer")
        assertTrue(PairingTransport.reached.contains("wss://localhost:18921"), PairingTransport.reached.toString())
    }

    @Test
    fun `connect again on a runner that answers is already connected, and on one that does not it re-pairs with a fresh code`() {
        val dir = Files.createTempDirectory("runner-connect").toFile()
        // A runner whose paired listener is not up yet answers no hello until `runner up` (the settle command) asks it.
        val issuer = FakeIssuer { argv -> if ("up" in argv) PairingTransport.helloFails = false; RunnerConnect.Issued(link, null) }
        val svc = service(issuer, dir)
        assertEquals("true", RunnerLinkService.field(svc.admin(connect("name" to "box", "via" to "x")), "ok"))

        val again = svc.admin(connect("name" to "box", "via" to "x"))
        assertEquals("true", RunnerLinkService.field(again, "ok"), again.toString())
        assertEquals("true", RunnerLinkService.field(again, "already"))
        assertEquals(1, issuer.argv.size, "a runner that answers is not paired again")

        // The container was recreated: the old credential no longer answers, so Connect asks the runner for a fresh code.
        PairingTransport.helloFails = true
        try {
            val repaired = svc.admin(connect("name" to "box", "via" to "x"))
            assertEquals("true", RunnerLinkService.field(repaired, "ok"), repaired.toString())
            assertNull(RunnerLinkService.field(repaired, "already"))
            assertEquals(3, issuer.argv.size, issuer.argv.toString())
            assertEquals(RunnerConnect.settleCommand(listOf("x"), "box"), issuer.argv.last(), "a runner that does not answer yet is asked to settle")
            assertFalse(PairingTransport.helloFails, "connect returns once the runner answers")
            assertNotNull(RunnerLinkStore.open(dir).runner("box"))
        } finally {
            PairingTransport.helloFails = false
        }
    }

    @Test
    fun `a runner that printed no link is a failed answer with its reason, and nothing is added`() {
        val dir = Files.createTempDirectory("runner-connect").toFile()
        val issuer = FakeIssuer { RunnerConnect.Issued(null, "the runner refused: nope") }
        val answer = service(issuer, dir).admin(connect("name" to "box", "via" to "x"))
        assertEquals("false", RunnerLinkService.field(answer, "ok"))
        assertEquals("the runner refused: nope", RunnerLinkService.field(answer, "error"))
        assertNull(RunnerLinkStore.open(dir).runner("box"))
    }

    @Test
    fun `a bad name or a missing command is refused before anything runs`() {
        val issuer = FakeIssuer { error("must not run") }
        val svc = service(issuer)
        assertEquals("false", RunnerLinkService.field(svc.admin(connect("name" to "Bad Name", "via" to "x")), "ok"))
        assertEquals("false", RunnerLinkService.field(svc.admin(connect("name" to "box")), "ok"))
        assertEquals("false", RunnerLinkService.field(svc.admin(connect("name" to "box", "via" to "   ")), "ok"))
        assertTrue(issuer.argv.isEmpty())
    }

    @Test
    fun `the process issuer reads the link a real child prints, and reports a command that cannot run`() {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val echo = if (windows) listOf("cmd.exe", "/c", "echo", "infantry runner add $link") else listOf("sh", "-c", "echo \"infantry runner add '$link'\"")
        val issued = RunnerConnect.processIssuer().issue(echo, 20_000)
        assertNotNull(issued.link, issued.failure)
        assertTrue(issued.link!!.startsWith("infantry://runner?") && code in issued.link!!, issued.link)
        issued.finish(5_000)

        val missing = RunnerConnect.processIssuer().issue(listOf("infantry-no-such-command-xyz", "a"), 5_000)
        assertNull(missing.link)
        assertTrue(missing.failure.orEmpty().startsWith("could not run 'infantry-no-such-command-xyz'"), missing.failure)

        val quiet = if (windows) listOf("cmd.exe", "/c", "echo", "error:", "nope") else listOf("sh", "-c", "echo 'error: nope' >&2; exit 3")
        val refused = RunnerConnect.processIssuer().issue(quiet, 20_000)
        assertNull(refused.link)
        assertEquals("the runner refused: nope", refused.failure)
    }
}
