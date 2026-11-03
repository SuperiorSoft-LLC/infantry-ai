package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MAIL-4: the wire table in `docs/AGENT-QUICKREF.md` must name the frame the daemon ACTUALLY answers.
 *
 * WHY THIS TEST EXISTS, and it is a real user-visible defect rather than a tidiness one. The table
 * documented `mail_thread` as answering `[MAIL]` / `[MAIL_THREADS]`, while `DaemonMain` answers
 * `Envelope.MAILS` — a list of messages. An agent following the doc waits for a frame that never
 * arrives, and the three names mean different things: `[MAIL]` is ONE message (the answer to
 * `mail_send` and `mail_read`), `[MAILS]` is a list of messages, `[MAIL_THREADS]` is a list of thread
 * summaries (the answer to `mail_threads`, which the table did not document at all). The in-code
 * contract (`Protocol.kt`) was right all along; only the markdown drifted.
 *
 * A doc comment cannot fail a build, so nothing caught it. This test closes that loop by parsing the
 * table and asking a REAL daemon what it actually sends.
 *
 * THE TABLE NAMES CONSTANTS, THE WIRE CARRIES VALUES: the table writes `[MAILS]`, while the frame on
 * the socket is `Envelope.MAILS`, whose value is the string `mails`. So a documented name is resolved
 * through [Envelope]'s own constants before it is compared, which also means a table naming something
 * that is not a frame constant at all (`[MAILZ]`, a typo) fails here rather than misleading a reader
 * forever.
 *
 * SCOPE, STATED PLAINLY SO NOBODY OVERCLAIMS IT: it drives the five READ-ONLY mail frames, because
 * those answer deterministically from an empty mailbox and need no delivery setup. `mail_send` and
 * `mail_read` are documented but not driven — answering them honestly needs a delivered message, and
 * a test that faked that would prove less than it appeared to. It also does NOT auto-extend: a NEW
 * `mail_*` row added to the table is not covered until its frame is added to [DRIVEN]. That is a
 * known limit of this guard, not an oversight.
 */
class MailFrameDocTest {

    /** `AGENT-QUICKREF.md`, found by walking up from the test's working directory. */
    private val quickref: String = findQuickref()

    private fun findQuickref(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/AGENT-QUICKREF.md")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("docs/AGENT-QUICKREF.md not found above ${File("").absolutePath}")
    }

    /**
     * Request frame -> the answer frames the table documents for it, parsed from the markdown.
     *
     * The answer frames are the `[UPPER_SNAKE]` tokens in the description column, which is how the
     * table writes them ("Answer: `[MAILS]`"). Bracketed names that are NOT answers (there are none
     * today) would be picked up too, which is why the assertion below is "the daemon's answer is one
     * OF the documented names" rather than "the documented list is exactly one name".
     */
    private fun documentedAnswers(): Map<String, Set<String>> =
        quickref.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("| `mail_") }
            .map { row ->
                val cells = row.split('|').map { it.trim() }
                val request = cells[1].removeSurrounding("`")
                val answers = Regex("\\[([A-Z_]+)]").findAll(cells[3]).map { it.groupValues[1] }.toSet()
                request to answers
            }
            .toMap()

    /**
     * The wire value of a frame constant named in the table, e.g. `MAILS` -> `mails`.
     *
     * Null when the table names something that is not a frame constant, which is a doc defect of its
     * own: an agent would wait for a frame that no daemon can ever send.
     */
    private fun wireValueOf(constant: String): String? =
        Envelope::class.java.fields
            .firstOrNull { it.name == constant && it.type == String::class.java }
            ?.get(null) as String?

    /**
     * Every mail frame the table documents for is a frame the daemon really sends.
     *
     * Three things are checked, and the second is the one that would have caught today's defect: the
     * table must DOCUMENT an answer (a row naming none leaves an agent guessing), every name it does
     * document must be a real frame constant, and the daemon's actual answer must be one of them.
     */
    @Test
    fun theDocumentedAnswerFramesAreTheOnesTheDaemonSends() {
        val documented = documentedAnswers()
        assertTrue(
            documented.isNotEmpty(),
            "no mail_* rows parsed out of AGENT-QUICKREF.md — the table's shape changed and this " +
                "guard would otherwise pass on an empty map",
        )
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            for (request in DRIVEN) {
                val answers = assertNotNull(
                    documented[request],
                    "AGENT-QUICKREF.md documents no row for '$request', so an agent following it " +
                        "has no answer frame to wait for",
                )
                assertTrue(answers.isNotEmpty(), "AGENT-QUICKREF.md names no answer frame for '$request'")

                val values = answers.map { name ->
                    assertNotNull(
                        wireValueOf(name),
                        "AGENT-QUICKREF.md says '$request' answers [$name], which is not an Envelope " +
                            "frame constant at all",
                    )
                }

                val from = fixture.client.mark()
                fixture.client.send(Envelope(request, ""))
                // The first frame after the request that is not the handshake or an error is the
                // answer: its TYPE is the whole point, and asking for it by name would assume the
                // very fact this test exists to check.
                val answer = fixture.client.awaitAfter(from, "the answer to '$request'") {
                    it.type != Envelope.READY && it.type != Envelope.ERROR
                }
                assertTrue(
                    answer.type in values,
                    "'$request' is documented as answering ${values.joinToString(" or ") { "'$it'" }} " +
                        "but the daemon actually sent '${answer.type}'. Either the table or " +
                        "DaemonMain's handler is wrong, and an agent following the doc waits forever.",
                )
            }
        } finally {
            fixture.stop()
        }
    }

    private companion object {
        /** The read-only mail frames: deterministic answers, no delivery setup needed. */
        val DRIVEN = listOf(
            Envelope.MAIL_INBOX,
            Envelope.MAIL_OUTBOX,
            Envelope.MAIL_THREAD,
            Envelope.MAIL_THREADS,
            Envelope.MAIL_WAITING,
        )
    }
}