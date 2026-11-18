// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.run.daemon

import com.superiorsoftllc.infantry.daemon.api.runs.RunHandle
import com.superiorsoftllc.infantry.daemon.api.runs.RunListener
import com.superiorsoftllc.infantry.daemon.api.runs.RunSpec
import com.superiorsoftllc.infantry.daemon.api.runs.Runs
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PREVIEW-BRANCH-1: the `{worktree}` / `{branch}` / `{root}` variables and the branch checkout, against a real git repository. */
class BranchRunTest {
    private val tmp: File = Files.createTempDirectory("infantry-branch-run").toFile().canonicalFile
    private val repo: File = File(tmp, "repo").also { it.mkdirs() }
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false") + args)
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, "git ${args.joinToString(" ")}: $out")
        return out
    }

    /** main (marker "main code"), feat/one ("one code"), feat/two ("two code"); the main checkout stays on main. */
    private fun repoWithBranches() {
        git(repo, "init", "-q", "-b", "main")
        File(repo, "marker.txt").writeText("main code")
        git(repo, "add", "."); git(repo, "commit", "-q", "-m", "main")
        for (b in listOf("one", "two")) {
            git(repo, "checkout", "-q", "-b", "feat/$b")
            File(repo, "marker.txt").writeText("$b code")
            git(repo, "commit", "-q", "-am", b)
            git(repo, "checkout", "-q", "main")
        }
    }

    /** Pipes only; a run's cwd is relative to [repo], as the host's `api.runs` resolves it. */
    private val runs = object : Runs {
        private val inner = TestRuns(PipedProcessRunner())
        override val workRoot: File get() = repo
        override val configDir: File get() = tmp
        override fun secretFields(name: String): Map<String, String>? = null
        override fun start(spec: RunSpec, listener: RunListener): RunHandle =
            inner.start(spec.copy(cwd = spec.cwd?.let { File(repo, it).path } ?: repo.path), listener)
    }
    private val worktrees = BranchWorktrees(RunsGit(runs))

    // ---- the variables ----

    @Test fun `a command naming worktree or branch is a branch command`() {
        assertTrue(BranchVars.isBranchCommand(listOf("x"), "{worktree}"))
        assertTrue(BranchVars.isBranchCommand(listOf("x", "-Pb={branch}"), null))
        assertFalse(BranchVars.isBranchCommand(listOf("x", "{root}/a"), "sub"))
        assertEquals("C:/w/x b C:/r", BranchVars.substitute("{worktree}/x {branch} {root}", "C:/w", "b", "C:/r"))
        assertEquals("{worktree}", BranchVars.substitute("{worktree}", null, "b", null))
    }

    @Test fun `a templated cwd must start with worktree and stay inside it`() {
        assertNull(BranchVars.cwdRefusal("{worktree}"))
        assertNull(BranchVars.cwdRefusal("{worktree}/sub/dir"))
        assertNull(BranchVars.cwdRefusal("{worktree}" + 92.toChar() + "sub"))
        for (bad in listOf("sub/{worktree}", "{worktree}x", "{worktree}/../out", "{worktree}/{branch}", "{root}", "{branch}")) {
            assertNotNull(BranchVars.cwdRefusal(bad), bad)
        }
    }

    @Test fun `branch names are checked and slugged`() {
        for (ok in listOf("main", "ticket/abc-1", "feat/x.y_z")) assertTrue(BranchWorktrees.isBranchName(ok), ok)
        for (bad in listOf("", "-x", "--upload-pack=evil", "a..b", "a//b", "a/", "a.", "a.lock", "a b", "a;b")) assertFalse(BranchWorktrees.isBranchName(bad), bad)
        assertEquals("ticket-abc-1", BranchWorktrees.slug("ticket/ABC-1"))
    }

    @Test fun `the registry keeps a worktree cwd as a template and marks the command a branch command`() {
        val reg = CommandRegistry.parse("""[{"id":"p","argv":["x","{branch}"],"cwd":"{worktree}/sub"},{"id":"plain","argv":["x"]}]""", repo)
        assertNull(reg.refusal)
        assertEquals("{worktree}/sub", reg.find("p")!!.cwd)
        assertTrue(reg.find("p")!!.branch)
        assertFalse(reg.find("plain")!!.branch)
        assertNotNull(CommandRegistry.parse("""[{"id":"p","argv":["x"],"cwd":"{worktree}/../up"}]""", repo).refusal)
    }

    // ---- the checkout ----

    @Test fun `branches lists local branches with a ticket worktree beside its branch`() {
        repoWithBranches()
        val ticket = File(tmp, "ticket-wt")
        git(repo, "worktree", "add", "-q", ticket.path, "feat/two")
        val rows = worktrees.branches(repo, null).getOrThrow()
        assertEquals(setOf("main", "feat/one", "feat/two"), rows.map { it.name }.toSet())
        assertNull(rows.first { it.name == "main" }.worktree, "the main checkout is never offered as a worktree")
        assertEquals(ticket.canonicalPath, rows.first { it.name == "feat/two" }.worktree)
    }

    @Test fun `prepare uses a ticket worktree as it is, else makes a preview worktree that remove deletes`() {
        repoWithBranches()
        val ticket = File(tmp, "ticket-wt")
        git(repo, "worktree", "add", "-q", ticket.path, "feat/two")
        val used = worktrees.prepare(repo, null, "feat/two").getOrThrow()
        assertFalse(used.created)
        assertEquals(ticket.canonicalPath, used.dir.path)

        val made = worktrees.prepare(repo, null, "feat/one").getOrThrow()
        assertTrue(made.created)
        assertEquals(File(repo, ".worktrees/preview-feat-one").canonicalPath, made.dir.path)
        assertEquals("one code", File(made.dir, "marker.txt").readText())
        assertEquals("main code", File(repo, "marker.txt").readText(), "the main checkout is untouched")
        assertEquals("main", git(repo, "branch", "--show-current").trim())

        assertFalse(worktrees.remove(repo, null, ticket), "a ticket's worktree is never removed")
        assertFalse(worktrees.remove(repo, null, repo), "the main checkout is never removed")
        assertTrue(ticket.isDirectory)
        assertTrue(worktrees.remove(repo, null, made.dir))
        assertFalse(made.dir.exists())
        assertFalse(git(repo, "worktree", "list").contains("preview-feat-one"))
    }

    @Test fun `prepare refuses a missing branch and an option look-alike`() {
        repoWithBranches()
        assertTrue(worktrees.prepare(repo, null, "nope").isFailure)
        assertTrue(worktrees.prepare(repo, null, "--orphan").isFailure)
        assertFalse(File(repo, ".worktrees").exists())
    }

    // ---- a run, end to end ----

    @Test fun `two branch runs one after the other each run their own code, and their preview worktrees are removed`() {
        repoWithBranches()
        val argv = if (windows) """["cmd.exe","/c","echo PREVIEW {branch}&& type marker.txt"]""" else """["sh","-c","echo PREVIEW {branch}; cat marker.txt"]"""
        val registry = CommandRegistry.parse("""[{"id":"preview","argv":$argv,"cwd":"{worktree}"},{"id":"plain","argv":["x"]}]""", repo)
        assertNull(registry.refusal)
        val frames = CopyOnWriteArrayList<Envelope>()
        val m = RunManager({ registry }, runs, { frames += it }, usePty = false, worktrees = worktrees)

        assertEquals(Envelope.ERROR, m.start("""{"commandId":"preview"}""").reply.type, "a branch command needs a branch")
        assertEquals(Envelope.ERROR, m.start("""{"commandId":"plain","branch":"feat/one"}""").reply.type, "a plain command takes none")

        for (b in listOf("one", "two")) {
            val before = frames.size
            val s = m.start("""{"commandId":"preview","branch":"feat/$b"}""")
            assertEquals(Envelope.RUN, s.reply.type, s.reply.data)
            assertTrue(s.reply.data.contains("feat/$b"), s.reply.data)
            s.launch()
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (frames.drop(before).none { it.type == Envelope.RUN_FINISHED } && System.nanoTime() < end) Thread.sleep(20)
            val mine = frames.drop(before)
            val lines = mine.filter { it.type == Envelope.RUN_OUTPUT }.map { Json.decodeFromString(RunOutput.serializer(), it.data).line.trim() }
            assertTrue("PREVIEW feat/$b" in lines, lines.toString())
            assertTrue("$b code" in lines, lines.toString())
            assertTrue(lines.any { it.contains("removed preview worktree") }, lines.toString())
            assertTrue(mine.first { it.type == Envelope.RUN_FINISHED }.data.contains("SUCCEEDED"), lines.toString())
            assertFalse(File(repo, ".worktrees/preview-feat-$b").exists())
        }
        assertEquals("main code", File(repo, "marker.txt").readText())
        assertEquals(1, git(repo, "worktree", "list").lines().count { it.isNotBlank() }, "only the main checkout is left")
    }
}
