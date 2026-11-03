package com.superiorsoftllc.infantry.db

import app.cash.sqldelight.db.QueryResult
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ticket 55d60a5e: the Docker daemon hung with every thread in `NativeDB._open_utf8`. Outside a transaction the driver
 * opens and closes a connection PER QUERY, so a burst of readers (a browser's connect burst) opens and closes in
 * parallel; SQLite 3.51.0 (sqlite-jdbc 3.51.0.0) deadlocks there: a WAL connection closing holds the inode lock and
 * waits for the unix-VFS mutex while an opening one holds that mutex and waits for the inode lock. This drives the same
 * pattern on a real WAL database and fails (instead of hanging the fork) when the readers do not all finish.
 */
class ConcurrentOpenCloseTest {

    @Test(timeout = 120_000)
    fun manyThreadsOpeningAndClosingConnectionsAtOnceAllFinish() {
        val dir = Files.createTempDirectory("infantry-open-close")
        val handle = InfantryDatabaseFactory.open(dir.resolve("infantry.db").toString())
        val threads = 24
        val queriesEach = 400
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r).also { it.isDaemon = true } }
        val done = CountDownLatch(threads)
        val answered = java.util.concurrent.atomic.AtomicInteger()
        try {
            repeat(threads) {
                pool.execute {
                    try {
                        repeat(queriesEach) {
                            handle.driver.executeQuery(null, "SELECT COUNT(*) FROM sqlite_master;", { c ->
                                c.next()
                                QueryResult.Value(c.getLong(0))
                            }, 0)
                            answered.incrementAndGet()
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }
            val finished = done.await(90, TimeUnit.SECONDS)
            assertTrue(finished, "readers stuck: ${answered.get()} of ${threads * queriesEach} queries answered (SQLite open/close deadlock)")
            assertEquals(threads * queriesEach, answered.get())
        } finally {
            pool.shutdownNow()
            if (done.count == 0L) {
                handle.close()
                dir.toFile().deleteRecursively()
            }
        }
    }
}
