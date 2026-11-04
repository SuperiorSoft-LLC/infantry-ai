// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.session.PathEnv
import kotlin.test.Test
import kotlin.test.assertEquals

/** ROLE-NOTE-READ-1: the launcher directory stays first on a session's PATH when later env layers set their own PATH. */
class PathEnvTest {
    @Test
    fun laterPathLayerKeepsTheLauncherDirectory() {
        // childEnv (bin first) overlaid by a launch-env PATH that knows nothing of the bin dir: the old `+` dropped it.
        val env = mapOf("PATH" to "/bin:/sys", "INFANTRY_BIN" to "/bin") + mapOf("PATH" to "/shims:/sys")
        assertEquals("/bin:/shims:/sys", PathEnv.withBinOnPath(env, "ignored", ":")["PATH"])
    }

    @Test
    fun mixedCaseKeysCollapseToOneAndKeepTheirSpelling() {
        val out = PathEnv.withBinOnPath(mapOf("Path" to "/a", "PATH" to "/b", "INFANTRY_BIN" to "/bin"), null, ":")
        assertEquals(listOf("PATH"), out.keys.filter { it.equals("path", true) })
        assertEquals("/bin:/b", out["PATH"])
    }

    @Test
    fun missingPathStartsFromInheritedAndNoBinLeavesEnvAlone() {
        assertEquals("/x:/y", PathEnv.withBinOnPath(mapOf("INFANTRY_BIN" to "/x"), "/y", ":")["PATH"])
        val plain = mapOf("PATH" to "/y")
        assertEquals(plain, PathEnv.withBinOnPath(plain, "/z", ":"))
    }

    /** CLI-PTY-3: a real worker shell started with the session env resolves `infantry` from the launcher directory. */
    @Test
    fun aWorkerShellResolvesInfantryOnPath() {
        val bin = kotlin.io.path.createTempDirectory("infantry-bin").toFile()
        try {
            val win = System.getProperty("os.name").lowercase().contains("win")
            java.io.File(bin, "infantry.cmd").writeText("@echo infantry-ok\r\n")
            java.io.File(bin, "infantry").apply { writeText("#!/bin/sh\necho infantry-ok\n"); setExecutable(true) }
            val inherited = System.getenv("PATH") ?: System.getenv("Path")
            val env = PathEnv.withBinOnPath(mapOf(PathEnv.BIN_ENV to bin.absolutePath), inherited)
            val cmd = if (win) listOf("cmd.exe", "/c", "infantry") else listOf("sh", "-c", "infantry")
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment().keys.removeIf { it.equals("PATH", ignoreCase = true) }
            pb.environment().putAll(env)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            kotlin.test.assertTrue(p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, p.exitValue(), out)
            kotlin.test.assertTrue("infantry-ok" in out, out)
        } finally {
            bin.deleteRecursively()
        }
    }
}
