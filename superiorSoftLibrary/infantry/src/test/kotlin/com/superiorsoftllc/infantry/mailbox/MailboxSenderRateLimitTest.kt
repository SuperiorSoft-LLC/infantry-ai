// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * MAIL-3c (ticket 20292c50): the SHIPPED sender rate limit, driven through a real [Mailbox].
 *
 * WHY THIS FILE EXISTS. The rate boundary is asserted in exactly one other place in the repo,
 * [MailboxTest.rate_limit_refuses_and_records_nothing], and that test injects
 * `MailLimits(perSenderPerWindow = 2, windowMs = 600_000)`. It proves that a mailbox CONFIGURED with a
 * limit of two refuses a third send. It does not prove that the mailbox the daemon actually builds --
 * `DaemonMain.kt:476 Mailbox(mailStore)`, i.e. the DEFAULTS 12 / 10 min / 60 -- refuses anything, it
 * does not prove the refusal leaves the file-backed store untouched, and nothing in the repo proved
 * that the window slides.
 *
 * WHY THE DEFAULT LIMITS AND NOT AN INJECTED ONES. The cheap version of this ticket is to inject
 * `MailLimits(perSenderPerWindow = 2)` a second time. Deliberately not: a test that measures a
 * configuration it chose itself cannot notice the SHIPPED default drifting, so if [MailLimits] ever
 * fell from 12 to 10, an injected-2 test would stay green and say nothing. These tests take the
 * default [MailLimits] -- the boundary the daemon ships -- and inject only the clock and the store,
 * which is what the [Mailbox] constructor exists for (see its KDoc). The cost is 13 sends in-process:
 * milliseconds, no daemon, no sleeping, no flakiness.
 */
class MailboxSenderRateLimitTest {

    private val sender = "sender-01"
    private val recipient = "recipient-01"

    /**
     * A store file of this test's own, in a temp directory, deleted up front so the first write is
     * the first thing on disk. [JsonMailStore] is the store the daemon builds, so the on-disk claim
     * is about the real one.
     */
    private fun storeFile(): File =
        File.createTempFile("infantry-mail3c", ".json").apply { deleteOnExit() }.also { it.delete() }

    /** One ordinary send from [sender] to [recipient]; the mailbox's own default limits apply. */
    private fun sendTo(mailbox: Mailbox, n: Int): MailResult =
        mailbox.send(MailSendRequest(from = sender, to = recipient, subject = "m$n", body = "body $n"))

    /**
     * The gap the ticket names first: the boundary that SHIPS. `Mailbox(mailStore)` takes the default
     * [MailLimits], so twelve sends from one sender are stored and the thirteenth is refused -- and a
     * refused send leaves no row ON DISK, not merely none in the mailbox's memory.
     */
    @Test
    fun the_shipped_default_refuses_the_thirteenth_send_and_leaves_the_store_untouched() {
        val file = storeFile()
        // The default limits, on purpose: this is the boundary the daemon constructs.
        val mailbox = Mailbox(JsonMailStore(file), clock = { AT })
        val events = mutableListOf<MailEvent>()
        mailbox.eventSink = { events += it }

        // The verdict below is about the THIRTEENTH send, so the twelve before it must be real. The
        // count is read back three ways -- the mailbox, the recipient's inbox, and a SECOND store
        // reading the file -- BEFORE the refusal is asserted. A test that only asserted the refusal
        // could not tell "the boundary held" from "every send was refused".
        val sent = (1..12).map { sendTo(mailbox, it) }
        assertEquals(
            12,
            sent.count { it is MailResult.Sent },
            "the shipped boundary is 12 sends per sender per 10 min, so all 12 must be accepted; refused: " +
                "${sent.filterIsInstance<MailResult.Refused>().map { it.reason }}",
        )
        assertEquals(12, mailbox.outbox(sender).size, "12 accepted sends must be 12 stored messages")
        assertEquals(12, mailbox.inbox(recipient).size)
        assertEquals(
            12,
            JsonMailStore(file).load().size,
            "the 12 accepted sends must be on disk before anything is said about the 13th",
        )

        val thirteenth = sendTo(mailbox, 13)

        val refusal = assertIs<MailResult.Refused>(
            thirteenth,
            "the 13th send from one sender must be refused by the shipped default limits",
        )
        // The exact string, because this is the text `infantry send` prints to stderr and exits non-zero
        // on (CliMain.kt:635-636 re-emits `MailRefused.reason`): it names the shipped 12 and the
        // window in minutes, so a default that drifted changes the assertion, not just the output.
        assertEquals(
            "rate limit: '$sender' already sent 12 messages in the last 10 min (limit 12)",
            refusal.reason,
        )

        // THE POINT OF THE BOUNDARY: a refused message is not stored, so it cannot be delivered later
        // and the boundary cannot be cosmetic -- in `Mailbox.send`'s own words, a refused message "is
        // NOT stored, because storing it would let it be delivered later and the boundary would have
        // been cosmetic". Quoted rather than cited by line number: the sendHistory comment added above
        // it in this same ticket moved every number in the file by 17. Read from the FILE, not from the
        // mailbox's map: only the disk copy rules out a row that was written and then forgotten, or
        // one that is in the map and was never persisted.
        assertEquals(12, mailbox.outbox(sender).size, "a refused send must not be stored")
        assertEquals(12, mailbox.inbox(recipient).size, "nothing may be addressed to the recipient either")
        assertEquals(12, JsonMailStore(file).load().size, "a refused send must leave no row ON DISK")
        assertTrue(
            events.any { it is MailEvent.Refused },
            "the refusal must be an event, never a silent drop: $events",
        )
        // And it is the RATE limit, not the per-conversation ceiling (60): 12 rows did not hit it.
        assertEquals(12, mailbox.thread(Threads.pair(sender, recipient)).size)
    }

