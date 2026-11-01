package com.superiorsoftllc.agenthq.daemon

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** BUG-G2: where the daemon finds the `agenthq` launchers, and the env it hands each session. */
class AgentHqBinTest {
    private val root: File = Files.createTempDirectory("agenthq-home").toFile()

    @AfterTest
    fun cleanup() { root.deleteRecursively() }

    private fun launcherDir(path: String, name: String = "agenthq"): File =
        File(root, path).also { it.mkdirs(); File(it, name).writeText("") }

    @Test
    fun agentHqBinWinsAndMayNameTheScript() {
        val bin = launcherDir("custom")
        assertEquals(bin.absoluteFile, AgentHqBin.resolve(env = { if (it == "AGENTHQ_BIN") bin.path else null }, codeSource = null))
        val script = File(bin, "agenthq")
        assertEquals(bin.absoluteFile, AgentHqBin.resolve(env = { if (it == "AGENTHQ_BIN") script.path else null }, codeSource = null))
    }

    @Test
    fun anInstallDistJarFindsItsSiblingBin() {
        val bin = launcherDir("install/agenthq/bin", "agenthq.bat")
        val jar = File(root, "install/agenthq/lib/agentHq.jar").also { it.parentFile.mkdirs(); it.writeText("") }
        assertEquals(bin.absoluteFile, AgentHqBin.resolve(env = { null }, codeSource = jar))
    }

    @Test
    fun aRunDaemonClassesDirFindsTheModuleInstall() {
        val bin = launcherDir("agentHq/build/install/agenthq/bin")
        val classes = File(root, "agentHq/build/classes/kotlin/main").also { it.mkdirs() }
        assertEquals(bin.absoluteFile, AgentHqBin.resolve(env = { null }, codeSource = classes))
    }

    @Test
    fun aDirWithoutALauncherIsNotUsed() {
        val classes = File(root, "agentHq/build/classes/kotlin/main").also { it.mkdirs() }
        File(root, "agentHq/build/install/agenthq/bin").mkdirs()
        // BG-WAKE-1: never empty: falls back to the daemon's own launcher directory.
        val r = AgentHqBin.resolve(env = { null }, codeSource = classes)
        assertEquals(AgentHqBin.ownBinDir(), r)
        assertNotEquals(File(root, "agentHq/build/install/agenthq/bin").absoluteFile, r)
        val exe = File(root, "own/agenthq.exe").also { it.parentFile.mkdirs(); it.writeText("x") }
        assertEquals(exe.parentFile.absoluteFile, AgentHqBin.ownBinDir(exe.path))
    }

    @Test
    fun childEnvPrependsTheBinAndFillsJavaHomeOnlyWhenMissing() {
        val bin = File(root, "bin")
        val env = AgentHqBin.childEnv(bin, env = { if (it == "PATH") "/usr/bin" else null }, javaHome = "/jdk", pathSeparator = ":")
        assertEquals(bin.absolutePath + ":/usr/bin", env["PATH"])
        assertEquals(bin.absolutePath, env["AGENTHQ_BIN"])
        assertEquals("/jdk", env["JAVA_HOME"])
        val withJava = AgentHqBin.childEnv(bin, env = { if (it == "JAVA_HOME") "/mine" else null }, javaHome = "/jdk")
        assertTrue("JAVA_HOME" !in withJava, "an operator's JAVA_HOME is never overridden")
        assertEquals(bin.absolutePath, withJava["PATH"])
        assertTrue(AgentHqBin.childEnv(null).isEmpty(), "no bin dir = PATH untouched")
    }

    @Test
    fun urlEnvNamesTheDaemonsOwnPort() {
        assertEquals(mapOf("AGENTHQ_URL" to "ws://127.0.0.1:8799/ws"), AgentHqBin.urlEnv("127.0.0.1", 8799, env = { null }))
        assertEquals("ws://127.0.0.1:8799/ws", AgentHqBin.urlEnv("0.0.0.0", 8799, env = { null })["AGENTHQ_URL"], "a wildcard bind is reached on loopback")
        assertEquals("ws://127.0.0.1:8799/ws", AgentHqBin.urlEnv("127.0.0.1", 8799, env = { if (it == "AGENTHQ_URL") " " else null })["AGENTHQ_URL"], "a blank inherited value is ignored")
    }

    @Test
    fun anInheritedAgentHqUrlIsPreserved() {
        val mine = "ws://10.0.0.5:9000/ws"
        assertEquals(mine, AgentHqBin.urlEnv("127.0.0.1", 8799, env = { if (it == "AGENTHQ_URL") mine else null })["AGENTHQ_URL"])
    }
}
