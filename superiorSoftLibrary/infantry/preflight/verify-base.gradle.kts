// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BUILD-VB-1: the base-commit preflight lives in its own script plugin so VerifyBaseTaskTest can apply
// this exact file to a throwaway Gradle build (TestKit) instead of testing a copy of it.
import java.util.Properties

// ---- Base-commit preflight ------------------------------------------------------------------
// A worktree cut on the wrong base (Phase 5c was cut on Phase 4.5 and its whole foundation was
// missing) must fail BEFORE any code is written or compiled. Mechanical, not a documented step:
// compileKotlin and compileTestKotlin depend on verifyBase, so every build, test, check, installDist
// and run goes through it. The expected base per branch is declared explicitly in
// preflight/expected-bases.properties. "Expected" is never inferred.
//
// BUILD-VB-1: the check is OPT-IN per branch. It is active only when expected-bases.properties has a
// line for the current branch; an undeclared branch (`main` on a fresh clone of the public repo) and a
// detached HEAD (CI) skip with one log line instead of failing the build. A declared branch keeps the
// strict behaviour: a missing or non-ancestor base still fails.
//
// Manual run:  ./gradlew :superiorSoftLibrary:infantry:verifyBase
tasks.register("verifyBase") {
    group = "verification"
    description = "Fails when a branch declared in expected-bases.properties does not contain its base; skips otherwise."
    val checkoutDir = projectDir
    val basesFile = file("preflight/expected-bases.properties")
    inputs.file(basesFile)
    doLast {
        fun git(vararg args: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf("git") + args.toList())
                .directory(checkoutDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            return process.waitFor() to output
        }

        val (_, branch) = git("rev-parse", "--abbrev-ref", "HEAD")
        if (branch.isEmpty() || branch == "HEAD") {
            println("verifyBase: skipped, detached HEAD in $checkoutDir (no branch to look up).")
            return@doLast
        }

        val expected = Properties().apply { basesFile.reader().use { load(it) } }
        if (!expected.containsKey(branch)) {
            println(
                "verifyBase: skipped, no expected base declared for branch '$branch' " +
                    "(add '$branch=<base commit>' to preflight/expected-bases.properties to enforce it)."
            )
            return@doLast
        }

        val base = expected.getProperty(branch).trim()
        val (headExit, headSha) = git("rev-parse", "--short", "HEAD")
        if (base.isEmpty()) {
            println("verifyBase: '$branch' is declared as a base branch (HEAD $headSha). Nothing to verify.")
            return@doLast
        }

        val (resolveExit, _) = git("rev-parse", "--verify", "--quiet", "$base^{commit}")
        if (resolveExit != 0) {
            throw GradleException("verifyBase: declared base '$base' for '$branch' does not exist in this repository.")
        }

        val (ancestorExit, _) = git("merge-base", "--is-ancestor", base, "HEAD")
        when (ancestorExit) {
            0 -> println("verifyBase: OK. '$branch' (HEAD $headSha) contains expected base $base.")
            1 -> throw GradleException(
                "WRONG BASE: '$branch' (HEAD ${if (headExit == 0) headSha else "?"}) does NOT contain expected base $base. " +
                    "Recut the worktree from $base, then rerun."
            )
            else -> throw GradleException("verifyBase: git merge-base failed (exit $ancestorExit) for '$branch'.")
        }
    }
}
