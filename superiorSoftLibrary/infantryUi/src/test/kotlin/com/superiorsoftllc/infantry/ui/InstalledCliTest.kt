package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.daemon.InfantryBin
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CLIPATH-2: the installed app's `infantry` CLI. The installed profile pins the app's launcher dir as INFANTRY_BIN, so
 * every spawned session gets it first on PATH; dev keeps exactly its installDist behaviour; and the shipped launchers
 * run the real CLI entry point.
 */
class InstalledCliTest {
    private val scratch = Files.createTempDirectory("infantry-installed-cli").toFile()
    private val home = File("C:/Users/someone").path
    private val env: (String) -> String? = mapOf("LOCALAPPDATA" to File(home, "AppData/Local").path)::get

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    /** `<install>/app/resources` with the Windows launcher in it, as jpackage lays it out. */
    private fun installedResources(): File =
        File(scratch, "Infantry/app/resources").apply { mkdirs(); File(this, InstalledCli.WINDOWS_LAUNCHER).writeText("@echo off\r\n") }

    @Test
    fun installedProfilePutsTheLauncherDirFirstOnTheChildPathAndSetsInfantryBin() {
        val resources = installedResources()
        val binDir = InstalledCli.binDir(mapOf(InstalledCli.RESOURCES_DIR_PROPERTY to resources.path)::get)
        assertEquals(resources.absoluteFile, binDir)

        val pinned = InstallProfile.properties(env, home, cliBin = binDir)
        assertEquals(resources.absolutePath, pinned[InfantryBin.BIN_ENV], "the installed profile pins INFANTRY_BIN")

        // The daemon reads its settings property-first (infantrySetting): what InstallProfile.apply pinned decides.
        val resolved = InfantryBin.resolve(env = pinned::get, codeSource = null)
        assertEquals(resources.absoluteFile, resolved)

        val child = InfantryBin.childEnv(resolved, env = mapOf("PATH" to "C:\\Windows").let { it::get }, javaHome = null, pathSeparator = ";")
        assertEquals(resources.absolutePath + ";C:\\Windows", child["PATH"], "the launcher dir is FIRST on the child's PATH")
        assertEquals(resources.absolutePath, child[InfantryBin.BIN_ENV])
    }

    @Test
    fun devLeavesTheSpawnEnvironmentAsItWas() {
        // Dev never calls InstallProfile; Gradle `run` may still set the Compose resources property, and that alone
        // must not reach the daemon: InfantryBin never reads it.
        assertFalse(LaunchProfile.isInstalled(mapOf<String, String>()::get))
        val runProperties = mapOf(InstalledCli.RESOURCES_DIR_PROPERTY to installedResources().path)
        // BG-WAKE-1: with no launcher found the daemon falls back to its own executable's directory (never the Compose resources dir).
        assertNotEquals(File(installedResources().path).absoluteFile, InfantryBin.resolve(env = runProperties::get, codeSource = null), "the Compose resources property alone must not reach the daemon")
        assertTrue(InfantryBin.childEnv(null, env = mapOf("PATH" to "/usr/bin").let { it::get }).isEmpty(), "the child inherits PATH untouched")

        // And the dev installDist lookup is unchanged: classes under build/classes give build/install/infantry/bin.
        val module = File(scratch, "infantry")
        val installBin = File(module, "build/install/infantry/bin").apply { mkdirs(); File(this, "infantry").writeText("#!/bin/sh\n") }
        val classes = File(module, "build/classes/kotlin/main").apply { mkdirs() }
        assertEquals(installBin.absoluteFile, InfantryBin.resolve(env = { null }, codeSource = classes))
    }

    @Test
    fun noLauncherMeansNoPinNeverAGuess() {
        assertNull(InstalledCli.binDir(mapOf<String, String>()::get), "no resources property")
        val empty = File(scratch, "empty").apply { mkdirs() }
        assertNull(InstalledCli.binDir(mapOf(InstalledCli.RESOURCES_DIR_PROPERTY to empty.path)::get), "a dir with no launcher")
        assertFalse(InfantryBin.BIN_ENV in InstallProfile.properties(env, home, cliBin = null))
    }

