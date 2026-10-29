package com.superiorsoftllc.agenthq.daemon

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Ticket 55d60a5e: `/health` must answer even when the database does not.
 *
 * In the Docker image the daemon hung with every SQLite open blocked in native code (a lock-order deadlock inside
 * sqlite-jdbc 3.51.0); `/health` read its counts from the database on the request thread, so it hung too, the container
 * stayed "Up (unhealthy)" and nothing said why. The gate runs the database part of an answer on its OWN thread and
 * waits at most [timeoutMs]: a read that does not come back in time is reported as a stuck database, at once, and the
 * request thread is free again. Only one read is ever in flight: while it is stuck every later caller is answered
 * "stuck" immediately instead of queuing another thread behind the same lock; callers that arrive while a read is
 * still within its budget share that read's answer.
 */
class HealthGate(private val timeoutMs: Long, private val clock: () -> Long = System::currentTimeMillis) : AutoCloseable {

    /** What one gated read came to. */
    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        /** The read has been running for [forMs] and is past its budget: the database is not answering. */
        data class Stuck(val forMs: Long) : Outcome<Nothing>
        /** The read threw: the database answered with an error. */
        data class Failed(val message: String) : Outcome<Nothing>
    }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "agenthq-health-probe").also { it.isDaemon = true } }
    private val lock = Any()
    private var inFlight: Future<*>? = null
    private var inFlightSince = 0L

    @Suppress("UNCHECKED_CAST")
    fun <T> read(compute: () -> T): Outcome<T> {
        val (future, since) = synchronized(lock) {
            val running = inFlight
            if (running != null && !running.isDone) {
                running to inFlightSince
            } else {
                val started = clock()
                val submitted = try {
                    executor.submit<T> { compute() }
                } catch (closed: java.util.concurrent.RejectedExecutionException) {
                    return Outcome.Failed("health gate closed")
                }
                inFlight = submitted
                inFlightSince = started
                submitted to started
            }
        }
        val left = timeoutMs - (clock() - since)
        if (left <= 0) return Outcome.Stuck(clock() - since)
        return try {
            Outcome.Ok(future.get(left, TimeUnit.MILLISECONDS) as T)
        } catch (late: TimeoutException) {
            Outcome.Stuck(clock() - since)
        } catch (failed: ExecutionException) {
            val cause = failed.cause ?: failed
            Outcome.Failed(cause.message ?: cause::class.simpleName ?: "error")
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

/**
 * Ticket 55d60a5e: Docker never restarts an UNHEALTHY container (`restart: unless-stopped` acts on exits only), so a
 * daemon that stops answering stays down until someone runs `docker restart`. This watchdog asks the daemon's own
 * `/health` every [intervalMs] and halts the JVM after [failures] answers in a row that were not 200 (a timeout, a
 * refused connection, a 503 for a stuck database), so the restart policy brings it back.
 *
 * `Runtime.halt`, not `exit`: shutdown hooks close the database, and a database that is stuck is exactly what would make
 * them hang. Off unless [failures] > 0 (`limits.health.watchdogFailures`); the Docker image turns it on.
 */
class HealthWatchdog(
    private val url: URI,
    private val intervalMs: Long,
    private val timeoutMs: Long,
    private val failures: Int,
    private val check: (URI, Long) -> Int = HttpCheck()::status,
    private val onTrip: (String) -> Unit = { reason -> haltProcess(reason) },
) : AutoCloseable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "agenthq-health-watchdog").also { it.isDaemon = true }
    }
    @Volatile private var misses = 0

    /** Consecutive failed checks so far (for the log and tests). */
    val consecutiveFailures: Int get() = misses

    fun start(): HealthWatchdog {
        scheduler.scheduleWithFixedDelay(::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
        return this
    }

    /** One check; public so a test drives it without waiting. */
    fun tick() {
        val status = try {
            check(url, timeoutMs)
        } catch (e: Exception) {
            -1
        }
        if (status == 200) {
            if (misses > 0) println("[agentHq] health watchdog: /health answers again after $misses failed check(s)")
            misses = 0
            return
        }
        misses++
        val what = if (status < 0) "no answer within ${timeoutMs} ms" else "HTTP $status"
        System.err.println("[agentHq] health watchdog: /health failed ($what), $misses of $failures")
        if (misses >= failures) {
            onTrip("/health failed $misses checks in a row (last: $what)")
            scheduler.shutdown()
        }
    }

    override fun close() {
        scheduler.shutdownNow()
    }

    /** One HTTP client for every check (a client per check would leave its selector threads behind each time). */
    private class HttpCheck {
        private val client by lazy { HttpClient.newBuilder().build() }

        fun status(url: URI, timeoutMs: Long): Int {
            val request = HttpRequest.newBuilder(url).timeout(Duration.ofMillis(timeoutMs)).GET().build()
            return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .get(timeoutMs, TimeUnit.MILLISECONDS).statusCode()
        }
    }

    companion object {
        /** The exit status of a watchdog halt, so `docker inspect` / logs tell it apart from a crash. */
        const val EXIT_STATUS = 3


        fun haltProcess(reason: String) {
            System.err.println("[agentHq] health watchdog: $reason; halting the JVM (exit $EXIT_STATUS) so the supervisor restarts it")
            System.err.flush()
            System.out.flush()
            Runtime.getRuntime().halt(EXIT_STATUS)
        }

        /** The address the watchdog asks: the bound host, or loopback when bound to every interface. */
        fun selfUrl(host: String, port: Int): URI {
            val h = when (host) {
                "0.0.0.0", "::", "[::]", "" -> "127.0.0.1"
                else -> host
            }
            val bracketed = if (':' in h && !h.startsWith("[")) "[$h]" else h
            return URI("http://$bracketed:$port$OPEN_PATH")
        }
    }
}