    /**
     * The second gap: the WINDOW SLIDES. Nothing in the repo has ever tested expiry -- the one
     * existing rate-limit test refuses twice in a row, which says nothing about a window that never
     * opens. `sendHistory` is pruned by `while (window.isNotEmpty() && now - window.first() >
     * limits.windowMs) window.removeFirst()`, so expiry is one clock move away with the injected
     * clock. Sleeping 10 minutes instead would be a flaky test on every machine, and this board has
     * already paid for one Windows flake tonight.
     */
    @Test
    fun a_refused_send_is_accepted_once_the_window_has_slid_past_it() {
        val file = storeFile()
        var now = AT
        // Default limits again: the window that slides is the SHIPPED one.
        val mailbox = Mailbox(JsonMailStore(file), clock = { now })

        // Fill the shipped window at a single instant, so every entry ages together and "has the
        // window expired" has one unambiguous answer.
        repeat(12) { n ->
            assertIs<MailResult.Sent>(
                sendTo(mailbox, n + 1),
                "send ${n + 1} of 12 must be accepted at $now",
            )
        }
        val refused = assertIs<MailResult.Refused>(
            sendTo(mailbox, 13),
            "the window is full at $now, so the 13th send must be refused",
        )
        assertTrue(refused.reason.contains("rate limit"), "the refusal must name the boundary: ${refused.reason}")
        assertEquals(12, mailbox.outbox(sender).size, "the refused send must not be stored")

        // ONE MILLISECOND past the window, and the same send goes through. The refusal above is what
        // makes this meaningful: without it, this assertion would also pass on a mailbox that had
        // never refused anything.
        now += SHIPPED_WINDOW_MS + 1
        val accepted = assertIs<MailResult.Sent>(
            sendTo(mailbox, 13),
            "every entry is now $SHIPPED_WINDOW_MS ms old, so the window must have slid open at $now",
        )
        assertEquals(now, accepted.message.sentAt)
        assertEquals(13, mailbox.outbox(sender).size, "the send the window was holding back must now be stored")
        assertEquals(
            13,
            JsonMailStore(file).load().size,
            "and it must be on disk: the refused attempt left nothing behind, this one did",
        )
    }

    /**
     * The window slides PER ENTRY, not as a batch that resets when its oldest member expires. The two
     * are indistinguishable while every send in the window is the same age (which is what the test
     * above does, deliberately): only a window holding two different ages tells them apart, because a
     * batch reset would free all 12 slots where the shipped prune frees only the 8 that are actually
     * older than `limits.windowMs`.
     */
    @Test
    fun the_window_expires_each_send_by_its_own_age_and_not_as_a_batch() {
        val file = storeFile()
        var now = AT
        val mailbox = Mailbox(JsonMailStore(file), clock = { now })
        var n = 0
        fun send(): MailResult = sendTo(mailbox, ++n)

        // An OLD batch and a RECENT batch: the 8 sent at AT are the ones about to expire, the 4 sent
        // at AT + window - 1 are milliseconds old and must still count against the limit.
        repeat(8) { assertIs<MailResult.Sent>(send(), "send $n of the old batch must be accepted") }
        now += SHIPPED_WINDOW_MS - 1
        repeat(4) { assertIs<MailResult.Sent>(send(), "send $n of the recent batch must be accepted") }
        assertIs<MailResult.Refused>(
            send(),
            "the window holds 12 at $now, so send $n must be refused",
        )
        assertEquals(12, mailbox.outbox(sender).size, "the refused send must not be stored")

        // One millisecond past the window. The 8 old sends are now 600_001 ms old and
        // expire; the 4 recent ones are 2 ms old and do not. Exactly 8 sends fit, not 12.
        now += 2
        assertEquals(AT + SHIPPED_WINDOW_MS + 1, now)
        repeat(8) {
            assertIs<MailResult.Sent>(
                send(),
                "send $n: only the 8 expired slots may have been freed -- the 4 recent sends must still " +
                    "count, so a ninth send here means the window was reset instead of slid",
            )
        }
        val overflow = assertIs<MailResult.Refused>(
            send(),
            "the 4 recent sends still count, so send $n must be refused even though the window slid",
        )
        assertTrue(
            overflow.reason.contains("already sent 12 messages"),
            "the refusal must name the same shipped window: ${overflow.reason}",
        )
        // 20 = the 12 that were accepted before the slide + the 8 the expired slots freed. A batch
        // reset would have stored 24; a window that never pruned would have stored 12.
        assertEquals(20, mailbox.outbox(sender).size, "only the 8 expired slots were freed")
        assertEquals(20, JsonMailStore(file).load().size)
    }

