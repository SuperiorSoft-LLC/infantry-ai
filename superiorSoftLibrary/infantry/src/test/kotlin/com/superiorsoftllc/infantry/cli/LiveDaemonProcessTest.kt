// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SOAK-1 defect: the retry after a lost port must be decided by THIS attempt's log, not by the
 * whole accumulating one.
 *
 * `launchOnce` opens the daemon log with `Redirect.appendTo`, and a retry runs on the SAME state
 * directory, so attempt 2 appends to the file attempt 1 wrote. A classifier that reads the whole
 * file therefore still sees attempt 1's `BindException` and calls attempt 2's unrelated failure a
 * port collision -- burning four more attempts, on four fresh ports, for a failure that was never
 * about the port. It always threw in the end, with the log attached, so nothing was ever hidden;
 * what was wrong is that a failure that should have failed fast was retried instead, which is
 * sleep-by-stealth in a harness whose whole point is that it does not sleep.
 *
 * WHAT IS REAL HERE: the retry loop is the production one ([LiveDaemonProcess.launchWithPortRetry]),
 * the log is a real file that really accumulates across attempts, and the ports come from a real
 * sequence. WHAT IS SUBSTITUTED: `startAttempt`, i.e. the daemon itself. A real daemon cannot be
 * made to fail in a CHOSEN way on its second attempt -- the port it lost on the first is not
 * knowable in advance -- so the two failures are forced in sequence through the real loop instead.
 * The end-to-end daemon path is soaked by [CliAgentsLiveSoakTest]; this class pins the policy, and
 * says so rather than claiming coverage it does not have.
 */
class LiveDaemonProcessTest {

    private val dir: File = Files.createTempDirectory("infantry-port-retry").toFile()
    private val log: File = File(dir, "daemon.log")

    /** What a daemon prints when another process took the port between the probe and its bind. */
    private val bindFailure = "java.net.BindException: Address already in use: bind\n"

    /** What a daemon prints when it dies for a reason that has nothing to do with its port. */
    private val unrelatedFailure =
        "IllegalStateException: the daemon exited with 1 before it served /health: INFANTRY_DB is a directory\n"

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /**
     * The defect itself. Attempt 1 loses the port; attempt 2 fails for an unrelated reason. The
     * second failure must surface on the spot, because nothing about attempt 2's log says the port
     * was the problem.
     */
    @Test
    fun anUnrelatedFailureOnTheSecondAttemptIsNotRetried() {
        val ports = ArrayDeque(listOf(41001, 41002, 41003, 41004, 41005, 41006))
        var attempts = 0

        val thrown = assertFailsWith<IllegalStateException> {
            LiveDaemonProcess.launchWithPortRetry(
                log = log,
                fixedPort = null,
                freshPort = { ports.removeFirst() },
            ) { port ->
                attempts++
                if (attempts == 1) {
                    log.appendText(bindFailure)
                    throw DaemonLaunchFailure("attempt 1 on $port lost the port")
                }
                log.appendText(unrelatedFailure)
                throw DaemonLaunchFailure("attempt 2 on $port failed for an unrelated reason")
            }
        }

        // The condition this test is about has to have genuinely occurred, or "not retried" is
        // vacuous: the stale marker really is in the accumulating log, and attempt 2 really did
        // append to that same file.
        val accumulated = log.readText()
        assertTrue(
            accumulated.contains("Address already in use"),
            "attempt 1's bind failure never reached the log, so this proved nothing:\n$accumulated",
        )
        assertTrue(
            accumulated.contains(unrelatedFailure),
            "attempt 2 did not append to the same log file, so the offset it is classified from " +
                "was never in question:\n$accumulated",
        )
        assertEquals(
            2,
            attempts,
            "attempt 2's unrelated failure was retried ${attempts - 2} more time(s): the stale bind " +
                "marker attempt 1 left in the log was read as if it were attempt 2's own.",
        )
        assertEquals(
            "attempt 2 on 41002 failed for an unrelated reason",
            thrown.message,
            "the loop surfaced an earlier attempt's failure instead of the one that actually happened",
        )
    }

