// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** BUG-G2: where the daemon finds the `infantry` launchers, and the env it hands each session. */
class InfantryBinTest {
    private val root: File = Files.createTempDirectory("infantry-home").toFile()

    @AfterTest
    fun cleanup() { root.deleteRecursively() }

    private fun launcherDir(path: String, name: String = "infantry"): File =
        File(root, path).also { it.mkdirs(); File(it, name).writeText("") }

    @Test
    fun infantryBinWinsAndMayNameTheScript() {
        val bin = launcherDir("custom")
        assertEquals(bin.absoluteFile, InfantryBin.resolve(env = { if (it == "INFANTRY_BIN") bin.path else null }, codeSource = null))
        val script = File(bin, "infantry")
        assertEquals(bin.absoluteFile, InfantryBin.resolve(env = { if (it == "INFANTRY_BIN") script.path else null }, codeSource = null))
    }

    @Test
    fun anInstallDistJarFindsItsSiblingBin() {
        val bin = launcherDir("install/infantry/bin", "infantry.bat")
        val jar = File(root, "install/infantry/lib/infantry.jar").also { it.parentFile.mkdirs(); it.writeText("") }
        assertEquals(bin.absoluteFile, InfantryBin.resolve(env = { null }, codeSource = jar))
    }

    @Test
    fun aRunDaemonClassesDirFindsTheModuleInstall() {
        val bin = launcherDir("infantry/build/install/infantry/bin")
        val classes = File(root, "infantry/build/classes/kotlin/main").also { it.mkdirs() }
        assertEquals(bin.absoluteFile, InfantryBin.resolve(env = { null }, codeSource = classes))
    }

    @Test
    fun aDirWithoutALauncherIsNotUsed() {
        val classes = File(root, "infantry/build/classes/kotlin/main").also { it.mkdirs() }
        File(root, "infantry/build/install/infantry/bin").mkdirs()
        // BG-WAKE-1: never empty: falls back to the daemon's own launcher directory.
        val r = InfantryBin.resolve(env = { null }, codeSource = classes)
        assertEquals(InfantryBin.ownBinDir(), r)
        assertNotEquals(File(root, "infantry/build/install/infantry/bin").absoluteFile, r)
        val exe = File(root, "own/infantry.exe").also { it.parentFile.mkdirs(); it.writeText("x") }
        assertEquals(exe.parentFile.absoluteFile, InfantryBin.ownBinDir(exe.path))
    }

    @Test
    fun childEnvPrependsTheBinAndFillsJavaHomeOnlyWhenMissing() {
        val bin = File(root, "bin")
        val env = InfantryBin.childEnv(bin, env = { if (it == "PATH") "/usr/bin" else null }, javaHome = "/jdk", pathSeparator = ":")
        assertEquals(bin.absolutePath + ":/usr/bin", env["PATH"])
        assertEquals(bin.absolutePath, env["INFANTRY_BIN"])
        assertEquals("/jdk", env["JAVA_HOME"])
        val withJava = InfantryBin.childEnv(bin, env = { if (it == "JAVA_HOME") "/mine" else null }, javaHome = "/jdk")
        assertTrue("JAVA_HOME" !in withJava, "an operator's JAVA_HOME is never overridden")
        assertEquals(bin.absolutePath, withJava["PATH"])
        assertTrue(InfantryBin.childEnv(null).isEmpty(), "no bin dir = PATH untouched")
    }

    @Test
    fun urlEnvNamesTheDaemonsOwnPort() {
        assertEquals(mapOf("INFANTRY_URL" to "ws://127.0.0.1:8799/ws"), InfantryBin.urlEnv("127.0.0.1", 8799, env = { null }))
        assertEquals("ws://127.0.0.1:8799/ws", InfantryBin.urlEnv("0.0.0.0", 8799, env = { null })["INFANTRY_URL"], "a wildcard bind is reached on loopback")
        assertEquals("ws://127.0.0.1:8799/ws", InfantryBin.urlEnv("127.0.0.1", 8799, env = { if (it == "INFANTRY_URL") " " else null })["INFANTRY_URL"], "a blank inherited value is ignored")
    }

    @Test
    fun anInheritedInfantryUrlIsPreserved() {
        val mine = "ws://10.0.0.5:9000/ws"
        assertEquals(mine, InfantryBin.urlEnv("127.0.0.1", 8799, env = { if (it == "INFANTRY_URL") mine else null })["INFANTRY_URL"])
    }
}