    @Test
    fun theShippedLaunchersRunCliMainKt() {
        // Gradle runs tests from the module dir: these are the files packaging ships (appResourcesRootDir).
        val root = File("appResources")
        val cmd = File(root, "windows/${InstalledCli.WINDOWS_LAUNCHER}")
        val posix = listOf(File(root, "macos/${InstalledCli.POSIX_LAUNCHER}"), File(root, "linux/${InstalledCli.POSIX_LAUNCHER}"))
        (posix + cmd).forEach { assertTrue(it.isFile, "missing launcher ${it.absolutePath}") }

        val cmdText = cmd.readText()
        assertTrue(InstalledCli.MAIN_CLASS in cmdText, "infantry.cmd must name ${InstalledCli.MAIN_CLASS}")
        assertTrue("-cp \"%INFANTRY_APP_DIR%\\*\"" in cmdText, "infantry.cmd runs on the app's own classpath")
        assertTrue("runtime\\bin\\java.exe" in cmdText, "infantry.cmd prefers the bundled runtime")
        posix.forEach { script ->
            val text = script.readText()
            assertTrue(text.startsWith("#!/bin/sh"), "${script.path} is an sh script")
            assertTrue(InstalledCli.MAIN_CLASS in text, "${script.path} must name ${InstalledCli.MAIN_CLASS}")
            assertFalse('\r' in text, "${script.path} must keep LF line endings")
        }
        // Git Bash does not resolve `infantry` to infantry.cmd: the Windows dir also ships an sh shim, which runs java
        // itself (BUG-CLI-2, see WindowsLauncherArgsTest), and PowerShell gets infantry.ps1 (shipped as a template, BUG-CLI-3).
        val shim = File(root, "windows/${InstalledCli.POSIX_LAUNCHER}").readText()
        assertTrue(InstalledCli.MAIN_CLASS in shim && !shim.contains('\r'), shim)
        assertTrue(InstalledCli.MAIN_CLASS in File(root, "windows/${InstalledCli.POWERSHELL_LAUNCHER_SOURCE}").readText())

        // The class the launchers name is the real CLI entry point on the app's classpath.
        Class.forName(InstalledCli.MAIN_CLASS)
        assertTrue(InfantryBin.LAUNCHERS.containsAll(listOf(InstalledCli.WINDOWS_LAUNCHER, InstalledCli.POSIX_LAUNCHER)))
    }

    @Test
    fun infantryCmdPrefersTheBundledJavaOverJavaHomeAndPath() {
        // PKG-4: packaging now keeps runtime\bin\java.exe, so the bundled branch must be checked before JAVA_HOME and
        // PATH (only an explicit INFANTRY_JAVA beats it). Each `set` is guarded by `if not defined`, so the first
        // matching line wins: the order of the lines IS the preference order.
        val lines = File("appResources/windows/${InstalledCli.WINDOWS_LAUNCHER}").readLines()
        fun lineOf(marker: String): Int = lines.indexOfFirst { "INFANTRY_CLI_JAVA=" in it && marker in it }
            .also { assertTrue(it >= 0, "infantry.cmd has no INFANTRY_CLI_JAVA line for $marker") }
        val explicit = lineOf("%INFANTRY_JAVA%")
        val bundled = lineOf("runtime\\bin\\java.exe")
        val javaHome = lineOf("%JAVA_HOME%")
        val path = lines.indexOfFirst { "set \"INFANTRY_CLI_JAVA=java\"" in it }
        assertTrue(explicit < bundled && bundled < javaHome && javaHome < path, "order must be INFANTRY_JAVA, bundled, JAVA_HOME, PATH")
        assertTrue("if not defined INFANTRY_CLI_JAVA if exist \"%~dp0..\\..\\runtime\\bin\\java.exe\"" in lines[bundled], lines[bundled])
    }

    @Test
    fun posixLauncherRunsTheBundledJavaWhenPresent() {
        // PKG-4, behavioural: run the shipped sh launcher in a jpackage-shaped layout with fake `java`s that report
        // which one ran. Needs an `sh` (Git Bash on Windows); without one the cmd-order test above still covers it.
        val sh = shell() ?: return
        val install = File(scratch, "Infantry")
        val resources = File(install, "app/resources").apply { mkdirs() }
        val launcher = File(resources, InstalledCli.POSIX_LAUNCHER)
        File("appResources/linux/${InstalledCli.POSIX_LAUNCHER}").copyTo(launcher)
        fun fakeJava(file: File, says: String) = file.apply {
            parentFile.mkdirs(); writeText("#!/bin/sh\necho $says\n"); setExecutable(true, false)
        }
        val jdk = File(scratch, "jdk")
        fakeJava(File(jdk, "bin/java"), "javahome")
        val bundled = fakeJava(File(install, "runtime/bin/java"), "bundled")

        fun run(): String {
            val process = ProcessBuilder(sh, launcher.absolutePath.replace('\\', '/'), "--help").redirectErrorStream(true).apply {
                environment().remove("INFANTRY_JAVA")
                environment()["JAVA_HOME"] = jdk.absolutePath.replace('\\', '/')
            }.start()
            val out = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            return out
        }
        assertEquals("bundled", run(), "with runtime/bin/java present the launcher must not fall back to JAVA_HOME")
        bundled.delete()
        assertEquals("javahome", run(), "without a bundled java it falls back to JAVA_HOME")
    }