    /**
     * The other half of the acceptance: a REAL collision on attempt 1 still earns a fresh port. A
     * fix that simply stopped retrying would pass the test above and break this one.
     */
    @Test
    fun aLostPortOnTheFirstAttemptIsStillRetriedOnAFreshPort() {
        val ports = ArrayDeque(listOf(41201, 41202))
        val used = mutableListOf<Int>()
        var attempts = 0

        val servedOn: String = LiveDaemonProcess.launchWithPortRetry(
            log = log,
            fixedPort = null,
            freshPort = { ports.removeFirst() },
        ) { port ->
            attempts++
            used += port
            if (attempts == 1) {
                log.appendText(bindFailure)
                throw DaemonLaunchFailure("attempt 1 on $port lost the port")
            }
            "served on $port"
        }

        assertEquals(2, attempts, "a real port collision was not retried at all")
        assertEquals("served on 41202", servedOn, "the launch did not come up on the port it re-probed")
        assertEquals(
            listOf(41201, 41202),
            used,
            "the retry did not ask the OS for a DIFFERENT port, so the collision was simply re-raced",
        )
    }

    /**
     * The offset has to be taken PER ATTEMPT. Taken once, before the loop, attempt 1's marker is
     * still in view at attempt 3 -- which is the same defect with a longer tail.
     */
    @Test
    fun aLostPortOnTheSecondAttemptDoesNotClassifyTheThird() {
        val ports = ArrayDeque(listOf(41101, 41102, 41103, 41104, 41105, 41106))
        var attempts = 0

        val thrown = assertFailsWith<IllegalStateException> {
            LiveDaemonProcess.launchWithPortRetry(
                log = log,
                fixedPort = null,
                freshPort = { ports.removeFirst() },
            ) { port ->
                attempts++
                if (attempts <= 2) {
                    log.appendText(bindFailure)
                    throw DaemonLaunchFailure("attempt $attempts on $port lost the port")
                }
                log.appendText(unrelatedFailure)
                throw DaemonLaunchFailure("attempt 3 on $port failed for an unrelated reason")
            }
        }

        assertEquals(
            3,
            attempts,
            "attempt 3's unrelated failure was retried ${attempts - 3} more time(s): the log offset " +
                "was taken once, before the loop, instead of once per attempt.",
        )
        assertEquals(
            "attempt 3 on 41103 failed for an unrelated reason",
            thrown.message,
            "the loop surfaced an earlier attempt's failure instead of the one that actually happened",
        )
    }

    /**
     * An explicit port is a restart onto the port the previous daemon already proved. Re-choosing it
     * would silently move the restart target, so this must never retry -- whatever the log says.
     */
    @Test
    fun anExplicitPortIsNeverReChosenEvenWhenItWasLost() {
        var attempts = 0

        val thrown = assertFailsWith<IllegalStateException> {
            LiveDaemonProcess.launchWithPortRetry(
                log = log,
                fixedPort = 41301,
                freshPort = { throw AssertionError("an explicit port must never be re-probed") },
            ) { port ->
                attempts++
                log.appendText(bindFailure)
                throw DaemonLaunchFailure("the restarted daemon lost $port")
            }
        }

        assertEquals(1, attempts, "the restart re-chose its port, so a restart test can be measuring a different target")
        assertTrue(
            thrown.message.orEmpty().contains("41301"),
            "the failure does not even name the port the restart was pinned to: ${thrown.message}",
        )
    }

    /** A port that is never won has to stop, not spin: the cap is what makes the retry bounded. */
    @Test
    fun aPortThatIsNeverWonStopsAtTheAttemptCap() {
        val ports = ArrayDeque(listOf(41401, 41402, 41403, 41404, 41405, 41406))
        var attempts = 0

        assertFailsWith<IllegalStateException> {
            LiveDaemonProcess.launchWithPortRetry(
                log = log,
                fixedPort = null,
                freshPort = { ports.removeFirst() },
                maxAttempts = 3,
            ) { port ->
                attempts++
                log.appendText(bindFailure)
                throw DaemonLaunchFailure("attempt $attempts on $port lost the port")
            }
        }

        assertEquals(3, attempts, "the probe did not stop at the attempt cap")
    }

    /**
     * DEFECT 2, made deliberate rather than incidental: only [DaemonLaunchFailure]
     * earns a retry. Anything else is a harness bug -- a child JVM that cannot be started, a
     * classpath that cannot be worked out -- and burning four fresh ports on it would hide it.
     */
    @Test
    fun aFailureThatIsNotALaunchFailureIsNeverRetried() {
        var attempts = 0

        assertFailsWith<IOException> {
            LiveDaemonProcess.launchWithPortRetry(
                log = log,
                fixedPort = null,
                freshPort = { 41501 },
            ) { _ ->
                attempts++
                // Even a genuine bind marker in this attempt's own log must not rescue it: the
                // exception type is what decides, not the text.
                log.appendText(bindFailure)
                throw IOException("the child JVM could not be started")
            }
        }

        assertEquals(
            1,
            attempts,
            "an IOException was retried: the catch is wider than the launch failure it is meant for",
        )
    }
}
