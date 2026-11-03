package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import com.superiorsoftllc.infantry.ui.protocol.TicketStart
import kotlinx.serialization.json.Json
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NOTICE-COLOUR-1: the notice strip is red only for a real failure. A routine notice (a ticket queued
 * or started, a `/command` that ran) is the theme's success green, a heads-up (a message the busy agent
 * did not take, a held ticket) is amber, and a genuine error stays red. Each case is fed through the real
 * reducer from the daemon frame that raises it, rendered, and the strip's own pixels are read back.
 */
@OptIn(ExperimentalTestApi::class)
class NoticeColourUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun ticketFrame(start: TicketStart) =
        Envelope(Envelope.TICKET, json.encodeToString(Ticket.serializer(), Ticket("t1", "Ship it", status = "in_progress", sessionId = "s1", start = start)))

    /** Renders [frame]'s notice and answers the colour it is drawn in (the strip is written as a PNG for the record). */
    private fun stripColour(name: String, frame: Envelope): Color {
        val state = reduce(AppState(), frame, 1)
        var colour = Color.Unspecified
        runDesktopComposeUiTest(600, 80) {
            setContent { InfantryTheme { NoticeStrip(state, "s1") } }
            val image = onNodeWithTag(NOTICE_TAG).captureToImage().toAwtImage()
            File("build/notice-colour").apply { mkdirs() }.let { ImageIO.write(image, "png", File(it, "$name.png")) }
            // The most saturated pixel is the notice's own colour (its text and edge), never the dark panel.
            val pixels = (0 until image.width).flatMap { x -> (0 until image.height).map { y -> image.getRGB(x, y) } }
            colour = Color(pixels.maxBy { p ->
                val r = p shr 16 and 0xFF; val g = p shr 8 and 0xFF; val b = p and 0xFF
                maxOf(r, g, b) - minOf(r, g, b)
            })
        }
        return colour
    }

    private fun assertDrawnIn(expected: Color, actual: Color, what: String) {
        val near = listOf("green" to Palette.Ok, "amber" to Palette.Warn, "red" to Palette.Danger)
            .minBy { (_, c) -> abs(c.red - actual.red) + abs(c.green - actual.green) + abs(c.blue - actual.blue) }
        val wanted = listOf("green" to Palette.Ok, "amber" to Palette.Warn, "red" to Palette.Danger).first { it.second == expected }.first
        assertEquals(wanted, near.first, "$what: drawn $actual")
    }

    @Test
    fun aQueuedTicketMoveIsGreen() =
        assertDrawnIn(Palette.Ok, stripColour("ticket-queued", ticketFrame(TicketStart(false, "queued", "fullstack"))), "ticket queued on its lane")

    @Test
    fun aStartedTicketIsGreen() =
        assertDrawnIn(Palette.Ok, stripColour("ticket-started", ticketFrame(TicketStart(true, "started", "cut from feature 1a2b3c4d"))), "ticket started")

    @Test
    fun aHeldTicketIsAHeadsUp() =
        assertDrawnIn(Palette.Warn, stripColour("ticket-held", ticketFrame(TicketStart(false, "held", "t0"))), "ticket held by a prerequisite")

    @Test
    fun aMessageTheBusyAgentDidNotTakeIsAHeadsUp() =
        assertDrawnIn(
            Palette.Warn,
            stripColour("busy-agent", Envelope(Envelope.ERROR, "session 97968bee-a41a-4fd5-9a82-2a587e9f940a already has a run in flight")),
            "prompt to a busy agent",
        )

    @Test
    fun aRefusedTicketStartStaysRed() =
        assertDrawnIn(Palette.Danger, stripColour("ticket-refused", ticketFrame(TicketStart(false, "start_refused", "no repo configured"))), "start refused")

    @Test
    fun aGenuineDaemonErrorStaysRed() =
        assertDrawnIn(Palette.Danger, stripColour("daemon-error", Envelope(Envelope.ERROR, "unknown pod 'x'")), "daemon error")
}
