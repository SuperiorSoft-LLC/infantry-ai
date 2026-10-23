package com.superiorsoftllc.agenthq.daemon

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BUG-PORT-1-STATEDIR: one daemon per state dir. A second daemon on a held state dir exits 2 with one readable line
 * naming the holder's pid and port, before it opens anything; a lock whose holder is dead is taken over.
 *
 * Every daemon here binds port 0 on a temp state dir, never 8787/8788 ([TestPortGuardTest]).
 */
class StateDirLockTest {

    private fun tempStateDir() = Files.createTempDirectory("agenthq-statedir-lock").toFile()

    /** The real `main` in its own JVM on [stateDir], port 0, stub provider; output goes to [log]. */
    private fun startDaemon(stateDir: File, log: File): Process {
        // The test classpath can exceed the Windows command-line limit, so it travels in an @argfile.
        val argFile = File(stateDir.parentFile, "${stateDir.name}.java.args").apply {
            writeText("-cp\n\"${System.getProperty("java.class.path").replace("\\", "\\\\")}\"\n")
            deleteOnExit()
        }
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val builder = ProcessBuilder(java, "@${argFile.absolutePath}", "com.superiorsoftllc.agenthq.daemon.DaemonMainKt")
        builder.environment().apply {
            keys.filter { it.startsWith("AGENTHQ_", ignoreCase = true) }.forEach(::remove)
            put("AGENTHQ_HOST", "127.0.0.1")
            put("AGENTHQ_PORT", "0")
            put("AGENTHQ_STATE_DIR", stateDir.absolutePath)
            put("AGENTHQ_PROVIDER", "stub")
        }
        return builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
    }