    private fun shell(): String? {
        val candidates = listOf("sh", "C:/Program Files/Git/bin/sh.exe", "C:/Program Files/Git/usr/bin/sh.exe")
        return candidates.firstOrNull { candidate ->
            runCatching {
                ProcessBuilder(candidate, "-c", "exit 0").redirectErrorStream(true).start().waitFor() == 0
            }.getOrDefault(false)
        }
    }

    /** BUG-CLI-3: infantry.ps1 only when every PowerShell may run an unsigned local script. */
    @Test
    fun scriptsCanRunOnlyUnderAPermissivePolicyInEveryPowerShell() {
        assertEquals(true, InstalledCli.scriptsCanRun(listOf("RemoteSigned")))
        assertEquals(true, InstalledCli.scriptsCanRun(listOf("Bypass", "Unrestricted\r\n")))
        assertEquals(false, InstalledCli.scriptsCanRun(listOf("Restricted")), "the Windows client default")
        assertEquals(false, InstalledCli.scriptsCanRun(listOf("AllSigned")), "the script is unsigned")
        assertEquals(false, InstalledCli.scriptsCanRun(listOf("RemoteSigned", "Restricted")), "one shell refusing it is enough")
        assertNull(InstalledCli.scriptsCanRun(listOf("")), "a probe that said nothing is unknown, not a refusal")
        assertEquals(true, InstalledCli.scriptsCanRun(listOf("remotesigned")), "PowerShell names are case-insensitive")
        assertNull(InstalledCli.scriptsCanRun(listOf("RemoteSigned", "Get-ExecutionPolicy : error")), "one unknown answer")
        assertEquals(false, InstalledCli.scriptsCanRun(listOf("Restricted", "Get-ExecutionPolicy : error")), "a known refusal still wins")
        assertEquals(false, InstalledCli.scriptsCanRun(emptyList()), "no PowerShell at all")
        val asked = mutableListOf<List<String>>()
        val policies = InstalledCli.executionPolicies { asked += it; if (it.first() == "pwsh") null else "Restricted" }
        assertEquals(listOf("Restricted"), policies, "a shell that cannot start is left out")
        assertEquals(listOf("powershell", "pwsh"), asked.map { it.first() })
        assertTrue(asked.all { it.last() == "Get-ExecutionPolicy" })
    }

    /**
     * BUG-INSTALL-PS1-1: Infantry.exe started from a pwsh 7 console hands Windows PowerShell pwsh's PSModulePath, and
     * `powershell -Command Get-ExecutionPolicy` then prints an error instead of a policy. That text is not a policy:
     * it must not count as "scripts cannot run", and the existing infantry.ps1 must stay.
     */
    @Test
    fun aProbeThatPrintsAnErrorLeavesTheExistingScriptInPlace() {
        val bin = Files.createTempDirectory("infantry-ps1-probe").toFile()
        try {
            val ps1 = File(bin, InstalledCli.POWERSHELL_LAUNCHER)
            File(bin, InstalledCli.POWERSHELL_LAUNCHER_SOURCE).writeText("v1")
            assertTrue(InstalledCli.syncPowerShellLauncher(bin, scriptsCanRun = true))
            val error = "Get-ExecutionPolicy : The 'Get-ExecutionPolicy' command was found in the module " +
                "'Microsoft.PowerShell.Security', but the module could not be loaded. For more information, run " +
                "'Import-Module Microsoft.PowerShell.Security'."
            val policies = InstalledCli.executionPolicies { if (it.first() == "powershell") error else "RemoteSigned" }
            InstalledCli.syncPowerShellLauncher(bin, InstalledCli.scriptsCanRun(policies))
            assertTrue(ps1.isFile, "an unrecognised probe answer must not delete infantry.ps1")
            assertEquals("v1", ps1.readText())
        } finally {
            bin.deleteRecursively()
        }
    }

    @Test
    fun syncPowerShellLauncherInstallsTheTemplateOrRemovesTheScript() {
        val bin = Files.createTempDirectory("infantry-ps1-sync").toFile()
        try {
            val ps1 = File(bin, InstalledCli.POWERSHELL_LAUNCHER)
            val template = File(bin, InstalledCli.POWERSHELL_LAUNCHER_SOURCE).apply { writeText("v1") }
            assertFalse(InstalledCli.syncPowerShellLauncher(bin, scriptsCanRun = false))
            assertTrue(InstalledCli.syncPowerShellLauncher(bin, scriptsCanRun = true))
            assertEquals("v1", ps1.readText())
            template.writeText("v2")
            assertTrue(InstalledCli.syncPowerShellLauncher(bin, scriptsCanRun = true))
            assertEquals("v2", ps1.readText(), "an upgraded template replaces the old script")
            assertFalse(InstalledCli.syncPowerShellLauncher(bin, scriptsCanRun = false))
            assertFalse(ps1.exists(), "a policy tightened later takes the script away")
        } finally {
            bin.deleteRecursively()
        }
    }
}
