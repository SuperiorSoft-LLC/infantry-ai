// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.health

import com.superiorsoftllc.infantry.agents.composeFirstTurn
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DAEMON-HEALTH-2: the hook rows, the project fallback, the build label, the branch reader and the brief line. */
class HealthGapsTest {
    private fun service(rows: Map<String, String>, paths: (String?, String?) -> Triple<String?, String?, String?>): HealthService {
        val dir = java.nio.file.Files.createTempDirectory("health-gaps").toFile()
        val j = HealthJournal(dir, { 10 }, pidAlive = { false }).also { it.begin(1, 18901, "b") }
        return HealthService(j, McpBridgeTracker(j), HealthSources(dir, { 18901 }, "b", { emptyMap() }, { rows }, { null }, { emptyList() }, paths, 5))
    }

    @Test fun hookRowsFromAPluginBecomeTheHookCounts() {
        val r = service(mapOf("hooks" to "7", "hookFailures" to "2", "other" to "x")) { _, _ -> Triple(null, null, null) }.report()
        assertEquals(7, r.hooks); assertEquals(2, r.hookFailures)
        assertEquals(mapOf("other" to "x"), r.pluginRows)
    }

    @Test fun noScriptingPluginMeansNullHooks() {
        assertNull(service(emptyMap()) { _, _ -> Triple(null, null, null) }.report().hooks)
    }

    @Test fun projectOfTheCallFillsTheEnvironmentWithoutASession() {
        val r = service(emptyMap()) { sid, pid -> if (sid == null && pid == "p1") Triple("/repo", null, "main") else Triple(null, null, null) }.report(null, "p1")
        assertEquals("/repo", r.environment.projectPath); assertEquals("main", r.environment.gitBranch)
    }

    @Test fun branchIsReadFromHeadAndFromAWorktreeGitFile() {
        val repo = java.nio.file.Files.createTempDirectory("br").toFile()
        File(repo, ".git").mkdirs(); File(repo, ".git/HEAD").writeText("ref: refs/heads/feature/x\n")
        assertEquals("feature/x", EnvironmentProbe.currentBranch(repo))
        val wt = java.nio.file.Files.createTempDirectory("wt").toFile()
        val gd = File(repo, ".git/worktrees/w").apply { mkdirs() }; File(gd, "HEAD").writeText("ref: refs/heads/ticket/1\n")
        File(wt, ".git").writeText("gitdir: ${gd.path}\n")
        assertEquals("ticket/1", EnvironmentProbe.currentBranch(wt))
        File(repo, ".git/HEAD").writeText("0123456789012345678901234567890123456789\n")
        assertNull(EnvironmentProbe.currentBranch(repo))
    }

    @Test fun buildLabelKeepsAStampAndCompletesDev() {
        assertEquals("1.3.0+20261112.1000.abcdef01", EnvironmentProbe.buildLabel("1.3.0+20261112.1000.abcdef01"))
        assertEquals("dev (unstamped; version 1.3.0, commit 97d543b8)", EnvironmentProbe.buildLabel("dev", "1.3.0") { "97d543b8" })
        assertTrue(EnvironmentProbe.buildLabel("dev", null) { null }.contains("unknown"))
    }

    @Test fun firstTurnCarriesTheEnvironmentLineOnlyWhenGiven() {
        val line = EnvironmentProbe.briefLine(EnvironmentProbe.facts(File("."), "/p", null, null, emptyList()))
        assertTrue(composeFirstTurn(null, emptyList(), "r", "a", "TASK", environment = line).contains("Environment:"))
        assertTrue(!composeFirstTurn(null, emptyList(), "r", "a", "TASK").contains("Environment:"))
    }
}
