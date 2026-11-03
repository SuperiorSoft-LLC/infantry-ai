// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.daemon.awaitAfter
import com.superiorsoftllc.infantry.daemon.mark
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.runner.StubScript
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CLI-T3 patch (675e7c4c): `ticket create` is NOT idempotent. When the connection drops after the daemon has
 * received the create but before the answer arrives, the CLI must REFUSE and must NOT re-send, because a
 * second copy would be a second ticket.
 *
 * "A retry was refused" is not evidence on its own: from the CLI, a request the daemon never received and one
 * the daemon stored look identical. So this test proves it by DAEMON STATE, counting the board before and after:
 *
 *  1. the count before the create is `before`;
 *  2. the create goes through [AnswerHoldingRelay], which lets the request reach the daemon and HOLDS the
 *     daemon's `ticket` answer, so the client never sees it. The test waits until the board reads `before + 1`:
 *     the daemon has stored the ticket exactly once, and the answer is the only thing the client is missing;
 *  3. the relay drops the link. The CLI must refuse with a message saying it is unknown whether the create was
 *     applied, and it must not send a second copy;
 *  4. after a settle period the board must still read `before + 1`. A re-sent create would make it `before + 2`.
 *
 * The relay asserts that it really held one ticket answer, so step 2 cannot pass without the loss it is about.
 */
class TicketCreateNotIdempotentTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The board's ticket count, read from the daemon directly (not through the relay). */
    private fun boardCount(fixture: DaemonFixture): Int {
        val from = fixture.client.mark()
        fixture.client.send(Envelope(Envelope.TICKET_LIST, ""))
        val frame = fixture.client.awaitAfter(from, "the board") { env -> env.type == Envelope.TICKETS }
        return json.decodeFromString(ListSerializer(Ticket.serializer()), frame.data).size
    }

    @Test
    fun aCreateWhoseAnswerIsLostIsRefusedAndTheDaemonStoresExactlyOneTicket() {
        val fixture = DaemonFixture.launch(
            StubScript.default(),
            null,
            Files.createTempDirectory("infantry-create-not-idempotent").toFile(),
        )
        val relay = AnswerHoldingRelay(fixture.port)
        try {
            fixture.client.await(Envelope.READY)
            val before = boardCount(fixture)
            relay.holdTicketAnswers()

            val out = StringBuilder()
            val err = StringBuilder()
            var exit = -1
            val worker = thread(name = "cli-ticket-create") {
                exit = Cli.run(
                    arrayOf("ticket", "create", "--title", "Lost answer", "--project", "proj-lost", "--port", relay.port.toString()),
                    out,
                    err,
                    CliTestEnv.getenv,
                )
            }

            // Step 2: wait until the daemon has stored the create (bounded, so a hang fails instead of stalling).
            val deadline = System.currentTimeMillis() + 15_000
            while (boardCount(fixture) == before && System.currentTimeMillis() < deadline) Thread.sleep(25)
            val storedBeforeLoss = boardCount(fixture)
            assertEquals(
                before + 1,
                storedBeforeLoss,
                "the daemon must store the create once before its answer is lost (before=$before, now=$storedBeforeLoss)",
            )
            assertEquals(1, relay.heldTicketAnswers.get(), "the relay must have held exactly one ticket answer, or the drop proves nothing")

            // Step 3: sever the link while the answer is held.
            relay.dropAll()
            worker.join(30_000)
            assertFalse(worker.isAlive, "the create must give up when its answer is lost, not hang")

            // Step 4, the decisive check, made first: give any re-send time to land, then count the board.
            Thread.sleep(1_000)
            val after = boardCount(fixture)
            assertEquals(
                before + 1,
                after,
                "the refused create was re-sent: the board went from ${before + 1} to $after (a second ticket)",
            )
            assertTrue(exit != 0, "a create whose answer was lost must not report success: exit=$exit out=$out err=$err")
            assertTrue(
                err.contains("unknown whether it was applied"),
                "the refusal must say the create's outcome is unknown: err=$err",
            )
            assertFalse(out.contains("created "), "a refused create must not print an id: out=$out")
        } finally {
            relay.close()
            runCatching { fixture.stop() }
            runCatching { fixture.deleteState() }
        }
    }

    /**
     * A TCP pass-through that, once [holdTicketAnswers] is set, delivers every daemon frame EXCEPT a `ticket`
     * answer: that frame is held until the link is dropped, so the client never receives it. The HTTP upgrade
     * response and every other frame pass through untouched. Only server-to-client (unmasked) frames are read,
     * and only as far as their length headers say.
     */
    private class AnswerHoldingRelay(private val targetPort: Int) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val live = CopyOnWriteArrayList<Socket>()

        @Volatile
        private var listening = true

        @Volatile
        private var holding = false

        /** How many `ticket` answers the relay has held (and therefore the client never received). */
        val heldTicketAnswers = AtomicInteger(0)

        val port: Int = server.localPort

        init {
            thread(isDaemon = true, name = "answer-holding-relay-accept") {
                while (listening) {
                    val client = try {
                        server.accept()
                    } catch (e: IOException) {
                        break
                    }
                    val upstream = try {
                        Socket("127.0.0.1", targetPort)
                    } catch (e: IOException) {
                        client.close()
                        continue
                    }
                    live += client
                    live += upstream
                    pipeUpward(client, upstream)
                    pipeDownward(upstream, client)
                }
            }
        }

        fun holdTicketAnswers() {
            holding = true
        }

        private fun pipeUpward(from: Socket, to: Socket) {
            thread(isDaemon = true, name = "answer-holding-relay-up") {
                try {
                    from.getInputStream().copyTo(to.getOutputStream())
                } catch (e: IOException) {
                    // Severed: closing both ends below is the whole response.
                } finally {
                    runCatching { from.close() }
                    runCatching { to.close() }
                }
            }
        }

        private fun pipeDownward(from: Socket, to: Socket) {
            thread(isDaemon = true, name = "answer-holding-relay-down") {
                try {
                    val input = BufferedInputStream(from.getInputStream())
                    val output = to.getOutputStream()
                    copyHttpResponseHead(input, output)
                    while (true) {
                        val frame = readFrame(input) ?: break
                        if (holding && String(frame.payload, Charsets.UTF_8).contains("\"type\":\"ticket\"")) {
                            heldTicketAnswers.incrementAndGet()
                            // Hold it: nothing more reaches the client on this link until the test drops it.
                            while (!from.isClosed) Thread.sleep(10)
                            break
                        }
                        output.write(frame.raw)
                        output.flush()
                    }
                } catch (e: IOException) {
                    // Severed.
                } finally {
                    runCatching { from.close() }
                    runCatching { to.close() }
                }
            }
        }

        /** Copies the HTTP upgrade response, through its blank line, untouched. */
        private fun copyHttpResponseHead(input: InputStream, output: OutputStream) {
            val tail = ByteArray(4)
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("the daemon closed before the handshake ended")
                output.write(b)
                tail[0] = tail[1]; tail[1] = tail[2]; tail[2] = tail[3]; tail[3] = b.toByte()
                if (tail[0] == '\r'.code.toByte() && tail[1] == '\n'.code.toByte() &&
                    tail[2] == '\r'.code.toByte() && tail[3] == '\n'.code.toByte()
                ) {
                    output.flush()
                    return
                }
            }
        }

        private class Frame(val raw: ByteArray, val payload: ByteArray)

        /** One unmasked server frame, read by its length header; null at a clean end of stream. */
        private fun readFrame(input: InputStream): Frame? {
            val first = input.read()
            if (first < 0) return null
            val second = input.read()
            if (second < 0) throw IOException("frame header cut short")
            val lengthCode = second and 0x7f
            val extraLengthBytes = when (lengthCode) {
                126 -> 2
                127 -> 8
                else -> 0
            }
            val extra = readExactly(input, extraLengthBytes)
            val length = when (extraLengthBytes) {
                2 -> ((extra[0].toInt() and 0xff) shl 8) or (extra[1].toInt() and 0xff)
                8 -> ByteBuffer.wrap(extra).long.toInt()
                else -> lengthCode
            }
            val payload = readExactly(input, length)
            val raw = byteArrayOf(first.toByte(), second.toByte()) + extra + payload
            return Frame(raw, payload)
        }

        private fun readExactly(input: InputStream, count: Int): ByteArray {
            val bytes = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val n = input.read(bytes, filled, count - filled)
                if (n < 0) throw IOException("stream ended inside a frame")
                filled += n
            }
            return bytes
        }

        fun dropAll() {
            live.forEach { runCatching { it.close() } }
            live.clear()
        }

        override fun close() {
            listening = false
            runCatching { server.close() }
            dropAll()
        }
    }
}
