package com.superiorsoftllc.agenthq.cli

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MAIL-FOOTER-1: the "Start here" section of `docs/AGENT-QUICKREF.md` is what a cold agent copies, so every
 * command in it must still be a command. Each line is run through [Cli.run] against a dead port: a stale command
 * or flag is refused BEFORE any connection, while a valid one gets as far as connecting ("daemon not running")
 * or answers locally (exit 0). Also pins the grouped `--help` and the `mail` / `mailbox` alias.
 */
class AgentQuickRefCommandsTest {

    @Test
    fun everyStartHereCommandParses() {
        val commands = startHereCommands()
        assertTrue(commands.size >= 20, "the start-here section lost its commands: $commands")
        val files = sampleFiles()
        val deadPort = ServerSocket(0).use { it.localPort }
        // A spawned agent always has these; `memory`, `decision` and `issue` need the project to pass validation.
        val env = CliTestEnv.vars + mapOf("AGENTHQ_PROJECT_ID" to "project-x", "AGENTHQ_SESSION_ID" to "session-x")

        for (line in commands) {
            var args = shellWords(line.removePrefix("agenthq ")).map { word -> files[word] ?: placeholder(word) }
            // `bg run` really executes what follows `--`: keep its parse (the quickref's own words) but run a harmless command.
            val sep = args.indexOf("--")
            if (args.firstOrNull() == "bg" && sep >= 0) args = args.take(sep) + listOf("--", "java", "-version")
            val out = StringBuilder()
            val err = StringBuilder()
            val exit = Cli.run((if (args.firstOrNull() == "bg") listOf("bg", "--port", deadPort.toString()) + args.drop(1) else args + listOf("--port", deadPort.toString())).toTypedArray(), out, err, { env[it] })
            // Reaching the connect means parsing and validation passed; anything else (exit 2 with another
            // reason) is the stale command this test exists to catch.
            assertTrue(
                exit == 0 || err.contains("daemon not running"),
                "quickref command is not a valid command: `$line` -> exit $exit, err: $err",
            )
            assertFalse(err.contains("unknown"), "quickref command was not understood: `$line`, err: $err")
        }
    }

    @Test
    fun helpIsGroupedAndMailSitsTogether() {
        val out = StringBuilder()
        assertEquals(0, Cli.run(arrayOf("--help"), out, StringBuilder(), CliTestEnv.getenv))
        val help = out.toString().replace("\r\n", "\n")
        for (heading in listOf("Tickets:", "Agents and sessions:", "Mail:", "Memory and decisions:", "Daemon and pods:")) {
            assertTrue(help.lines().contains(heading), "help is missing the '$heading' group: $help")
        }
        val mail = help.substringAfter("\nMail:\n").substringBefore("\n\n")
        for (command in listOf("send ", "inbox", "read ", "threads", "thread ", "mail, mailbox")) {
            assertTrue(mail.lines().any { it.trimStart().startsWith(command) }, "'$command' is not in the Mail group: $mail")
        }
    }

    @Test
    fun mailAndMailboxPrintTheMailGroup() {
        for (args in listOf(arrayOf("mail"), arrayOf("mailbox"), arrayOf("mailbox", "--help"), arrayOf("mail", "-h"))) {
            val out = StringBuilder()
            val err = StringBuilder()
            assertEquals(0, Cli.run(args, out, err, CliTestEnv.getenv), "${args.toList()} failed: $err")
            val text = out.toString().replace("\r\n", "\n")
            assertTrue(text.startsWith("Mail:\n"), "${args.toList()} must print the Mail group: $text")
            assertTrue(text.contains("--reply-to"), text)
            assertFalse(text.contains("ticket create"), "${args.toList()} prints only the Mail group: $text")
        }
    }

    /** The `agenthq ...` lines of the fenced blocks in section 0. */
    private fun startHereCommands(): List<String> {
        val section = findQuickref().substringAfter("## 0. Start here").substringBefore("\n## 1.")
        return Regex("```\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).findAll(section)
            .flatMap { it.groupValues[1].lines() }
            .map { it.trim() }
            .filter { it.startsWith("agenthq ") }
            .toList()
    }

    /** The files the commands name, created for real: `--desc-file`, `--file` and `--image` read them before connecting. */
    private fun sampleFiles(): Map<String, String> {
        val dir = Files.createTempDirectory("quickref").toFile().apply { deleteOnExit() }
        fun text(name: String) = File(dir, name).apply { writeText("line one\nline two\n") }.absolutePath
        val png = File(dir, "shot.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16))
        }.absolutePath
        return mapOf("brief.md" to text("brief.md"), "note.md" to text("note.md"), "decision.md" to text("decision.md"), "shot.png" to png)
    }

    /** `<8-char-id>`, `<ticket-id>`, `<word>`...: a sample value of the right shape. */
    private fun placeholder(word: String): String = when {
        word == "<thread-id>" -> "pair:a|b"
        word.startsWith("<") && word.endsWith(">") -> "abcd1234"
        else -> word
    }

    /** Split like a shell: whitespace separates, "double quotes" group (and are removed). */
    private fun shellWords(line: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var any = false
        for (c in line) {
            when {
                // A shell comment: an unquoted `#` that starts a word ends the command, so the doc may annotate it.
                c == '#' && !quoted && !any -> break
                c == '"' -> { quoted = !quoted; any = true }
                c.isWhitespace() && !quoted -> { if (any) words += current.toString(); current.clear(); any = false }
                else -> { current.append(c); any = true }
            }
        }
        if (any) words += current.toString()
        return words
    }

    /** `docs/AGENT-QUICKREF.md`, found by walking up from the test's working directory, line endings normalized. */
    private fun findQuickref(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/AGENT-QUICKREF.md")
            if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            dir = dir.parentFile
        }
        error("docs/AGENT-QUICKREF.md not found above ${File("").absolutePath}")
    }
}
