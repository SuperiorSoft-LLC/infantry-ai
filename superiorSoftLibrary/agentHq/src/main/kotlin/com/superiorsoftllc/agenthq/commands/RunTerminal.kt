package com.superiorsoftllc.agenthq.commands

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * RUN-CONSOLE (ticket 67f117b6): the raw terminal bytes of one run, newest kept, oldest dropped past [capBytes]
 * (setting `limits.run.scrollbackBytes`, read on every append so a change applies to a run already going).
 * A console that attaches late, or to a finished run, replays [snapshot].
 */
class ByteScrollback(private val capBytes: () -> Int) {
    private val chunks = ArrayDeque<ByteArray>()
    private var size = 0L

    /** Every byte ever appended, kept or not. */
    var totalBytes = 0L
        private set

    /** Bytes dropped from the front by the cap. */
    val droppedBytes: Long get() = totalBytes - size

    @Synchronized
    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        totalBytes += bytes.size
        chunks.addLast(bytes)
        size += bytes.size
        trim(capBytes().toLong())
    }

    @Synchronized
    fun snapshot(): ByteArray {
        val out = ByteArray(size.toInt())
        var at = 0
        for (c in chunks) { c.copyInto(out, at); at += c.size }
        return out
    }

    private fun trim(cap: Long) {
        while (size > cap && chunks.isNotEmpty()) {
            val first = chunks.first()
            val over = size - cap
            if (first.size <= over) {
                chunks.removeFirst()
                size -= first.size
            } else {
                // Cut inside the first chunk, then on to its next line break if there is one, so a replay does not
                // open in the middle of a line or an escape sequence.
                var from = over.toInt()
                val nl = (from until first.size).firstOrNull { first[it] == '\n'.code.toByte() }
                if (nl != null && nl + 1 < first.size) from = nl + 1
                chunks[0] = first.copyOfRange(from, first.size)
                size -= from
            }
        }
    }
}

/**
 * The plain-text view of a terminal stream, one finished line at a time, for the clients that have no terminal
 * emulator (web, Android, the line pane of an older desktop): colours and every other escape sequence are dropped,
 * a `\r` returns to the start of the line and what follows overwrites it, a backspace steps back, an erase-line
 * clears. A line is handed to [onLine] at its `\n` (or at [flush]). Not an emulator: cursor moves across lines
 * (full-screen tools) are not followed. UTF-8 split across chunks is decoded whole.
 */
class AnsiLineDecoder(private val onLine: (String) -> Unit) {
    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)
    private val line = StringBuilder()
    private var col = 0
    private var mode = Mode.TEXT
    private val params = StringBuilder()
    private var dirty = false

    private enum class Mode { TEXT, ESC, ESC_ONE, CSI, OSC, OSC_ESC }

    fun feed(bytes: ByteArray) {
        val input = ByteBuffer.wrap(if (pending.isEmpty()) bytes else pending + bytes)
        val chars = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, chars, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        chars.flip()
        while (chars.hasRemaining()) char(chars.get())
    }

    /** The unfinished last line, if anything was written to it (the end of a run). */
    fun flush() {
        if (dirty || line.isNotEmpty()) emit()
    }

    private fun char(c: Char) {
        when (mode) {
            Mode.TEXT -> text(c)
            Mode.ESC -> when (c) {
                '[' -> { mode = Mode.CSI; params.setLength(0) }
                ']' -> mode = Mode.OSC
                '(', ')', '*', '+', '#', '%' -> mode = Mode.ESC_ONE // a charset or line-size choice: one more char
                else -> mode = Mode.TEXT
            }
            Mode.ESC_ONE -> mode = Mode.TEXT
            Mode.CSI -> if (c.code in 0x40..0x7E) { csi(c, params.toString()); mode = Mode.TEXT } else params.append(c)
            // OSC (titles, hyperlinks, clipboard): never shown, never acted on. Ends at BEL or ESC \.
            Mode.OSC -> when (c) {
                '\u0007' -> mode = Mode.TEXT
                '\u001b' -> mode = Mode.OSC_ESC
                else -> {}
            }
            Mode.OSC_ESC -> mode = if (c == '\\') Mode.TEXT else Mode.OSC
        }
    }

    private fun text(c: Char) {
        when (c) {
            '\u001b' -> mode = Mode.ESC
            '\n' -> emit()
            '\r' -> col = 0
            '\b' -> if (col > 0) col--
            '\t' -> { val next = (col / 8 + 1) * 8; while (col < next) put(' ') }
            else -> if (c >= ' ' && c != '\u007f') put(c)
        }
    }

    private fun put(c: Char) {
        if (col < line.length) line.setCharAt(col, c) else { while (line.length < col) line.append(' '); line.append(c) }
        col++
        dirty = true
    }

    private fun csi(final: Char, p: String) {
        val n = p.trimStart('?').substringBefore(';').toIntOrNull()
        when (final) {
            'K' -> when (n ?: 0) {
                0 -> if (col < line.length) line.setLength(col)
                1 -> for (i in 0 until minOf(col + 1, line.length)) line.setCharAt(i, ' ')
                else -> line.setLength(0)
            }
            'G' -> col = ((n ?: 1) - 1).coerceAtLeast(0)
            'C' -> col += (n ?: 1).coerceAtLeast(1)
            'D' -> col = (col - (n ?: 1).coerceAtLeast(1)).coerceAtLeast(0)
            else -> {} // colours (m) and everything else: dropped
        }
    }

    private fun emit() {
        onLine(line.toString().trimEnd())
        line.setLength(0)
        col = 0
        dirty = false
    }
}
