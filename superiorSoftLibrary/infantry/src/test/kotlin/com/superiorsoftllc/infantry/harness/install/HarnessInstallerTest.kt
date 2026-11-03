package com.superiorsoftllc.infantry.harness.install

import com.superiorsoftllc.infantry.executables.ExecutableEnvironment
import com.superiorsoftllc.infantry.executables.ExecutableResolver
import com.superiorsoftllc.infantry.executables.HostOs
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HARNESS-INSTALL-1: the installer against a FAKE npm and a fake machine. Nothing here starts a real process or reaches the
 * network: the "npm install" is a lambda that drops a fake CLI into the (real, temporary) prefix dir's fake file table.
 */
class HarnessInstallerTest {
    private val prefix: File = Files.createTempDirectory("harness-tools").toFile().resolve("harness-tools")

    private class FakeEnv(
        private val vars: Map<String, String>,
        val files: MutableSet<String>,
    ) : ExecutableEnvironment {
        override val os = HostOs.LINUX
        override fun env(name: String) = vars[name]
        override fun home() = "/home/u"
        override fun exists(path: String) = path in files
        override fun isDirectory(path: String) = false
        override fun isExecutable(path: String) = path in files
        override fun isAbsolute(path: String) = true
    }

    /** A fake npm: records every argv; `install` creates the CLI; `view` answers [latest]; `--version` answers [versions]. */
    private inner class FakeNpm(private val env: FakeEnv, var exit: Int = 0, var latest: String = "9.9.9") : HarnessProcessRunner {
        val calls = ArrayList<List<String>>()
        val versions = HashMap<String, String>()
        override fun run(argv: List<String>, env: Map<String, String>, timeoutMillis: Long, onLine: (String) -> Unit): ProcessResult {
            calls += argv
            return when {
                argv.last() == "--version" -> versions[argv.first()]?.let { ProcessResult(0, it) } ?: ProcessResult(1, "boom")
                argv[1] == "view" -> ProcessResult(0, latest)
                argv[1] == "install" -> {
                    onLine("added 1 package")
                    if (exit != 0) return ProcessResult(exit, "npm ERR! network\nnpm ERR! code ETIMEDOUT")
                    val spec = argv.last()
                    val version = spec.substringAfterLast('@').takeIf { it != "latest" } ?: latest
                    val id = when {
                        spec.startsWith("@anthropic-ai/claude-code") -> "claude"
                        spec.startsWith("opencode-ai") -> "opencode"
                        else -> "pi"
                    }
                    val path = this.env.join(prefix.path, "bin", id)
                    this.env.files += path
                    versions[path] = "$version ($id)"
                    ProcessResult(0, "")
                }
                else -> ProcessResult(1, "unexpected $argv")
            }
        }
    }

    private val overrides = com.superiorsoftllc.infantry.executables.ExecutableOverrides.InMemory()

    private fun fixture(withNpm: Boolean = true, vars: Map<String, String> = emptyMap()): Triple<HarnessInstaller, FakeNpm, FakeEnv> {
        val files = mutableSetOf<String>()
        if (withNpm) files += setOf("/usr/bin/npm", "/usr/bin/node")
        val env = FakeEnv(vars + ("PATH" to "/usr/bin"), files)
        val npm = FakeNpm(env)
        return Triple(HarnessInstaller(prefix, ExecutableResolver(env, overrides, prefix.path), env, overrides, npm), npm, env)
    }

    @AfterTest
    fun reset() {
        prefix.parentFile.deleteRecursively()
    }

    @Test
    fun everyHarnessIsMissingOnAFreshMachineAndNpmIsReported() {
        val (installer, _, _) = fixture()
        val report = installer.status()
        assertEquals(listOf("claude", "opencode", "pi"), report.harnesses.map { it.id })
        assertTrue(report.harnesses.all { it.state == "missing" && it.path == null })
        assertEquals("/usr/bin/npm", report.npmPath)
        assertNull(report.guidance)
    }

    @Test
    fun installPutsTheCliInTheAppPrefixAndASecondRunIsANoOp() {
        val (installer, npm, _) = fixture()
        val first = installer.install(listOf("claude"), update = false)
        assertEquals(listOf("installed"), first.outcomes.map { it.result })
        assertEquals("9.9.9", first.outcomes.single().version)
        val installCall = npm.calls.single { it.getOrNull(1) == "install" }
        assertEquals("--prefix", installCall[installCall.indexOf("--prefix")])
        assertEquals(prefix.path, installCall[installCall.indexOf("--prefix") + 1], "installs only into the app prefix")
        assertEquals("@anthropic-ai/claude-code@latest", installCall.last())
        val claude = first.report.harnesses.single { it.id == "claude" }
        assertEquals("installed", claude.state)
        assertTrue(claude.managed, "the resolver finds it in the app prefix and says so")
        assertTrue(first.log.any { it.contains("added 1 package") })

        val installsBefore = npm.calls.count { it.getOrNull(1) == "install" }
        val second = installer.install(listOf("claude"), update = false)
        assertEquals(listOf("skipped"), second.outcomes.map { it.result })
        assertEquals(installsBefore, npm.calls.count { it.getOrNull(1) == "install" }, "idempotent: npm is not run again")
    }