    /**
     * The third gap: FAN-OUT counts as N sends from the sender against the same budget
     * (Mailbox.kt:243). Same shape, same absence -- [MailRouterTest] drives fan-out for delivery and
     * exactly-once, never against a limit, and the fan-out refusal string (`would exceed 12 in the last
     * 10 min`) appears in no test.
     */
    @Test
    fun a_fan_out_of_n_counts_as_n_sends_from_the_sender() {
        val file = storeFile()
        val mailbox = Mailbox(JsonMailStore(file), clock = { AT })
        val crew = listOf("worker-1", "worker-2", "worker-3", "worker-4", "worker-5")

        // 8 of the 12 slots are already spent, one at a time, before any dispatch is attempted.
        repeat(8) { n ->
            assertIs<MailResult.Sent>(sendTo(mailbox, n + 1), "send ${n + 1} of 8 must be accepted at $AT")
        }
        assertEquals(8, mailbox.outbox(sender).size, "the 8 accepted sends must be 8 stored rows")
        assertEquals(8, JsonMailStore(file).load().size, "and they must be on disk before the fan-out is read")

        // 8 + 5 > 12: refused as a BATCH. Nothing is stored -- not even the four recipients that would
        // have fitted -- which is the property the batch check exists for (a limit refusal never
        // half-fires), and the reason names the fan-out's own size rather than the window's.
        val oversized = mailbox.fanOut(
            MailFanOutRequest(from = sender, recipients = crew, subject = "dispatch", body = "status?"),
        )
        val refusal = assertIs<FanOutResult.Refused>(
            oversized,
            "a fan-out of 5 on top of 8 sends would make 13 in the window, so it must be refused",
        )
        assertEquals(
            "rate limit: fan-out of 5 from '$sender' would exceed 12 in the last 10 min",
            refusal.reason,
        )
        assertEquals(8, mailbox.outbox(sender).size, "a refused fan-out must store nothing")
        assertEquals(8, JsonMailStore(file).load().size, "and must leave no row on disk")
        assertEquals(
            emptyList(),
            crew.filter { mailbox.inbox(it).isNotEmpty() },
            "no recipient may hold a copy of a fan-out that was refused",
        )

        // The four that DO fit are accepted: 8 + 4 is the shipped limit exactly.
        val fits = assertIs<FanOutResult.Sent>(
            mailbox.fanOut(MailFanOutRequest(from = sender, recipients = crew.take(4), body = "status?")),
            "8 + 4 is 12, the shipped limit exactly, so this batch must be accepted",
        )
        assertEquals(4, fits.messages.size, "one stored message per recipient")
        assertEquals(1, fits.messages.map { it.threadId }.distinct().size, "one fan-out = one collecting thread")
        assertEquals(crew.take(4), fits.messages.map { it.to }, "one copy each, in the order asked")
        assertEquals(12, JsonMailStore(file).load().size, "8 single sends + the 4 copies the batch stored")

        // And the batch SPENT the budget exactly as 4 single sends would have: the window is now full,
        // so the next single send is refused, and so is a fan-out of one.
        assertIs<MailResult.Refused>(sendTo(mailbox, 9), "the fan-out of 4 must have spent 4 slots")
        val last = assertIs<FanOutResult.Refused>(
            mailbox.fanOut(MailFanOutRequest(from = sender, recipients = listOf("worker-5"), body = "still there?")),
            "a fan-out of 1 on a full window is still one send too many",
        )
        assertTrue(last.reason.contains("would exceed 12"), "the refusal must name the shipped limit: ${last.reason}")
        assertEquals(12, mailbox.outbox(sender).size, "no refusal may add a row")
        assertEquals(12, JsonMailStore(file).load().size)
    }

