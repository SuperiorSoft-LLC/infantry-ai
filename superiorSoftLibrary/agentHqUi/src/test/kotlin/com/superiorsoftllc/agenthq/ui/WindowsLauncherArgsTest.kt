package com.superiorsoftllc.agenthq.ui

import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BUG-CLI-2: the Windows launchers must hand java every argument whole. agenthq.cmd passes `%*`, and cmd.exe ends the
 * argument line at the first LF, so `--desc "a<LF>b" --port N` reached the CLI as `--desc a` and the port was lost (the
 * call went to the default daemon). PowerShell now runs agenthq.ps1 and Git Bash's shim runs java itself.
 *
 * Behavioural: each shipped launcher runs in a jpackage-shaped layout (`AgentHQ/app/resources/<launcher>`,
 * `AgentHQ/app/<jars>`) whose only jar is a fake `CliMainKt` that prints its arguments, one per line with LF shown as
 * `<LF>`. Windows only (the launchers are Windows ones); each test also needs its shell and is skipped without it.
 */
class WindowsLauncherArgsTest {
    private val scratch = Files.createTempDirectory("agenthq-launcher-args").toFile()
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val args = listOf("ticket", "create", "--title", "t", "--desc", "a\nb", "--port", "47123")
    private val expected = listOf("[ticket]", "[create]", "[--title]", "[t]", "[--desc]", "[a<LF>b]", "[--port]", "[47123]")

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    @Test
    fun powerShellRunsTheps1AndKeepsAMultiLineArgumentAndEveryArgumentAfterIt() {
        if (!windows) return
        val resources = installedLayout()
        val launcher = File(resources, InstalledCli.POWERSHELL_LAUNCHER)
        assertTrue(InstalledCli.syncPowerShellLauncher(resources, InstalledCli.scriptsCanRun(listOf("RemoteSigned"))))
        assertTrue(launcher.isFile, "agenthq.ps1 is put next to agenthq.cmd where scripts can run")
        for (shell in powerShells()) {
            // The bare name, from PATH, exactly as an agent types it: PowerShell must pick agenthq.ps1 over agenthq.cmd.
            val script = "\$env:PATH = '${resources.absolutePath}' + [IO.Path]::PathSeparator + \$env:PATH; " +
                "(Get-Command agenthq).Source; agenthq " + args.joinToString(" ") { psQuote(it) }
            val out = run(listOf(shell, "-NoProfile", "-ExecutionPolicy", "RemoteSigned", "-Command", script))
            assertEquals(launcher.absolutePath, out.first(), "$shell resolves `agenthq` to agenthq.ps1")
            assertEquals(expected, out.drop(1), "$shell -> agenthq.ps1 -> java")
        }
    }

    /**
     * BUG-CLI-3: under Restricted (the Windows client default) PowerShell refuses agenthq.ps1 yet still resolves a bare
     * `agenthq` to it, so shipping the script broke `agenthq` where agenthq.cmd used to work. The app ships it as a
     * template and installs it only where scripts run; here it was installed, then the policy turned out Restricted.
     */
    @Test
    fun powerShellUnderRestrictedPolicyStillRunsAgenthq() {
        if (!windows) return
        val resources = installedLayout()
        val cmd = File(resources, InstalledCli.WINDOWS_LAUNCHER)
        assertTrue(InstalledCli.syncPowerShellLauncher(resources, scriptsCanRun = true), "a permissive start installs it")
        assertFalse(InstalledCli.syncPowerShellLauncher(resources, InstalledCli.scriptsCanRun(listOf("Restricted"))))
        for (shell in powerShells()) {
            val script = "\$env:PATH = '${resources.absolutePath}' + [IO.Path]::PathSeparator + \$env:PATH; " +
                "(Get-Command agenthq).Source; agenthq --version"
            val out = run(listOf(shell, "-NoProfile", "-ExecutionPolicy", "Restricted", "-Command", script))
            assertEquals(cmd.absolutePath, out.first(), "$shell resolves `agenthq` to agenthq.cmd under Restricted")
            assertEquals(listOf("[--version]"), out.drop(1), "$shell -Restricted -> agenthq.cmd -> java")
        }
    }