    /** Wait until the daemon has bound and recorded its port in its state-dir lock; returns it (= daemon.port). */
    private fun awaitBoundPort(process: Process, stateDir: File, log: File): Int {
        val lockFile = File(stateDir, StateDirLock.FILE_NAME)
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            StateDirLock.read(lockFile).port?.let { port ->
                assertEquals(port, DaemonPort.read(File(stateDir, DaemonPort.FILE_NAME)), "lock and daemon.port agree")
                return port
            }
            check(process.isAlive) { "daemon A exited with ${process.exitValue()} before binding:\n${log.readText()}" }
            Thread.sleep(100)
        }
        error("daemon A never recorded its port:\n${log.readText()}")
    }

    /**
     * BUG-STATEDIR-2: take over the lock of [dead], a daemon just killed. The OS drops a dead process's file locks
     * on its own clock: on Windows that can land after [Process.waitFor] has returned ("the time it takes for the
     * operating system to unlock these locks depends upon available system resources", LockFileEx), so under a
     * loaded bulk gate the first try still found the lock held and threw. A refusal naming the dead holder is
     * retried until the OS lets go; a refusal naming anyone else is a real failure.
     */
    private fun takeOverFrom(dead: Process, stateDir: File, pid: Long): StateDirLock.Held {
        check(!dead.isAlive) { "the holder must be dead before its lock is taken over" }
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            try {
                return StateDirLock.acquire(stateDir, pid)
            } catch (stillHeld: StateDirInUseException) {
                if (stillHeld.holder.pid != dead.pid() || System.currentTimeMillis() > deadline) throw stillHeld
                Thread.sleep(100)
            }
        }
    }

    /**
     * The failing assertion first: before this fix the second daemon fell back to a free port and came up on the
     * same state dir. Now it exits 2, one line, naming A's pid and port. Then A is killed and its lock is taken over.
     */
    @Test fun aSecondDaemonOnTheSameStateDirExitsTwoNamingTheFirst() {
        val stateDir = tempStateDir()
        val logA = File(stateDir.parentFile, "${stateDir.name}.a.log").apply { deleteOnExit() }
        val a = startDaemon(stateDir, logA)
        try {
            val portA = awaitBoundPort(a, stateDir, logA)
            val logB = File(stateDir.parentFile, "${stateDir.name}.b.log").apply { deleteOnExit() }
            val b = startDaemon(stateDir, logB)
            try {
                assertTrue(b.waitFor(60, TimeUnit.SECONDS), "the second daemon must exit, not come up:\n${logB.readText()}")
            } finally {
                b.destroyForcibly()
            }
            val out = logB.readText()
            assertEquals(2, b.exitValue(), out)
            val line = "[agentHq] refusing to start: state dir ${stateDir.absolutePath} is in use by another daemon " +
                "(pid ${a.pid()}, port $portA)."
            assertTrue(line in out, out)
            assertEquals(1, out.lines().count { it.isNotBlank() }, "one readable line: $out")
            assertFalse("Exception" in out || "\tat " in out, "no stack trace: $out")
            assertTrue(a.isAlive, "the first daemon keeps running")

            // A dies without a clean shutdown: its lock file still names it, and the next daemon takes it over.
            assertTrue(a.destroyForcibly().waitFor(30, TimeUnit.SECONDS), "the first daemon must die")
            assertEquals(a.pid(), StateDirLock.read(File(stateDir, StateDirLock.FILE_NAME)).pid)
            val taken = takeOverFrom(a, stateDir, pid = 777L)
            try {
                assertEquals(777L, StateDirLock.read(taken.file).pid, "a dead holder's lock is taken over")
            } finally {
                taken.release()
            }
        } finally {
            a.destroyForcibly()
            a.waitFor(30, TimeUnit.SECONDS)
            stateDir.deleteRecursively()
        }
    }

    @Test fun aHeldStateDirRefusesWithTheHoldersPidAndPort() {
        val stateDir = tempStateDir()
        val held = StateDirLock.acquire(stateDir, pid = 4242L)
        try {
            held.recordPort(40123)
            val refused = assertFailsWith<StateDirInUseException> { StateDirLock.acquire(stateDir, pid = 4343L) }
            assertEquals(StateDirLock.Holder(4242L, 40123), refused.holder)
            assertEquals(
                "state dir ${stateDir.absolutePath} is in use by another daemon (pid 4242, port 40123). " +
                    "Stop that daemon, or set AGENTHQ_STATE_DIR to a different directory.",
                refused.message,
            )
        } finally {
            held.release()
            stateDir.deleteRecursively()
        }
    }

    @Test fun aHolderThatHasNotBoundYetSaysSo() {
        val stateDir = tempStateDir()
        val held = StateDirLock.acquire(stateDir, pid = 4242L)
        try {
            val refused = assertFailsWith<StateDirInUseException> { StateDirLock.acquire(stateDir) }
            assertTrue("(pid 4242, port not bound yet)" in refused.message.orEmpty(), refused.message)
        } finally {
            held.release()
            stateDir.deleteRecursively()
        }
    }

    @Test fun aStaleLockFileWithNoLiveHolderIsTakenOver() {
        val stateDir = tempStateDir()
        try {
            File(stateDir, StateDirLock.FILE_NAME).writeText("pid=999999999\nport=40999\n")
            val held = StateDirLock.acquire(stateDir, pid = 5150L)
            try {
                assertEquals(StateDirLock.Holder(5150L, null), StateDirLock.read(held.file), "the stale text is replaced")
            } finally {
                held.release()
            }
        } finally {
            stateDir.deleteRecursively()
        }
    }

    @Test fun aReleasedLockCanBeTakenAgain() {
        val stateDir = tempStateDir()
        try {
            StateDirLock.acquire(stateDir, pid = 1L).release()
            val again = StateDirLock.acquire(stateDir, pid = 2L)
            assertNotNull(again)
            again.release()
            again.release() // idempotent
        } finally {
            stateDir.deleteRecursively()
        }
    }

    @Test fun anUnreadableLockFileDescribesAnUnknownHolder() {
        val file = Files.createTempFile("daemon", ".lock").toFile().apply { deleteOnExit() }
        file.writeText("garbage")
        assertEquals(StateDirLock.Holder(null, null), StateDirLock.read(file))
        assertEquals("pid unknown, port not bound yet", StateDirLock.Holder(null, null).describe())
    }
}
