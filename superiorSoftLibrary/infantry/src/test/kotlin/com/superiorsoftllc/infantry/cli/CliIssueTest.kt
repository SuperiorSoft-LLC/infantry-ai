// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P10-K7: `infantry issue list / show / open / attempt / fix / precheck` over a REAL daemon and the REAL [Cli.run] (the
 * [CliDecisionTest] pattern). The active issue an unnamed `attempt` / `fix` goes to is resolved from
 * `INFANTRY_SESSION_ID`, per session, against two REAL sessions of the daemon; a refusal prints the daemon's text, code
 * first, exit 1; a malformed invocation is a usage error, exit 2.
 */
class CliIssueTest {
    private val fixtures = mutableListOf<DaemonFixture>()

    private fun launch(): DaemonFixture =
        DaemonFixture.launch(stateDir = Files.createTempDirectory("infantry-issue-cli-test").toFile())
            .also {
                fixtures += it
                it.client.await(Envelope.READY)
            }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun DaemonFixture.cli(vararg args: String, project: String? = "P", session: String? = null): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val env = buildMap {
            if (project != null) put(AccessPolicy.PROJECT_ID_ENV, project)
            if (session != null) put(AccessPolicy.SESSION_ID_ENV, session)
        }
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, getenv = { env[it] }, input = { StringReader("") })
        return Result(exit, out.toString(), err.toString())
    }

    private fun Result.lines() = out.lines().filter { it.isNotBlank() }

    /** A real session on the daemon (the stub answers a prompt), so `session_not_found` cannot hide a scoping bug. */
    private fun DaemonFixture.spawnSession(exclude: Set<String> = emptySet()): String {
        // A connection's prompt goes to that connection's session, so each new session gets its own connection.
        val owner = connectClient()
        owner.await(Envelope.READY)
        owner.send(Envelope(Envelope.PROMPT, "hello"))
        return owner.awaitWhere("a new session's turn", 10_000) {
            it.type == Envelope.TURN && it.sessionId != null && it.sessionId !in exclude
        }.sessionId!!
    }

    @Test
    fun theActiveIssueIsResolvedFromTheSessionInTheEnvironment() {
        val fixture = launch()
        val a = fixture.spawnSession()
        val b = fixture.spawnSession(exclude = setOf(a))
        assertNotEquals(a, b)

        fixture.cli("issue", "open", "--summary", "login 500", "--location", "src/auth/Login.kt", session = a).also {
            assertEquals(0, it.exit, it.err)
            assertEquals("opened I1: login 500 (src/auth/Login.kt)", it.out.trim())
        }
        fixture.cli("issue", "open", "--summary", "cart total", "--location", "src/cart/Cart.kt", "--ticket", "T-7", session = b).also {
            assertEquals(0, it.exit, it.err)
            assertEquals("opened I2: cart total (src/cart/Cart.kt)", it.out.trim())
        }

        // B opened the most recent issue; A's unnamed attempt must still go to A's own I1.
        fixture.cli("issue", "attempt", "--summary", "null check on token", session = a).also {
            assertEquals(0, it.exit, it.err)
            assertEquals("attempt #1 on I1 (failed): null check on token", it.out.trim())
        }
        fixture.cli("issue", "attempt", "--summary", "round half up", "--outcome", "partial", "--location", "src/cart/Money.kt", session = b).also {
            assertEquals(0, it.exit, it.err)
            assertEquals("attempt #1 on I2 (partial): round half up", it.out.trim())
        }
        fixture.cli("issue", "fix", "--summary", "BigDecimal everywhere", session = b).also {
            assertEquals(0, it.exit, it.err)
            assertEquals("fixed I2: BigDecimal everywhere", it.out.trim())
        }
        assertEquals(listOf("I1 | open | login 500 (src/auth/Login.kt)"), fixture.cli("issue", "list").lines(), "B's fix closed B's issue only")

        // B has no open issue left: its unnamed fix is refused, and its next unnamed attempt opens an implicit issue.
        fixture.cli("issue", "fix", "--summary", "again", session = b).also {
            assertEquals(1, it.exit)
            assertTrue("issue_fix refused: no_active_issue: " in it.err, it.err)
        }
        fixture.cli("issue", "attempt", "--summary", "flaky again?", session = b).also {
            assertEquals(0, it.exit, it.err)
            assertEquals(
                listOf("opened implicit I3: flaky again? (no active issue for this session)", "attempt #1 on I3 (failed): flaky again?"),
                it.lines(),
            )
        }
        fixture.cli("issue", "fix", "I1", "--summary", "refresh before use", session = b).also { assertEquals(0, it.exit, it.err) }

        val show = fixture.cli("issue", "show", "I2")
        assertEquals(0, show.exit, show.err)
        assertTrue(
            "I2: cart total" in show.out && "STATUS: fixed" in show.out && "TICKET: T-7" in show.out &&
                "session $b" in show.out && "#1 attempt partial: round half up (src/cart/Money.kt)" in show.out &&
                "#2 fix: BigDecimal everywhere" in show.out,
            show.out,
        )
        assertEquals(
            listOf(
                "I1 | fixed | login 500 (src/auth/Login.kt)",
                "I2 | fixed | cart total (src/cart/Cart.kt)",
                "I3 | open | flaky again? [implicit]",
            ),
            fixture.cli("issue", "list", "--all").lines(),
        )
    }

    @Test
    fun precheckListsOpenIssuesFirstWithTheirAttempts() {
        val fixture = launch()
        fixture.cli("issue", "open", "--summary", "old crash", "--location", "src/a/A.kt").also { assertEquals(0, it.exit, it.err) }
        fixture.cli("issue", "fix", "I1", "--summary", "guarded").also { assertEquals(0, it.exit, it.err) }
        fixture.cli("issue", "open", "--summary", "new crash", "--location", "src\\a\\B.kt:12").also { assertEquals(0, it.exit, it.err) }
        fixture.cli("issue", "attempt", "I2", "--summary", "retry").also { assertEquals(0, it.exit, it.err) }
        fixture.cli("issue", "open", "--summary", "neighbour", "--location", "src/ab/C.kt").also { assertEquals(0, it.exit, it.err) }

        val pre = fixture.cli("issue", "precheck", "src/a")
        assertEquals(0, pre.exit, pre.err)
        assertEquals(
            listOf(
                "I2 | open | new crash (src\\a\\B.kt:12)",
                "    #1 attempt failed: retry",
                "I1 | fixed | old crash (src/a/A.kt)",
                "    #1 fix: guarded",
            ),
            pre.lines(),
        )
        assertEquals("No issues at lib in project P.", fixture.cli("issue", "precheck", "lib").out.trim())
        assertEquals(listOf("I3 | open | neighbour (src/ab/C.kt)"), fixture.cli("issue", "list", "--location", "src/ab").lines())
        assertEquals("No open issues in project Q.", fixture.cli("issue", "list", project = "Q").out.trim())
    }

    @Test
    fun refusalsPrintTheCodeAndExitOne() {
        val fixture = launch()
        fixture.cli("issue", "open", "--summary", "a", "--location", "src/a").also { assertEquals(0, it.exit, it.err) }
        fixture.cli("issue", "fix", "1", "--summary", "done").also { assertEquals(0, it.exit, it.err) }
        for ((args, prefix) in listOf(
            arrayOf("issue", "fix", "I1", "--summary", "again") to "issue_fix refused: already_fixed: ",
            arrayOf("issue", "attempt", "I1", "--summary", "recurs") to "issue_attempt refused: already_fixed: ",
            arrayOf("issue", "attempt", "I9", "--summary", "x") to "issue_attempt refused: issue_not_found: ",
            arrayOf("issue", "show", "I9") to "issue_get refused: issue_not_found: ",
            arrayOf("issue", "fix", "--summary", "x") to "issue_fix refused: no_active_issue: ",
        )) {
            val r = fixture.cli(*args)
            assertEquals(1, r.exit, args.joinToString(" "))
            assertTrue(prefix in r.err, "${args.joinToString(" ")} -> ${r.err}")
        }
        val ghost = fixture.cli("issue", "open", "--summary", "x", "--location", "src/x", session = "no-such-session")
        assertEquals(1, ghost.exit)
        assertTrue("issue_open refused: session_not_found: " in ghost.err, ghost.err)
        assertEquals(listOf("I1 | fixed | a (src/a)"), fixture.cli("issue", "list", "--all").lines(), "no refusal wrote anything")
    }

    @Test
    fun malformedInvocationsAreUsageErrors() {
        val fixture = launch()
        for (args in listOf(
            arrayOf("issue"),
            arrayOf("issue", "bogus"),
            arrayOf("issue", "open", "--summary", "x"),
            arrayOf("issue", "open", "--location", "src/a"),
            arrayOf("issue", "attempt"),
            arrayOf("issue", "attempt", "--summary", "x", "--outcome", "great"),
            arrayOf("issue", "attempt", "X1", "--summary", "x"),
            arrayOf("issue", "fix", "I1"),
            arrayOf("issue", "fix", "I1", "--summary", "x", "--outcome", "worked"),
            arrayOf("issue", "show"),
            arrayOf("issue", "show", "I0"),
            arrayOf("issue", "precheck"),
        )) {
            assertEquals(2, fixture.cli(*args).exit, args.joinToString(" "))
        }
        val noProject = fixture.cli("issue", "list", project = null)
        assertEquals(2, noProject.exit)
        assertTrue("needs a project id" in noProject.err, noProject.err)
    }
}
