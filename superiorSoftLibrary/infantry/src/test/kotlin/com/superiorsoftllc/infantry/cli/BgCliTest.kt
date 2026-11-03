// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.session.BgJobRequest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BgCliTest {
    @Test fun `run takes a name and everything after -- verbatim`() {
        val p = BgCli.parse(listOf("run", "--name", "gate", "--", "gradlew", "test", "--name", "x"))
        assertEquals(BgCli.Parsed.Run("gate", listOf("gradlew", "test", "--name", "x")), p)
    }

    @Test fun `run --keep marks the job to survive its session closing`() {
        assertEquals(BgCli.Parsed.Run("dev", listOf("npm", "run", "dev"), keep = true), BgCli.parse(listOf("run", "--keep", "--name", "dev", "--", "npm", "run", "dev")))
        assertEquals(false, (BgCli.parse(listOf("run", "--", "x")) as BgCli.Parsed.Run).keep)
    }

    @Test fun `run without a command or with a stray option is refused`() {
        assertTrue(BgCli.parse(listOf("run", "--name", "gate")) is BgCli.Parsed.Bad)
        assertTrue(BgCli.parse(listOf("run", "--bogus", "--", "x")) is BgCli.Parsed.Bad)
        assertTrue(BgCli.parse(listOf("run", "--name")) is BgCli.Parsed.Bad)
    }

    @Test fun `list status logs stop`() {
        assertEquals(BgCli.Parsed.Show("list", null), BgCli.parse(listOf("list")))
        assertEquals(BgCli.Parsed.Show("stop", "ab12"), BgCli.parse(listOf("stop", "ab12")))
        assertEquals(BgCli.Parsed.Show("ack", "ab12"), BgCli.parse(listOf("ack", "ab12")))
        assertTrue(BgCli.parse(listOf("logs")) is BgCli.Parsed.Bad)
        assertTrue(BgCli.parse(listOf("nope")) is BgCli.Parsed.Bad)
        assertTrue(BgCli.parse(emptyList()) is BgCli.Parsed.Bad)
    }

    // ---- BG-RESOLVE (ticket bbb6120c): argv[0] resolution, and a command that cannot start never looks started ----

    private fun tmp(): File = Files.createTempDirectory("bgcli").toFile().apply { deleteOnExit() }
    private fun touch(f: File): File = f.apply { parentFile.mkdirs(); writeText("@echo off"); setExecutable(true) }

    /** Hermetic env (project note "cli test env hermetic"): only what the test names, never the operator's. */
    private fun env(vararg kv: Pair<String, String>): (String) -> String? = mapOf(*kv)::get

    @Test fun `windows - a bare bat in the cwd resolves to its absolute path`() {
        val cwd = tmp()
        val bat = touch(File(cwd, "gradlew.bat"))
        touch(File(cwd, "gradlew")) // the sh script next to it must not win
        val r = BgCli.resolveCommand(listOf("gradlew.bat", "tasks"), cwd, env("PATH" to ""), windows = true)
        assertEquals(listOf(bat.absolutePath, "tasks"), r)
        val bare = BgCli.resolveCommand(listOf("gradlew", "tasks"), cwd, env("PATH" to ""), windows = true)
        assertEquals(listOf(bat.absolutePath, "tasks"), bare)
    }

    @Test fun `windows - a cmd shim on PATH is found through PATHEXT`() {
        val bin = tmp()
        val shim = touch(File(bin, "infantry.cmd"))
        val r = BgCli.resolveCommand(
            listOf("infantry", "run", "--label", "x"), tmp(),
            env("PATH" to "${tmp().path}${File.pathSeparator}${bin.path}", "PATHEXT" to ".COM;.EXE;.BAT;.CMD"), windows = true,
        )
        assertEquals(listOf(shim.absolutePath, "run", "--label", "x"), r)
    }

    @Test fun `windows - a relative path with a separator resolves against the cwd`() {
        val cwd = tmp()
        val bat = touch(File(cwd, "sub/build.bat"))
        assertEquals(listOf(bat.absolutePath), BgCli.resolveCommand(listOf("./sub/build"), cwd, env(), windows = true))
    }

    @Test fun `a missing program resolves to null`() {
        assertNull(BgCli.resolveCommand(listOf("definitely-not-a-program"), tmp(), env("PATH" to tmp().path), windows = true))
        assertNull(BgCli.resolveCommand(listOf("definitely-not-a-program"), tmp(), env("PATH" to tmp().path), windows = false))
    }

    @Test fun `unix - a bare name is searched on PATH only`() {
        val bin = tmp()
        val tool = touch(File(bin, "tool"))
        val cwd = tmp().also { touch(File(it, "tool")) }
        if (File.separatorChar == '/') {
            assertEquals(listOf(tool.absolutePath, "a"), BgCli.resolveCommand(listOf("tool", "a"), cwd, env("PATH" to bin.path), windows = false))
        }
        assertNull(BgCli.resolveCommand(listOf("tool"), cwd, env("PATH" to ""), windows = false))
    }

    @Test fun `bg run --notify with a missing program exits 127 and says NOT started`() {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = BgCli.main(
            listOf("run", "--notify", "--", "definitely-not-a-program"), out, err,
            env("INFANTRY_SESSION_ID" to "s1", "PATH" to tmp().path),
        ) { error("must not connect") }
        assertEquals(127, code)
        assertTrue(err.contains("NOT started"), err.toString())
        assertFalse(out.contains("started detached"), out.toString())
    }

    // ---- BG-EXIT-1 (ticket 36f744fb): the done request and the .exit file carry the command's real exit code ----

    @Test fun `a failing command reports its real exit code in done and the exit file`() {
        val win = File.separatorChar == '\\'
        listOf(listOf("cmd", "/c", "exit 3"), listOf("sh", "-c", "exit 3")).filter { win == (it[0] == "cmd") }.forEach { cmd ->
            val sent = ArrayList<BgJobRequest>()
            val id = "x" + System.nanoTime().toString().takeLast(8)
            val code = BgCli.runJob(
                BgCli.Parsed.Run("job", cmd, notify = true, jobId = id), "s1",
                System::getenv, StringBuilder(), StringBuilder(), tmp(),
            ) { BgCli.Reporter({ sent += it }, { }) }
            assertEquals(3, code)
            assertEquals(3, sent.last { it.op == "done" }.exitCode)
            assertEquals("3", File(BgCli.logDir(), "$id.exit").readText())
        }
    }

    @Test fun `a wrapper whose command cannot start still registers and reports exit 127`() {
        val sent = ArrayList<BgJobRequest>()
        var closed = false
        val id = "t" + System.nanoTime().toString().takeLast(8)
        val err = StringBuilder()
        val bin = tmp()
        touch(File(bin, if (File.separatorChar == '\\') "prog.bat" else "prog"))
        val code = BgCli.runJob(
            BgCli.Parsed.Run("job", listOf("prog"), notify = true, jobId = id), "s1",
            env("PATH" to bin.path), StringBuilder(), err, tmp(),
            spawn = { throw java.io.IOException("CreateProcess error=2") },
        ) { BgCli.Reporter({ sent += it }, { closed = true }) }
        assertEquals(127, code)
        assertEquals(listOf("beat", "done"), sent.map { it.op })
        assertEquals(id, sent[0].job?.id)
        assertEquals(127, sent[1].exitCode)
        assertTrue(sent[1].tail!!.single().contains("cannot start"))
        assertTrue(closed)
        assertEquals("127", File(BgCli.logDir(), "$id.exit").readText())
        assertTrue(err.contains("NOT started"))
    }

    @Test fun `a wrapper whose program is not found reports exit 127 too`() {
        val sent = ArrayList<BgJobRequest>()
        val code = BgCli.runJob(
            BgCli.Parsed.Run("job", listOf("definitely-not-a-program"), notify = true, jobId = "n" + System.nanoTime().toString().takeLast(8)),
            "s1", env("PATH" to tmp().path), StringBuilder(), StringBuilder(), tmp(),
            spawn = { error("must not spawn") },
        ) { BgCli.Reporter({ sent += it }, {}) }
        assertEquals(127, code)
        assertEquals(127, sent.single { it.op == "done" }.exitCode)
    }
}
