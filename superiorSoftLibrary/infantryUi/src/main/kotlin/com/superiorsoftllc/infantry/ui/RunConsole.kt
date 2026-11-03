package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.superiorsoftllc.infantry.ui.model.RunConsoleFeed
import com.superiorsoftllc.infantry.ui.model.runPill
import com.superiorsoftllc.infantry.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Font
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/**
 * RUN-CONSOLE (ticket 67f117b6): JediTerm's view of a run the DAEMON owns. Bytes come in through [feed] (from the
 * daemon's `run_bytes`, ordered by [RunConsoleFeed]); keys go out through [sendInput] only while [interactive]
 * says so, a read-only console drops them (and the paste that would type them). A resize goes to [sendResize],
 * which resizes the run's pty. Not tied to a run: the same connector can front any byte stream later (the pure CLI).
 */
class StreamTtyConnector(
    private val name: String,
    private val interactive: () -> Boolean,
    private val sendInput: (ByteArray) -> Unit,
    private val sendResize: (cols: Int, rows: Int) -> Unit,
) : TtyConnector {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)
    private val queue = LinkedBlockingQueue<CharArray>()
    private var chunk: CharArray = CharArray(0)
    private var at = 0
    @Volatile private var open = true

    /** Raw terminal bytes; UTF-8 split across calls is decoded whole. */
    @Synchronized
    fun feed(bytes: ByteArray) {
        if (!open || bytes.isEmpty()) return
        val input = ByteBuffer.wrap(if (pending.isEmpty()) bytes else pending + bytes)
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        out.flip()
        if (out.hasRemaining()) queue.put(CharArray(out.remaining()).also { out.get(it) })
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        while (at >= chunk.size) {
            if (!open && queue.isEmpty()) return -1
            chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            at = 0
        }
        val n = minOf(length, chunk.size - at)
        chunk.copyInto(buf, offset, at, at + n)
        at += n
        return n
    }

    override fun write(bytes: ByteArray) { if (interactive()) sendInput(bytes) }
    override fun write(string: String) = write(string.toByteArray(Charsets.UTF_8))
    override fun isConnected(): Boolean = open
    override fun resize(termSize: TermSize) = sendResize(termSize.columns, termSize.rows)
    override fun waitFor(): Int { while (open) Thread.sleep(200); return 0 }
    override fun ready(): Boolean = at < chunk.size || queue.isNotEmpty()
    override fun getName(): String = name
    override fun close() { open = false }
}

/**
 * The console's JediTerm settings: the app's theme colours and monospace font, a scrollback of
 * `infantry.console.scrollbackLines` (system property, or env INFANTRY_CONSOLE_SCROLLBACK_LINES; the daemon's own
 * cap is the setting `limits.run.scrollbackBytes`), no paste on a middle click, no bell.
 */
class ConsoleSettings : DefaultSettingsProvider() {
    private fun jedi(c: androidx.compose.ui.graphics.Color): TerminalColor {
        val argb = c.toArgb()
        return TerminalColor((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }
    override fun getDefaultForeground(): TerminalColor = jedi(Palette.Text)
    override fun getDefaultBackground(): TerminalColor = jedi(Palette.Panel)
    override fun getDefaultStyle(): TextStyle = TextStyle(defaultForeground, defaultBackground)
    override fun getTerminalFont(): Font = Font(Font.MONOSPACED, Font.PLAIN, terminalFontSize.toInt())
    override fun getTerminalFontSize(): Float = 13f
    override fun pasteOnMiddleMouseClick(): Boolean = false
    override fun audibleBell(): Boolean = false
    override fun getBufferMaxLinesCount(): Int = scrollbackLines()

    companion object {
        const val SCROLLBACK_PROPERTY = "infantry.console.scrollbackLines"
        const val SCROLLBACK_ENV = "INFANTRY_CONSOLE_SCROLLBACK_LINES"
        private const val DEFAULT_SCROLLBACK = 10_000

        fun scrollbackLines(
            property: (String) -> String? = System::getProperty,
            env: (String) -> String? = System::getenv,
        ): Int = (property(SCROLLBACK_PROPERTY) ?: env(SCROLLBACK_ENV))?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_SCROLLBACK
    }
}

/** One open console: the widget, its connector, and the feed that orders the daemon's bytes into it. */
class RunConsoleSession(private val runId: String, private val controller: Controller, scope: CoroutineScope, label: String) {
    private fun run() = controller.state.value.runs.runs[runId]
    private fun running() = run()?.state?.let { it != "FINISHED" } ?: false

    val connector = StreamTtyConnector(
        label,
        interactive = { run()?.interactive == true && running() },
        sendInput = { bytes -> scope.launch { controller.sendRunInput(runId, bytes) } },
        sendResize = { cols, rows -> if (running()) scope.launch { controller.resizeRun(runId, cols, rows) } },
    )
    private val feed = RunConsoleFeed(runId, connector::feed) { scope.launch { controller.attachRun(runId) } }

    val widget: JediTermWidget = JediTermWidget(ConsoleSettings()).apply {
        setTtyConnector(connector)
        start()
    }

    init {
        // Listen before asking: the replay must not slip past.
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.runBytes.collect { feed.accept(it) } }
        scope.launch { controller.attachRun(runId) }
    }

    fun close() {
        connector.close()
        runCatching { widget.close() }
    }
}

/**
 * The desktop's [RunConsoleHost]: one console window per run, opened from its pill; a second click on the pill
 * brings the open one to the front. [Windows] is composed beside the main window, inside the application.
 */
class DesktopRunConsoles : RunConsoleHost {
    private val open = mutableStateListOf<Pair<String, Controller>>()
    private val raise = mutableStateMapOf<String, Int>()

    override fun open(runId: String, controller: Controller) {
        if (open.none { it.first == runId }) open += runId to controller
        raise[runId] = (raise[runId] ?: 0) + 1
    }

    @Composable
    fun Windows() {
        for ((runId, controller) in open.toList()) key(runId) {
            RunConsoleWindow(runId, controller, raise[runId] ?: 0) { open.removeAll { it.first == runId }; raise.remove(runId) }
        }
    }
}

@Composable
private fun RunConsoleWindow(runId: String, controller: Controller, raise: Int, onClose: () -> Unit) {
    val state by controller.state.collectAsState()
    val run = state.runs.runs[runId]
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    val going = run != null && run.state != "FINISHED"
    LaunchedEffect(going) { while (going) { delay(1000); now = Clock.System.now().toEpochMilliseconds() } }
    val pill = run?.let { runPill(it, state.runs.commands, now) }
    val title = "${pill?.label ?: "Run"} - console"
    Window(onCloseRequest = onClose, title = title, state = rememberWindowState(size = DpSize(960.dp, 600.dp))) {
        LaunchedEffect(raise) { window.toFront() }
        val scope = rememberCoroutineScope()
        val session = remember { RunConsoleSession(runId, controller, scope, title) }
        DisposableEffect(session) { onDispose { session.close() } }
        Column(Modifier.fillMaxSize().background(Palette.Bg)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pill?.let { RunPillChip(it, selected = true, onClick = {}) }
                Text(
                    if (run?.interactive == true && going) "interactive" else "read-only",
                    color = Palette.Muted, fontSize = 11.sp,
                )
                Box(Modifier.weight(1f))
                if (going) {
                    OutlinedButton(onClick = { scope.launch { controller.stopRun(runId) } }) { Text("Stop", fontSize = 12.sp) }
                }
            }
            SwingPanel(factory = { session.widget }, modifier = Modifier.fillMaxSize())
        }
    }
}
