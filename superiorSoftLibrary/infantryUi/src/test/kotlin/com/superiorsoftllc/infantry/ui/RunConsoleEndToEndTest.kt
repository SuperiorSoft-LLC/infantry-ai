package com.superiorsoftllc.infantry.ui

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.TtyBasedArrayDataStream
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.TerminalDisplay
import com.superiorsoftllc.infantry.plugins.official.run.daemon.CommandRegistry
import com.superiorsoftllc.infantry.plugins.official.run.daemon.RunManager
import com.superiorsoftllc.infantry.daemon.plugins.LocalRuns
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import com.superiorsoftllc.infantry.runner.Pty4jTerminalRunner
import com.superiorsoftllc.infantry.ui.model.RunBytesPayload
import com.superiorsoftllc.infantry.ui.model.RunConsoleFeed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RUN-CONSOLE (ticket 67f117b6) end to end, minus the window: a saved command that draws a `\r` progress bar in
 * colour runs in the daemon's real pty (RunManager + pty4j); its `run_bytes` frames go through the console's own
 * path ([RunConsoleFeed] -> [StreamTtyConnector]) into JediTerm's emulator, and the SCREEN BUFFER is asserted: the
 * bar ends at 100% on one line in green (the 0% .. 75% states were overwritten), then "done" in red.
 */
class RunConsoleEndToEndTest {
    private val windows = System.getProperty("os.name").lowercase().contains("win")
    private val json = Json { ignoreUnknownKeys = true }

    private fun loader(): List<String> = if (windows) listOf(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        "\$e=[char]27; \$r=[char]13; \$n=[char]10; foreach(\$i in 0,25,50,75,100){ " +
            "[Console]::Out.Write(\$r + \$e + '[32mprogress ' + \$i + '%' + \$e + '[0m'); Start-Sleep -Milliseconds 80 }; " +
            "[Console]::Out.Write(\$r + \$n + \$e + '[31mdone' + \$e + '[0m' + \$r + \$n)",
    ) else listOf(
        "sh", "-c",
        "for i in 0 25 50 75 100; do printf '\\r\\033[32mprogress %s%%\\033[0m' \$i; sleep 0.08; done; printf '\\r\\n\\033[31mdone\\033[0m\\r\\n'",
    )

