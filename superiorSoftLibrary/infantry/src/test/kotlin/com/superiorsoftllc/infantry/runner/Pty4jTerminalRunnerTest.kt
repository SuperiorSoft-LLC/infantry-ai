package com.superiorsoftllc.infantry.runner

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RUN-CONSOLE (ticket 67f117b6): the real pty (pty4j: ConPTY on Windows, the POSIX pty elsewhere). The child sees a
 * terminal, and a resize reaches it: it asks its own terminal for its width after the resize.
 */
class Pty4jTerminalRunnerTest {
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    private class Capture : TerminalListener {
        val out = ByteArrayOutputStream()
        val exited = CountDownLatch(1)
        @Volatile var code = -99
        override fun onBytes(bytes: ByteArray) { synchronized(out) { out.write(bytes) } }
        override fun onExit(exitCode: Int) { code = exitCode; exited.countDown() }
        fun text() = synchronized(out) { out.toString(Charsets.UTF_8) }
    }

    /** Prints the terminal's width once [delayMs] have passed, as `cols=<n>`. */
    private fun widthProbe(delayMs: Int): List<String> =
        if (windows) listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "Start-Sleep -Milliseconds $delayMs; Write-Output ('cols=' + [Console]::WindowWidth)")
        else listOf("sh", "-c", "sleep ${delayMs / 1000.0}; echo cols=\$(stty size | cut -d' ' -f2)")

    @Test fun `the child runs in a terminal of the asked size`() {
        val c = Capture()
        Pty4jTerminalRunner().start(ProcessSpec(widthProbe(0)), 91, 30, c)
        assertTrue(c.exited.await(60, TimeUnit.SECONDS), "the probe did not exit: ${c.text()}")
        assertTrue(c.text().contains("cols=91"), "expected cols=91 in: ${c.text()}")
        assertEquals(0, c.code)
    }

    @Test fun `a resize reaches the child`() {
        val c = Capture()
        val p = Pty4jTerminalRunner().start(ProcessSpec(widthProbe(3000)), 80, 24, c)
        p.resize(133, 40)
        assertTrue(c.exited.await(60, TimeUnit.SECONDS), "the probe did not exit: ${c.text()}")
        assertTrue(c.text().contains("cols=133"), "expected cols=133 after the resize in: ${c.text()}")
    }

    @Test fun `an argument with quotes reaches the child as given`() {
        // The double space survives only if the quotes around it do (a bare `"` would be eaten by the child's parser).
        val c = Capture()
        val argv = if (windows) listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Write-Output \"said  it\"")
        else listOf("sh", "-c", "echo \"said  it\"")
        Pty4jTerminalRunner().start(ProcessSpec(argv), 80, 24, c)
        assertTrue(c.exited.await(60, TimeUnit.SECONDS), "the child did not exit: ${c.text()}")
        assertTrue(c.text().contains("said  it"), "expected 'said  it' in: ${c.text()}")
        assertEquals(0, c.code)
    }

    @Test fun `the windows command line quotes and escapes each argument the msvcrt way`() {
        assertEquals("a.exe plain", windowsCommandLine(listOf("a.exe", "plain")))
        assertEquals("a.exe \"two words\" \"\"", windowsCommandLine(listOf("a.exe", "two words", "")))
        assertEquals("a.exe \"say \\\"hi\\\"\"", windowsCommandLine(listOf("a.exe", "say \"hi\"")))
        assertEquals("a.exe \"C:\\dir with space\\\\\"", windowsCommandLine(listOf("a.exe", "C:\\dir with space\\")))
        assertEquals("a.exe \"x\\\\\\\"y\"", windowsCommandLine(listOf("a.exe", "x\\\"y")))
        assertEquals("a.exe C:\\no\\space", windowsCommandLine(listOf("a.exe", "C:\\no\\space")))
    }
}
