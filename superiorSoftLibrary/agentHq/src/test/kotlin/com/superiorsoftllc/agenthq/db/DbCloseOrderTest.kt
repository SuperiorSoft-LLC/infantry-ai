package com.superiorsoftllc.agenthq.db

import app.cash.sqldelight.db.SqlDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GAP-1: the driver's close runs SQLite's final WAL checkpoint, which can hit SQLITE_BUSY. The lock
 * tracker must still be open at that moment so the BUSY can be attributed; it closes AFTER the driver.
 */
class DbCloseOrderTest {

    @Test
    fun theTrackerIsStillOpenWhenTheDriverCloses() {
        val dir = Files.createTempDirectory("agenthq-close-order").toFile()
        try {
            val real = AgentHQDatabaseFactory.open(File(dir, "agenthq.db").absolutePath, "close-order")
            val tracker = real.writes!!
            tracker.publishNow()
            val record = File(File(dir, "agenthq.db.holders"), "${tracker.instanceId}.json")
            assertTrue(record.exists(), "precondition: the tracker's holder record is published")

            val events = mutableListOf<String>()
            val recording = object : SqlDriver by real.driver {
                override fun close() {
                    events += if (record.exists()) "driver.close(tracker open)" else "driver.close(tracker CLOSED)"
                    real.driver.close()
                }
            }
            AgentHQDatabaseHandle(real.database, recording, tracker).close()
            events += if (record.exists()) "tracker still open" else "tracker closed"

            assertEquals(listOf("driver.close(tracker open)", "tracker closed"), events)
        } finally {
            dir.deleteRecursively()
        }
    }
}