    @Test
    fun noIdsInstallsExactlyTheMissingOnes() {
        val (installer, npm, _) = fixture()
        installer.install(listOf("pi"), update = false)
        val result = installer.install(emptyList(), update = false)
        assertEquals(listOf("claude", "opencode"), result.outcomes.map { it.id })
        assertTrue(result.outcomes.all { it.result == "installed" })
        assertEquals(3, npm.calls.count { it.getOrNull(1) == "install" })
    }

    @Test
    fun missingNpmIsAClearFailureWithGuidanceAndNothingRuns() {
        val (installer, npm, _) = fixture(withNpm = false)
        val status = installer.status()
        assertNull(status.npmPath)
        assertNotNull(status.guidance)
        assertTrue(status.guidance!!.contains("Node.js"))

        val result = installer.install(emptyList(), update = false)
        assertEquals(3, result.outcomes.size)
        assertTrue(result.outcomes.all { it.result == "failed" && it.message!!.contains("Node.js / npm was not found") })
        assertTrue(npm.calls.isEmpty(), "no process started without npm")
    }

    @Test
    fun anNpmFailureIsReportedPerHarnessNotThrown() {
        val (installer, npm, _) = fixture()
        npm.exit = 1
        val result = installer.install(listOf("opencode"), update = false)
        val outcome = result.outcomes.single()
        assertEquals("failed", outcome.result)
        assertTrue(outcome.message!!.contains("npm exited 1") && outcome.message!!.contains("ETIMEDOUT"), outcome.message)
        assertEquals("missing", result.report.harnesses.single { it.id == "opencode" }.state)
    }

    @Test
    fun aPinMakesADifferentVersionOutdatedAndUpdateInstallsExactlyThePin() {
        val (installer, npm, env) = fixture(vars = mapOf("INFANTRY_CLAUDE_VERSION" to "2.0.0"))
        val path = env.join(prefix.path, "bin", "claude")
        env.files += path
        npm.versions[path] = "1.5.0 (Claude Code)"

        assertEquals("outdated", installer.status().harnesses.single { it.id == "claude" }.state)
        val plain = installer.install(emptyList(), update = false)
        assertTrue(plain.outcomes.none { it.id == "claude" }, "outdated is left alone unless update is asked for")

        val updated = installer.install(emptyList(), update = true)
        assertEquals("updated", updated.outcomes.single { it.id == "claude" }.result)
        assertEquals("@anthropic-ai/claude-code@2.0.0", npm.calls.last { it.getOrNull(1) == "install" }.last())
        assertEquals("installed", updated.report.harnesses.single { it.id == "claude" }.state)
    }

    @Test
    fun checkLatestMarksAnOlderInstalledCliOutdated() {
        val (installer, npm, env) = fixture()
        val path = env.join(prefix.path, "bin", "pi")
        env.files += path
        npm.versions[path] = "0.9.0"
        assertEquals("installed", installer.status().harnesses.single { it.id == "pi" }.state, "no registry call unless asked")
        val checked = installer.status(checkLatest = true).harnesses.single { it.id == "pi" }
        assertEquals("outdated", checked.state)
        assertEquals("9.9.9", checked.latest)
    }

    @Test
    fun aCliWhoseVersionCannotBeReadIsUnknownNotMissing() {
        val (installer, _, env) = fixture()
        env.files += env.join(prefix.path, "bin", "claude") // no version registered: --version fails
        assertEquals("unknown", installer.status().harnesses.single { it.id == "claude" }.state)
    }

    @Test
    fun anUnknownIdIsRefusedBeforeAnythingRuns() {
        val (installer, npm, _) = fixture()
        assertFailsWith<IllegalArgumentException> { installer.install(listOf("claude", "emacs"), update = false) }
        assertTrue(npm.calls.isEmpty())
    }

    @Test
    fun anOverridePathIsUserManagedCheckedButNeverInstalledOrUpdated() {
        val (installer, npm, env) = fixture()
        env.files += "/opt/mine/claude"
        npm.versions["/opt/mine/claude"] = "1.0.0 (Claude Code)"
        overrides.set("claude", "/opt/mine/claude")

        val ok = installer.status().harnesses.single { it.id == "claude" }
        assertEquals("user-managed", ok.state)
        assertEquals("1.0.0", ok.installedVersion, "a real --version check ran against the override path")

        val result = installer.install(listOf("claude"), update = true)
        assertEquals("skipped", result.outcomes.single().result)
        assertTrue(npm.calls.none { it.getOrNull(1) == "install" }, "npm never runs for a user-managed harness")

        overrides.set("claude", "/opt/gone/claude") // the user's path stops working
        val broken = installer.status().harnesses.single { it.id == "claude" }
        assertEquals("user-managed", broken.state)
        assertNull(broken.installedVersion)
        assertNotNull(broken.note, "the broken path is explained, not silently replaced by another install")
    }

    @Test
    fun versionsCompareNumericallyAndAPreReleaseSortsFirst() {
        assertTrue(HarnessInstaller.compareVersions("1.10.0", "1.9.0") > 0)
        assertEquals(0, HarnessInstaller.compareVersions("2.0", "2.0.0"))
        assertTrue(HarnessInstaller.compareVersions("2.0.0-beta.1", "2.0.0") < 0)
    }
}