    @Test
    fun gitBashShimRunsJavaDirectlyAndKeepsAMultiLineArgumentAndEveryArgumentAfterIt() {
        if (!windows) return
        val sh = gitBash() ?: return
        val resources = installedLayout()
        val shim = File(resources, InstalledCli.POSIX_LAUNCHER).absolutePath.replace('\\', '/')
        assertEquals(expected, run(listOf(sh, shim) + args), "Git Bash -> agenthq shim -> java")
    }

    @Test
    fun theShimNeverHandsOverToAgentHqCmd() {
        val shim = File("appResources/windows/${InstalledCli.POSIX_LAUNCHER}").readLines().filterNot { it.trimStart().startsWith("#") }
        assertFalse(shim.any { "agenthq.cmd" in it }, "the shim must not exec agenthq.cmd (cmd.exe cuts %* at LF)")
        assertTrue(shim.any { "exec" in it && InstalledCli.MAIN_CLASS in it }, "the shim execs java on ${InstalledCli.MAIN_CLASS}")
        val ps1 = File("appResources/windows/${InstalledCli.POWERSHELL_LAUNCHER_SOURCE}").readText()
        assertFalse(File("appResources/windows/${InstalledCli.POWERSHELL_LAUNCHER}").exists(), "agenthq.ps1 is never shipped as is (BUG-CLI-3)")
        assertTrue(InstalledCli.MAIN_CLASS in ps1 && "@args" in ps1, "agenthq.ps1 splats its args into java")
    }

    /** `<scratch>/AgentHQ/app/resources` holding every shipped Windows launcher, with the fake CLI jar in `app/`. */
    private fun installedLayout(): File {
        val app = File(scratch, "AgentHQ/app")
        val resources = File(app, "resources").apply { mkdirs() }
        File("appResources/windows").listFiles()!!.forEach { it.copyTo(File(resources, it.name)) }
        fakeCliJar(File(app, "fake-cli.jar"))
        return resources
    }

    /** A jar whose `com.superiorsoftllc.agenthq.cli.CliMainKt` prints each argument as `[arg]`, LF as `<LF>`. */
    private fun fakeCliJar(jar: File) {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("tests need a JDK (javax.tools) to build the fake CLI")
        val src = File(scratch, "src/com/superiorsoftllc/agenthq/cli/CliMainKt.java").apply { parentFile.mkdirs() }
        src.writeText(
            """
            package com.superiorsoftllc.agenthq.cli;
            public class CliMainKt {
                public static void main(String[] args) {
                    for (String a : args) System.out.println("[" + a.replace("\r", "<CR>").replace("\n", "<LF>") + "]");
                }
            }
            """.trimIndent(),
        )
        val classes = File(scratch, "classes").apply { mkdirs() }
        assertEquals(0, compiler.run(null, null, null, "-d", classes.path, src.path), "fake CliMainKt compiles")
        JarOutputStream(jar.outputStream()).use { out ->
            classes.walkTopDown().filter { it.isFile }.forEach { file ->
                out.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    /** Runs [command] with the test JVM's own java as AGENTHQ_JAVA and no inherited daemon address. */
    private fun run(command: List<String>): List<String> {
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment()["AGENTHQ_JAVA"] = File(System.getProperty("java.home"), "bin/java.exe").absolutePath
            environment().remove("AGENTHQ_URL")
        }.start()
        val out = process.inputStream.bufferedReader().readLines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        assertEquals(0, process.waitFor(), "exit code of $command; output: $out")
        return out
    }

    private fun psQuote(arg: String) = "'" + arg.replace("'", "''") + "'"

    private fun powerShells(): List<String> = listOf("pwsh", "powershell").filter(::starts)

    private fun gitBash(): String? = // Never a bare `bash`: on Windows that can be WSL's System32ash.exe, which is not the Git Bash agents run.
        listOf("C:/Program Files/Git/bin/bash.exe", "C:/Program Files/Git/usr/bin/bash.exe").firstOrNull { File(it).isFile }

    private fun starts(exe: String): Boolean = runCatching {
        val p = ProcessBuilder(exe, "-NoProfile", "-Command", "exit 0").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor() == 0
    }.getOrDefault(false)
}
