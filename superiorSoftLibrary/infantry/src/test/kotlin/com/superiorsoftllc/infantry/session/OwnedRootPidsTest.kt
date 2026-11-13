// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.daemon.InstanceId
import com.superiorsoftllc.infantry.session.host.KeptProcess
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PROC-REAP-COPY-1 (ticket a153c7bc): a daemon roots its process watch only at pids it recorded itself (owner id + start instant),
 * a whole-dir copy of a state dir gets its own instance id, and a `sessions.json` from before the rule loads unchanged.
 */
class OwnedRootPidsTest {
    private val live = mapOf(42L to 1_000L, 43L to 2_000L)
    private val startInstant: (Long) -> Long? = { live[it] }

    private fun record(owner: String?, childStartedAt: Long? = 1_000, kept: KeptProcess? = null) = SessionRecord(
        id = "s1", provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0, state = SessionState.ORPHANED,
        childPid = 42, childStartedAt = childStartedAt, ownerId = owner, keptProcess = kept,
    )

    @Test fun `a record another state dir wrote is no root`() =
        assertTrue(record(owner = "original").ownedRootPids("copy", startInstant).isEmpty())

    @Test fun `an older record with no owner is no root`() =
        assertTrue(record(owner = null, childStartedAt = null).ownedRootPids("me", startInstant).isEmpty())

    @Test fun `no instance id means nothing is provably ours`() =
        assertTrue(record(owner = "me").ownedRootPids(null, startInstant).isEmpty())

    @Test fun `own child with the recorded start instant is a root`() =
        assertEquals(listOf(42L), record(owner = "me").ownedRootPids("me", startInstant))

    @Test fun `own child whose pid now has another start instant is no root`() =
        assertTrue(record(owner = "me", childStartedAt = 999).ownedRootPids("me", startInstant).isEmpty())

    @Test fun `a kept process counts only with this dir's owner id and its start instant`() {
        val mine = record(owner = "me", kept = KeptProcess(43, 2_000, "opencode", ownerId = "me")).copy(childPid = null)
        assertEquals(listOf(43L), mine.ownedRootPids("me", startInstant))
        val foreign = mine.copy(keptProcess = KeptProcess(43, 2_000, "opencode", ownerId = "original"))
        assertTrue(foreign.ownedRootPids("me", startInstant).isEmpty())
    }

    @Test fun `a sessions json from before the rule loads unchanged`() {
        val old = """{"id":"s1","provider":"claude","workingDir":".","spawnedAt":1,"lastHeartbeat":2,"state":"ORPHANED","childPid":42,"providerSessionId":"p"}"""
        val r = Json { ignoreUnknownKeys = true }.decodeFromString(SessionRecord.serializer(), old)
        assertEquals(42L, r.childPid)
        assertEquals("p", r.providerSessionId)
        assertEquals(SessionState.ORPHANED, r.state)
        assertNull(r.ownerId)
        assertNull(r.childStartedAt)
    }

    @Test fun `a whole-dir copy of a state dir mints its own instance id and the original keeps its id`() {
        val original = Files.createTempDirectory("infantry-iid-orig").toFile()
        val id = InstanceId.loadOrCreate(original)
        val copy = Files.createTempDirectory("infantry-iid-copy").toFile()
        original.listFiles()!!.forEach { it.copyTo(File(copy, it.name)) }
        assertNotEquals(id, InstanceId.loadOrCreate(copy), "the copy is not the original")
        assertEquals(id, InstanceId.loadOrCreate(original), "the original keeps its id")
    }

    @Test fun `a dir from before the path stamp keeps its id and is stamped`() {
        val dir = Files.createTempDirectory("infantry-iid-old").toFile()
        File(dir, InstanceId.FILE_NAME).writeText("legacy-id")
        assertEquals("legacy-id", InstanceId.loadOrCreate(dir))
        assertTrue(File(dir, InstanceId.PATH_FILE).isFile)
        assertEquals("legacy-id", InstanceId.loadOrCreate(dir))
    }
}
