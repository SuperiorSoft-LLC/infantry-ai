package com.superiorsoftllc.agenthq.session

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ticket 7e9bed2a: the gate lock as the daemon reads it. Fake temp dir and fake pid liveness only. */
class GateLockTest {
    private val tmp = createTempDirectory("gatelock").toFile()
    private val lockDir = File(tmp, "agenthq-gate.lock")
    private val alive = mutableSetOf<Long>()
    private val lock = GateLock(dir = { lockDir }, pidAlive = { it in alive })

    private fun hold(owner: String?, pid: String? = "100") {
        lockDir.mkdirs()
        pid?.let { File(lockDir, "pid").writeText(it) }
        owner?.let { File(lockDir, GateLock.OWNER_FILE).writeText(it) }
    }

    @Test fun `no lock directory reads as no lock`() {
        assertNull(lock.read())
        assertFalse(lock.liveFor("s1", "t1"))
        assertNull(lock.releaseIfOwnedBy("s1", "t1"))
    }

    @Test fun `owner txt is parsed and the pid file is the fallback`() {
        hold("ticket=ticket-7e9bed2a session=s1 pid=77")
        assertEquals(GateLock.Owner(77, "ticket-7e9bed2a", "s1"), lock.read())
        File(lockDir, GateLock.OWNER_FILE).delete()
        assertEquals(GateLock.Owner(100, null, null), lock.read())
    }

    @Test fun `a live holder naming this ticket or session counts, one naming another does not`() {
        alive += 77
        hold("ticket=ticket-7e9bed2a session=s1 pid=77")
        assertTrue(lock.liveFor("s1", null))
        assertTrue(lock.liveFor("other", "7e9bed2a")) // ids compare without the ticket- prefix
        assertFalse(lock.liveFor("other", "ticket-ffff"))
    }

    @Test fun `a live holder with no owner information counts, a dead one does not`() {
        hold(null)
        alive += 100
        assertTrue(lock.liveFor("s1", "t1"))
        alive.clear()
        assertFalse(lock.liveFor("s1", "t1"))
    }

    @Test fun `release needs owner txt naming the closer and a gone or killed holder`() {
        hold("ticket=t1 session=s1 pid=77")
        alive += 77
        assertNull(lock.releaseIfOwnedBy("s1", "t1"), "holder alive and not killed: keep")
        assertTrue(lockDir.isDirectory)
        assertNull(lock.releaseIfOwnedBy("s2", "t2", killedPids = setOf(77)), "someone else's lock: keep")
        assertNotNull(lock.releaseIfOwnedBy("s1", "t1", killedPids = setOf(77)))
        assertFalse(lockDir.exists())
    }

    @Test fun `release of a dead holders lock named for the ticket`() {
        hold("ticket=t1 pid=77")
        assertNotNull(lock.releaseIfOwnedBy("sX", "t1"))
        assertFalse(lockDir.exists())
    }

    @Test fun `a lock without owner txt is never released`() {
        hold(null)
        assertNull(lock.releaseIfOwnedBy("s1", "t1"))
        assertTrue(lockDir.isDirectory)
    }
}
