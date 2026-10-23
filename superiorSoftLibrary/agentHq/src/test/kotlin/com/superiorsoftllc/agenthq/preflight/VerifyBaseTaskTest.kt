package com.superiorsoftllc.agenthq.preflight

import com.superiorsoftllc.agenthq.worktree.newGitRepo
import com.superiorsoftllc.agenthq.worktree.testGit
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUILD-VB-1: verifyBase must not break a fresh clone. It runs the REAL preflight script
 * (preflight/verify-base.gradle.kts, the file build.gradle.kts applies) in a throwaway Gradle build that
 * sits inside a throwaway git repository, so the branch, the declared bases and the history are all
 * controlled here, never read from the developer's checkout.
 *
 *  - undeclared branch (`main` on a fresh clone)  -> skip with one log line, build passes
 *  - detached HEAD (CI)                          -> skip with one log line, build passes
 *  - declared branch that contains its base      -> OK
 *  - declared branch that does NOT contain it    -> still fails, WRONG BASE (strict behaviour kept)
 */
class VerifyBaseTaskTest {

    private val git = testGit()
    private val made = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        made.forEach { runCatching { it.deleteRecursively() } }
    }

    private val script: File =
        File(requireNotNull(System.getProperty("agenthq.verifyBase.script")) {
            "agenthq.verifyBase.script is set by build.gradle.kts for every Test task"
        })

    private fun File.git(vararg args: String): String {
        val r = git.run(this, args.toList())
        assertTrue(r.ok, "git ${args.joinToString(" ")}: ${r.err()}")
        return r.out().trim()
    }

    /** A git repo on branch `main` holding a minimal Gradle build that applies the real preflight script. */
    private fun fixture(bases: String): File {
        val dir = newGitRepo("agenthq-vb1").also { made += it }
        dir.git("branch", "-M", "main")
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"vb1\"\n")
        File(dir, "build.gradle.kts").writeText("apply(from = \"${script.absolutePath.replace('\\', '/')}\")\n")
        File(dir, "preflight").mkdirs()
        File(dir, "preflight/expected-bases.properties").writeText(bases)
        dir.git("add", "-A")
        dir.git("commit", "-q", "-m", "fixture")
        return dir
    }

    private fun runner(dir: File) = GradleRunner.create()
        .withProjectDir(dir)
        .withArguments("verifyBase", "--stacktrace", "--no-configuration-cache")
        .forwardOutput()

    private fun pass(dir: File): BuildResult = runner(dir).build()
    private fun fail(dir: File): BuildResult = runner(dir).buildAndFail()

    @Test
    fun anUndeclaredBranchAndADetachedHeadSkipWithOneLogLine() {
        val dir = fixture(bases = "# no line for main\nsomething-else=\n")

        // 1. Fresh clone: branch main, no line for it. Skips, with one line saying why.
        val undeclared = pass(dir)
        assertEquals(TaskOutcome.SUCCESS, undeclared.task(":verifyBase")?.outcome)
        assertTrue(
            undeclared.output.contains("verifyBase: skipped, no expected base declared for branch 'main'"),
            "undeclared branch must skip with a one-line log",
        )

        // 2. Detached HEAD (CI checks out a commit). Skips too.
        dir.git("checkout", "-q", "--detach", "HEAD")
        val detached = pass(dir)
        assertEquals(TaskOutcome.SUCCESS, detached.task(":verifyBase")?.outcome)
        assertTrue(detached.output.contains("verifyBase: skipped, detached HEAD"), "detached HEAD must skip with a one-line log")
    }

    @Test
    fun aDeclaredBranchPassesOnItsBaseAndStillFailsOnAWrongBase() {
        val dir = fixture(bases = "")
        val goodBase = dir.git("rev-parse", "HEAD")

        // A commit that is NOT an ancestor of `feature`: made on a side branch.
        dir.git("checkout", "-q", "-b", "side")
        File(dir, "side.txt").writeText("side\n")
        dir.git("add", "-A")
        dir.git("commit", "-q", "-m", "side")
        val wrongBase = dir.git("rev-parse", "HEAD")
        dir.git("checkout", "-q", "main")
        dir.git("checkout", "-q", "-b", "feature")

        File(dir, "preflight/expected-bases.properties").writeText("feature=$goodBase\n")
        val ok = pass(dir)
        assertTrue(ok.output.contains("verifyBase: OK. 'feature'"), "declared branch containing its base passes")

        File(dir, "preflight/expected-bases.properties").writeText("feature=$wrongBase\n")
        val wrong = fail(dir)
        assertTrue(
            wrong.output.contains("WRONG BASE: 'feature'"),
            "a declared branch with a wrong base must still fail",
        )
    }
}