    private object NoDisplay : TerminalDisplay {
        override fun setCursor(x: Int, y: Int) {}
        override fun setCursorShape(shape: CursorShape?) {}
        override fun beep() {}
        override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) {}
        override fun setCursorVisible(isCursorVisible: Boolean) {}
        override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) {}
        override fun getWindowTitle(): String = ""
        override fun setWindowTitle(windowTitle: String) {}
        override fun getSelection(): TerminalSelection? = null
        override fun terminalMouseModeSet(mouseMode: MouseMode) {}
        override fun setMouseFormat(mouseFormat: MouseFormat) {}
        override fun ambiguousCharsAreDoubleWidth(): Boolean = false
    }

    @Test fun `a colour progress loader in the daemon's pty ends on one green 100 percent line in JediTerm's screen`() {
        val repo = Files.createTempDirectory("infantry-console-e2e").toFile()
        val argv = JsonArray(loader().map { JsonPrimitive(it) })
        val registry = CommandRegistry.parse("""[{"id":"loader","name":"loader","argv":$argv}]""", repo)

        // The console: JediTerm's emulator over the connector the window uses.
        val styles = StyleState()
        val buffer = TerminalTextBuffer(100, 12, styles, 1000)
        val terminal = JediTerminal(NoDisplay, buffer, styles)
        val connector = StreamTtyConnector("loader", { false }, {}, { _, _ -> })
        val emulator = JediEmulator(TtyBasedArrayDataStream(connector), terminal)
        val pump = thread(isDaemon = true) { runCatching { while (emulator.hasNext()) emulator.next() } }

        // The daemon side, with every frame it would send to a client.
        val frames = CopyOnWriteArrayList<Envelope>()
        lateinit var console: RunConsoleFeed
        val runs = LocalRuns(repo, repo, { null }, PipedProcessRunner(), Pty4jTerminalRunner(), { null })
        val manager = RunManager({ registry }, runs, { env ->
            frames += env
            if (env.type == Envelope.RUN_BYTES) console.accept(json.decodeFromString(RunBytesPayload.serializer(), env.data))
        })
        val started = manager.start("""{"commandId":"loader","cols":100,"rows":12}""")
        val runId = json.decodeFromString(com.superiorsoftllc.infantry.plugins.official.run.daemon.RunRecord.serializer(), started.reply.data).runId
        console = RunConsoleFeed(runId, connector::feed) {}
        // The window asks for the replay as it opens (empty here, sequence 0), then the live bytes follow.
        console.accept(json.decodeFromString(RunBytesPayload.serializer(), manager.attach("""{"runId":"$runId"}""").data))
        started.launch()

        val end = System.nanoTime() + 60_000_000_000L
        fun screen(): List<String> { buffer.lock(); try { return buffer.getScreenLines().lines() } finally { buffer.unlock() } }
        while (System.nanoTime() < end && screen().none { it.trim() == "done" }) Thread.sleep(50)
        connector.close()
        pump.join(2000)

        val lines = screen().map { it.trimEnd() }
        assertTrue(frames.any { it.type == Envelope.RUN_FINISHED && it.data.contains("SUCCEEDED") }, "the loader did not succeed: $frames")
        val bar = lines.indexOfFirst { it.trim() == "progress 100%" }
        assertTrue(bar >= 0, "no 'progress 100%' line on the screen:\n${lines.joinToString("\n")}")
        assertFalse(lines.any { it.contains("progress 0%") || it.contains("progress 75%") }, "an overwritten state is still shown:\n$lines")
        assertEquals("done", lines[bar + 1].trim(), "'done' must be the next line:\n$lines")

        buffer.lock()
        try {
            val barLine = buffer.getLine(bar)
            val col = barLine.text.indexOf("progress")
            val green = barLine.getStyleAt(col)?.foreground
            assertTrue(green != null && green.isIndexed && green.colorIndex == 2, "the bar is not green (SGR 32): $green")
            val doneLine = buffer.getLine(bar + 1)
            val red = doneLine.getStyleAt(doneLine.text.indexOf("done"))?.foreground
            assertTrue(red != null && red.isIndexed && red.colorIndex == 1, "'done' is not red (SGR 31): $red")
        } finally { buffer.unlock() }
    }

    @Test fun `a read-only console drops keys and paste, an interactive one sends them, a resize goes to the pty`() {
        val sent = mutableListOf<String>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        var interactive = false
        val c = StreamTtyConnector("t", { interactive }, { sent += String(it) }, { cols, rows -> sizes += cols to rows })
        c.write("ls\r")
        assertTrue(sent.isEmpty())
        interactive = true
        c.write("y\r")
        assertEquals(listOf("y\r"), sent)
        c.resize(TermSize(132, 43))
        assertEquals(listOf(132 to 43), sizes)
    }

    @Test fun `the connector decodes utf-8 split across chunks and ends after close`() {
        val c = StreamTtyConnector("t", { false }, {}, { _, _ -> })
        val bytes = "✓ ok".toByteArray()
        c.feed(bytes.copyOfRange(0, 1)); c.feed(bytes.copyOfRange(1, bytes.size))
        val buf = CharArray(16)
        val n = c.read(buf, 0, buf.size)
        assertEquals("✓ ok", String(buf, 0, n))
        c.close()
        assertEquals(-1, c.read(buf, 0, buf.size))
    }

    @Test fun `the console scrollback is configurable`() {
        assertEquals(500, ConsoleSettings.scrollbackLines(property = { "500" }, env = { null }))
        assertEquals(42, ConsoleSettings.scrollbackLines(property = { null }, env = { "42" }))
        assertTrue(ConsoleSettings.scrollbackLines(property = { null }, env = { null }) > 0)
        assertTrue(ConsoleSettings.scrollbackLines(property = { "-3" }, env = { null }) > 0)
    }
}
