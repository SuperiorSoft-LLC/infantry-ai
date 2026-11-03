package com.superiorsoftllc.infantry.daemon.plugins

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** BOARD-STALL-2: a busy plugin must not starve Dispatchers.IO; its pool is bounded, callers suspend, a full queue is refused fast. */
class PluginGuardStarvationTest {
    @Test
    fun `70 blocked plugin calls leave Dispatchers IO free for unrelated work`() = runBlocking {
        val latch = CountDownLatch(1)
        val g = PluginGuard("Board", null, 60_000, {}, hangCeilingMs = 60_000, poolSize = 4, queueLimit = 100)
        val calls = (1..70).map { async(Dispatchers.IO) { g.callSuspending("tool", 60_000) { latch.await(); 1 } } }
        kotlinx.coroutines.delay(300)
        val io = withTimeoutOrNull(1_000) { withContext(Dispatchers.IO) { "io-ok" } }
        val chat = withTimeoutOrNull(1_000) { withContext(Dispatchers.IO) { PluginGuard("Chat", null, 60_000, {}).callSuspending("chat", 1_000) { 5 }.getOrNull() } }
        latch.countDown()
        calls.awaitAll()
        g.shutdown()
        assertNotNull(io, "an unrelated Dispatchers.IO job hung behind the plugin calls")
        assertEquals(5, chat, "the chat-path call hung behind the plugin calls")
    }

    @Test
    fun `a full queue is refused at once`() = runBlocking {
        val latch = CountDownLatch(1)
        val g = PluginGuard("Board", null, 60_000, {}, hangCeilingMs = 60_000, poolSize = 1, queueLimit = 1)
        val first = (1..2).map { async(Dispatchers.IO) { g.callSuspending("tool", 60_000) { latch.await(); 1 } } }
        kotlinx.coroutines.delay(300)
        val refused = withTimeoutOrNull(1_000) { g.callSuspending("tool", 60_000) { 1 } }
        latch.countDown()
        first.awaitAll()
        g.shutdown()
        assertNotNull(refused, "the refusal was not fast")
        assertTrue(refused.isFailure && refused.exceptionOrNull()?.message?.contains("busy") == true)
    }
}

/** BOARD-STALL-3: a caller that gives up while its job is still queued must not leave the job to run later. */
class PluginGuardAbandonedJobTest {
    @Test
    fun `a queued call whose caller timed out never runs`() = runBlocking {
        val latch = CountDownLatch(1)
        val ran = java.util.concurrent.atomic.AtomicInteger()
        val g = PluginGuard("Board", null, 60_000, {}, hangCeilingMs = 60_000, poolSize = 1, queueLimit = 10)
        val blocker = async(Dispatchers.IO) { g.callSuspending("blocker", 60_000) { latch.await(); 1 } }
        kotlinx.coroutines.delay(200)
        val abandoned = withTimeoutOrNull(300) { g.callSuspending("list", 60_000) { ran.incrementAndGet() } }
        latch.countDown()
        blocker.await()
        kotlinx.coroutines.delay(300)
        g.shutdown()
        assertEquals(null, abandoned)
        assertEquals(0, ran.get(), "the abandoned queued job ran after its caller timed out")
    }
}
