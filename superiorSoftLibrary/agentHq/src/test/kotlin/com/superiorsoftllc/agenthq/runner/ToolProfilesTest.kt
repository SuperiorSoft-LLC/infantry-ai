package com.superiorsoftllc.agenthq.runner

import com.superiorsoftllc.agenthq.cli.BgCli
import com.superiorsoftllc.agenthq.session.BackgroundWorkScanner
import com.superiorsoftllc.agenthq.session.ProcInfo
import com.superiorsoftllc.agenthq.session.ProcessSource
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * STALL-3 (ticket d258b738) item 2: the build tools the watch knows are data. A profile entry for a made-up tool makes it
 * shimmed, rewritten, given its extra args and its daemons ignored, with no code naming it.
 */
class ToolProfilesTest {
    private val zork = ToolProfile(
        name = "zork",
        commands = listOf("zorkw", "zork"),
        shim = listOf("zork"),
        args = listOf("--plain-zork"),
        argsUnless = listOf("--plain"),
        daemons = listOf("ZorkDaemon"),
        notifyOn = listOf("ZORK DONE"),
    )

    @AfterTest
    fun reset() { ToolProfiles.active = emptyList() }

    private fun rw(cmd: String) = WatchRewrite.rewrite(WatchRewrite.Request(cmd, WatchRewrite.Shell.BASH, false, "claude", launcher = null, windows = false))

    @Test
    fun defaultsKeepTheStall2Behaviour() {
        assertEquals(listOf("gradle", "mvn", "npm", "yarn", "pnpm"), WatchShims.PROGRAMS)
        assertEquals(listOf("BUILD SUCCESSFUL", "BUILD FAILED", "^e: "), BgCli.DEFAULT_PATTERNS)
        assertEquals("gradlew", rw("./gradlew test")?.matched)
        assertEquals("java", rw("java -jar x.jar")?.matched)
        assertNull(rw("java -version"))
        assertTrue(BgCli.DETACHED_DAEMON.containsMatchIn("java -cp x org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.5"))
        assertTrue(BgCli.DETACHED_DAEMON.containsMatchIn("java org.jetbrains.kotlin.daemon.KotlinCompileDaemon"))
    }

    @Test
    fun aMadeUpToolIsShimmedRewrittenAndItsDaemonIgnoredWithoutCode() {
        assertNull(rw("./zorkw build"), "unknown before the entry exists")
        ToolProfiles.active = ToolProfiles.merge(ToolProfiles.DEFAULTS, listOf(zork))

        assertEquals("zorkw", rw("./zorkw build")?.matched)
        assertEquals("zork", rw("cd x && zork.cmd build | tee log")?.matched)
        assertTrue("zork" in WatchShims.PROGRAMS)
        assertEquals(listOf("zorkw", "--plain-zork", "build"), BgCli.withProfileArgs(listOf("zorkw", "build")))
        assertEquals(listOf("zork", "--plain=no"), BgCli.withProfileArgs(listOf("zork", "--plain=no")))
        assertTrue(BgCli.DETACHED_DAEMON.containsMatchIn("zork-runtime ZorkDaemon --port 1"))
        assertTrue("ZORK DONE" in BgCli.DEFAULT_PATTERNS)

        val dir = Files.createTempDirectory("zork-shims").toFile()
        try {
            WatchShims.install(dir, listOf("/bin/agenthq"), windows = false)
            val shim = File(dir, "zork")
            assertTrue(shim.isFile && shim.readText().contains("'--shim' 'zork' '--' 'zork'"), shim.readText())
            assertFalse(File(dir, "zorkw").exists(), "a path-named wrapper gets no PATH shim")
            // Dropping the entry drops its shim at the next install.
            ToolProfiles.active = ToolProfiles.merge(ToolProfiles.DEFAULTS, listOf(ToolProfile(name = "zork", disabled = true)))
            WatchShims.install(dir, listOf("/bin/agenthq"), windows = false)
            assertFalse(shim.exists())
            assertTrue(File(dir, "gradle").isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theProcessWatchIgnoresAProfileDaemon() {
        ToolProfiles.active = ToolProfiles.merge(ToolProfiles.DEFAULTS, listOf(zork))
        val source = object : ProcessSource {
            override fun descendants(rootPid: Long) = listOf(
                ProcInfo(pid = 10, parentPid = 1, command = "zork ZorkDaemon", startedAt = 0),
                ProcInfo(pid = 11, parentPid = 1, command = "zork build", startedAt = 0),
            )
        }
        assertEquals(listOf(11L), BackgroundWorkScanner.tops(source, 1, rootStartedAt = null).map { it.pid })
    }

    @Test
    fun overridesMergeByNameAndTheEnvCarriesTheList() {
        val merged = ToolProfiles.merge(ToolProfiles.DEFAULTS, listOf(ToolProfile(name = "npm", disabled = true), zork))
        assertNull(merged.firstOrNull { it.name == "npm" })
        assertEquals("zork", merged.last().name)
        val env = mapOf(ToolProfiles.ENV to ToolProfiles.encode(merged))
        assertEquals(merged, ToolProfiles.fromEnv { env[it] })
        assertNull(ToolProfiles.fromEnv { null })
        assertFailsWith<IllegalArgumentException> { ToolProfiles.merge(ToolProfiles.DEFAULTS, listOf(zork.copy(daemons = listOf("(")))) }
        assertFailsWith<IllegalArgumentException> { ToolProfiles.merge(ToolProfiles.DEFAULTS, listOf(zork.copy(commands = listOf("a b")))) }
        assertNotNull(WatchShims.sessionEnv(File("."), null, forced = true, profiles = merged)[ToolProfiles.ENV])
    }
}