    /**
     * The window's FAR EDGE is inclusive, which is a choice and is now on the record: an entry that is
     * exactly `limits.windowMs` old is still "in the last 10 min", so it still counts. The prune is
     * `now - window.first() > limits.windowMs`; pruning on `>=` instead would treat that entry as
     * expired and let a 13th send through one tick early, which is a boundary nobody chose on purpose.
     * Asserted from BOTH sides -- refused at exactly the edge, accepted one millisecond later -- so the
     * case cannot pass on a window that never opens nor on one that has jammed shut.
     */
    @Test
    fun a_send_exactly_at_the_far_edge_of_the_window_still_counts() {
        val file = storeFile()
        var now = AT
        val mailbox = Mailbox(JsonMailStore(file), clock = { now })

        repeat(12) { n ->
            assertIs<MailResult.Sent>(sendTo(mailbox, n + 1), "send ${n + 1} of 12 must be accepted at $now")
        }
        // Read the count back before the edge is exercised, so "the window is full" is a fact rather
        // than an assumption about the fixture.
        assertEquals(12, mailbox.outbox(sender).size, "the 12 accepted sends must be 12 stored rows")
        assertEquals(12, JsonMailStore(file).load().size)

        // EXACTLY windowMs: the oldest entry is 600_000 ms old and is still inside the window.
        now += SHIPPED_WINDOW_MS
        assertIs<MailResult.Refused>(
            sendTo(mailbox, 13),
            "an entry exactly $SHIPPED_WINDOW_MS ms old is still in the last 10 min, so the window is " +
                "still full at $now and send 13 must be refused",
        )

        // One millisecond past it and the entry is out, so the refusal above is the edge itself.
        now += 1
        assertIs<MailResult.Sent>(
            sendTo(mailbox, 13),
            "1 ms past the edge the oldest entry has expired, so send 13 must go through at $now",
        )
        assertEquals(13, mailbox.outbox(sender).size)
        assertEquals(13, JsonMailStore(file).load().size)
    }

    /**
     * Step C as briefed: a REFUSED send consumes no budget. The window records accepted sends only
     * (`window.addLast(now)` sits after the limit check at Mailbox.kt:159), so consecutive refusals are
     * not occupancy. If a refusal stamped itself into the window, a sender could be locked out by its
     * own repeated attempts: each refusal would extend the busy window the sender is waiting on.
     *
     * Both witnesses are in one case because they are one property: every refusal reports the SAME
     * count (the third field of the refusal reason is the window's size, which the boundary publishes),
     * and once the accepted sends age out, a full 12 fit again -- five refusals stamped half a window
     * ago would still be holding five of the twelve slots.
     */
    @Test
    fun a_refused_send_consumes_no_budget() {
        val file = storeFile()
        var now = AT
        val mailbox = Mailbox(JsonMailStore(file), clock = { now })

        repeat(12) { n ->
            assertIs<MailResult.Sent>(sendTo(mailbox, n + 1), "send ${n + 1} of 12 must be accepted at $now")
        }
        assertEquals(12, mailbox.outbox(sender).size, "the 12 accepted sends must be 12 stored rows")

        // Five attempts, half a window later, all refused. The refusal names the window's size, so if
        // the attempts were being recorded it would climb 12, 13, 14...  Here every one must say 12.
        now += SHIPPED_WINDOW_MS / 2
        val reasons = (1..5).map { attempt ->
            assertIs<MailResult.Refused>(sendTo(mailbox, 13), "attempt $attempt at $now must be refused").reason
        }
        assertEquals(
            setOf("rate limit: '$sender' already sent 12 messages in the last 10 min (limit 12)"),
            reasons.toSet(),
            "every refusal must report the same full window; a growing count means the refused sends " +
                "are consuming budget",
        )
        assertEquals(12, mailbox.outbox(sender).size, "five refusals, still 12 rows")

        // And the lockout that would follow: past the window the 12 ACCEPTED sends opened, twelve more
        // must fit. Five recorded refusals would still be inside the window and only seven would.
        now = AT + SHIPPED_WINDOW_MS + 1
        repeat(12) { n ->
            assertIs<MailResult.Sent>(
                sendTo(mailbox, 100 + n),
                "send ${n + 1} of 12 after the slide: the refusals took no slot, so all twelve fit",
            )
        }
        assertIs<MailResult.Refused>(sendTo(mailbox, 113), "and the window is full again at $now")
        assertEquals(24, mailbox.outbox(sender).size, "12 accepted before the refusals + 12 accepted after")
        assertEquals(24, JsonMailStore(file).load().size)
    }

    private companion object {
        /** A fixed instant, so "the last 10 min" is measured, not waited for. */
        const val AT = 1_700_000_000_000L

        /**
         * The shipped window, restated here rather than read from [MailLimits]: if the default drifts,
         * this constant must be corrected deliberately, not silently followed.
         */
        const val SHIPPED_WINDOW_MS = 600_000L
    }
}
