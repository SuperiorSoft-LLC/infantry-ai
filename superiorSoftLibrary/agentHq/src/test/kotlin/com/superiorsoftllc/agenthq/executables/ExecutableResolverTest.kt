package com.superiorsoftllc.agenthq.executables

import com.superiorsoftllc.agenthq.harness.pi.PiCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CLIPATH-1: the one executable resolver, over a FAKE file system and environment so every case is decided by the test
 * on any OS. Order under test: Settings override > env var > known default install location > PATH.
 */
class ExecutableResolverTest {

    /** A fake machine: [files] are regular files (true = executable bit set), [dirs] are directories. */
    private class FakeEnv(
        override val os: HostOs,
        private val vars: Map<String, String> = emptyMap(),
        private val home: String? = null,
        val files: MutableMap<String, Boolean> = mutableMapOf(),
        val dirs: MutableSet<String> = mutableSetOf(),
    ) : ExecutableEnvironment {
        override fun env(name: String): String? = vars[name]
        override fun home(): String? = home
        override fun exists(path: String): Boolean = path in files || path in dirs
        override fun isDirectory(path: String): Boolean = path in dirs
        override fun isExecutable(path: String): Boolean = files[path] == true
        override fun isAbsolute(path: String): Boolean =
            if (os == HostOs.WINDOWS) Regex("^[A-Za-z]:[\\\\/].*").matches(path) || path.startsWith("\\\\") else path.startsWith("/")
    }

    private fun linux(vararg files: String, path: String = "/usr/bin:/bin", vars: Map<String, String> = emptyMap()) =
        FakeEnv(HostOs.LINUX, vars + ("PATH" to path), home = "/home/u", files = files.associateWith { true }.toMutableMap())

    private val windowsVars = mapOf(
        "APPDATA" to "C:\\Users\\u\\AppData\\Roaming",
        "LOCALAPPDATA" to "C:\\Users\\u\\AppData\\Local",
        "USERPROFILE" to "C:\\Users\\u",
        "ProgramFiles" to "C:\\Program Files",
        "PATHEXT" to ".COM;.EXE;.BAT;.CMD;.VBS;.PS1",
    )

    private fun windows(vararg files: String, path: String) =
        FakeEnv(HostOs.WINDOWS, windowsVars + ("PATH" to path), home = "C:\\Users\\u", files = files.associateWith { true }.toMutableMap())

    @Test
    fun theOverrideBeatsTheDefaultLocationWhichBeatsPath() {
        val env = linux("/opt/custom/claude", "/home/u/.local/bin/claude", "/usr/bin/claude", path = "/usr/bin")
        val overrides = ExecutableOverrides.InMemory(mapOf("claude" to "/opt/custom/claude"))

        val withOverride = ExecutableResolver(env, overrides).resolve(CliTools.CLAUDE)
        assertEquals("/opt/custom/claude", withOverride.path)
        assertEquals(ExecutableResolver.Source.OVERRIDE, withOverride.source)

        overrides.set("claude", null)
        val withDefault = ExecutableResolver(env, overrides).resolve(CliTools.CLAUDE)
        assertEquals("/home/u/.local/bin/claude", withDefault.path, "the known install dir wins over PATH")
        assertEquals(ExecutableResolver.Source.DEFAULT_LOCATION, withDefault.source)

        env.files.remove("/home/u/.local/bin/claude")
        // /usr/bin/claude is ALSO a Linux default location; take it off the table to reach PATH alone.
        env.files.remove("/usr/bin/claude")
        env.files["/srv/tools/claude"] = true
        val onPath = ExecutableResolver(FakeEnv(HostOs.LINUX, mapOf("PATH" to "/srv/tools"), "/home/u", env.files), overrides)
            .resolve(CliTools.CLAUDE)
        assertEquals("/srv/tools/claude", onPath.path)
        assertEquals(ExecutableResolver.Source.PATH, onPath.source)
    }

    @Test
    fun theEnvironmentVariableSitsBetweenTheOverrideAndTheDefaults() {
        val env = linux("/env/claude", "/home/u/.local/bin/claude", vars = mapOf("AGENTHQ_CLAUDE_EXE" to "/env/claude"))
        assertEquals(ExecutableResolver.Source.ENVIRONMENT, ExecutableResolver(env).resolve(CliTools.CLAUDE).source)

        val overridden = ExecutableResolver(env, ExecutableOverrides.InMemory(mapOf("claude" to "/home/u/.local/bin/claude")))
            .resolve(CliTools.CLAUDE)
        assertEquals(ExecutableResolver.Source.OVERRIDE, overridden.source)

        val brokenEnv = linux("/home/u/.local/bin/claude", vars = mapOf("AGENTHQ_CLAUDE_EXE" to "/gone/claude"))
        val skipped = ExecutableResolver(brokenEnv).resolve(CliTools.CLAUDE)
        assertEquals("/home/u/.local/bin/claude", skipped.path, "a broken env var is skipped, as it was never validated")
        assertEquals("/gone/claude", skipped.tried.first())
    }

    @Test
    fun notFoundListsEveryPathItTried() {
        val env = linux(path = "/a:/b")
        val resolution = ExecutableResolver(env).resolve(CliTools.OPENCODE)

        assertNull(resolution.path)
        assertNull(resolution.source)
        val expected = listOf(
            "/home/u/.opencode/bin/opencode", "/home/u/.local/bin/opencode", "/usr/local/bin/opencode", "/usr/bin/opencode",
            "/a/opencode", "/b/opencode",
        )
        assertEquals(expected, resolution.tried)
        val reason = assertNotNull(resolution.reason)
        assertTrue(reason.startsWith("OpenCode not found"), reason)
        assertTrue("Settings > CLI locations" in reason, reason)

        val thrown = assertFailsWith<IllegalStateException> { ExecutableResolver(env).require(CliTools.OPENCODE) }
        expected.forEach { assertTrue(it in thrown.message.orEmpty(), "the launch failure names '$it': ${thrown.message}") }
    }

    @Test
    fun aBrokenStoredOverrideIsReportedNotSilentlyReplaced() {
        val env = linux("/home/u/.local/bin/claude")
        val resolution = ExecutableResolver(env, ExecutableOverrides.InMemory(mapOf("claude" to "/moved/claude"))).resolve(CliTools.CLAUDE)

        assertNull(resolution.path, "the user said to use /moved/claude; a different binary must not run instead")
        assertEquals("/moved/claude", resolution.override)
        assertEquals(listOf("/moved/claude"), resolution.tried)
        assertTrue("does not exist" in resolution.reason.orEmpty(), resolution.reason)
    }

    @Test
    fun validateRefusesABadPathWithAReason() {
        val env = linux("/ok/claude").apply {
            files["/noexec/claude"] = false
            dirs += "/some/dir"
        }
        val resolver = ExecutableResolver(env)

        assertNull(resolver.validate(CliTools.CLAUDE, "/ok/claude"))
        assertTrue("does not exist" in resolver.validate(CliTools.CLAUDE, "/nope/claude").orEmpty())
        assertTrue("is a directory" in resolver.validate(CliTools.CLAUDE, "/some/dir").orEmpty())
        assertTrue("is not executable" in resolver.validate(CliTools.CLAUDE, "/noexec/claude").orEmpty())
        assertTrue("not an absolute path" in resolver.validate(CliTools.CLAUDE, "bin/claude").orEmpty())
        assertTrue("empty" in resolver.validate(CliTools.CLAUDE, "  ").orEmpty())
    }

    @Test
    fun onWindowsAnExtensionlessShimOnPathIsNeverPickedAndTheCmdIs() {
        val npm = "C:\\Users\\u\\AppData\\Roaming\\npm"
        // npm writes `pi` (a sh script), `pi.cmd` and `pi.ps1`; only the .cmd is launchable by CreateProcess.
        val env = windows("C:\\tools\\pi", "C:\\tools\\pi.ps1", "C:\\tools\\pi.cmd", path = "C:\\tools;$npm")
        val resolution = ExecutableResolver(env).resolve(CliTools.PI)

        assertEquals("C:\\tools\\pi.cmd", resolution.path)
        assertEquals(ExecutableResolver.Source.PATH, resolution.source)
        assertFalse("C:\\tools\\pi" in resolution.tried, "an extensionless name is never a Windows candidate")
        assertTrue("is not a Windows executable" in ExecutableResolver(env).validate(CliTools.PI, "C:\\tools\\pi").orEmpty())
    }

    @Test
    fun theWindowsDefaultsComeFromTheTableAndPiTakesItsCliJs() {
        val cliJs = "C:\\Users\\u\\AppData\\Roaming\\npm\\node_modules\\@earendil-works\\pi-coding-agent\\dist\\bundle\\cli.js"
        val env = windows(cliJs, "C:\\Users\\u\\AppData\\Roaming\\npm\\pi.cmd", "C:\\Program Files\\Git\\cmd\\git.exe", path = "C:\\Windows")

        val pi = ExecutableResolver(env).resolve(CliTools.PI)
        assertEquals(cliJs, pi.path, "the bundled cli.js comes before the .cmd shim")
        assertEquals(listOf("node", cliJs), PiCommand.launcherFor(pi.path!!, node = { "node" }))
        assertEquals(listOf("C:\\x\\pi.cmd"), PiCommand.launcherFor("C:\\x\\pi.cmd", node = { "node" }))

        val git = ExecutableResolver(env).resolve(CliTools.GIT)
        assertEquals("C:\\Program Files\\Git\\cmd\\git.exe", git.path)
        assertEquals(ExecutableResolver.Source.DEFAULT_LOCATION, git.source)
    }

    /**
     * CLIPATH-2: node goes through the resolver like every other CLI. Windows order: Settings override >
     * AGENTHQ_NODE_EXE > `%ProgramFiles%\nodejs\node.exe` > nvm-windows' active version (`%NVM_SYMLINK%`) > PATH.
     */
    @Test
    fun nodeResolvesOverrideThenEnvThenProgramFilesThenNvmWindowsThenPath() {
        val programFiles = "C:\\Program Files\\nodejs\\node.exe"
        val nvm = "C:\\nvm4w\\nodejs\\node.exe"
        val fromEnv = "C:\\env\\node.exe"
        val onPath = "C:\\tools\\node.exe"
        val custom = "C:\\custom\\node.exe"
        val env = FakeEnv(
            HostOs.WINDOWS,
            windowsVars + mapOf("PATH" to "C:\\tools", "NVM_SYMLINK" to "C:\\nvm4w\\nodejs", "AGENTHQ_NODE_EXE" to fromEnv),
            home = "C:\\Users\\u",
            files = listOf(programFiles, nvm, fromEnv, onPath, custom).associateWith { true }.toMutableMap(),
        )
        val overrides = ExecutableOverrides.InMemory(mapOf("node" to custom))
        fun resolve() = ExecutableResolver(env, overrides).resolve(CliTools.NODE)

        assertEquals(custom to ExecutableResolver.Source.OVERRIDE, resolve().let { it.path to it.source })
        overrides.set("node", null)
        assertEquals(fromEnv to ExecutableResolver.Source.ENVIRONMENT, resolve().let { it.path to it.source })
        env.files.remove(fromEnv)
        assertEquals(programFiles to ExecutableResolver.Source.DEFAULT_LOCATION, resolve().let { it.path to it.source })
        env.files.remove(programFiles)
        assertEquals(nvm to ExecutableResolver.Source.DEFAULT_LOCATION, resolve().let { it.path to it.source }, "nvm-windows after Program Files")
        env.files.remove(nvm)
        assertEquals(onPath to ExecutableResolver.Source.PATH, resolve().let { it.path to it.source }, "PATH last")

        val tried = resolve().tried
        assertTrue(tried.indexOf(fromEnv) < tried.indexOf(programFiles), "env before defaults: $tried")
        assertTrue(tried.indexOf(programFiles) < tried.indexOf(nvm), "Program Files before nvm-windows: $tried")
        assertTrue(tried.indexOf(nvm) < tried.indexOf(onPath), "defaults before PATH: $tried")
    }

    /** CLIPATH-2: macOS order is /usr/local/bin > Homebrew > nvm current > PATH; nothing found names node and every path tried. */
    @Test
    fun nodeOnMacTriesTheDefaultTableInOrderAndNotFoundSaysWhatWasTried() {
        val env = FakeEnv(
            HostOs.MAC, mapOf("PATH" to "/srv/bin"), home = "/Users/u",
            files = mutableMapOf("/opt/homebrew/bin/node" to true, "/Users/u/.nvm/current/bin/node" to true),
        )
        assertEquals("/opt/homebrew/bin/node", ExecutableResolver(env).resolve(CliTools.NODE).path)
        env.files.remove("/opt/homebrew/bin/node")
        assertEquals("/Users/u/.nvm/current/bin/node", ExecutableResolver(env).resolve(CliTools.NODE).path)
        env.files.clear()

        val missing = ExecutableResolver(env).resolve(CliTools.NODE)
        assertNull(missing.path)
        assertEquals(
            listOf("/usr/local/bin/node", "/opt/homebrew/bin/node", "/Users/u/.nvm/current/bin/node", "/usr/bin/node", "/srv/bin/node"),
            missing.tried,
        )
        val reason = missing.reason.orEmpty()
        assertTrue(reason.startsWith("Node.js not found. Tried 5 location(s) and PATH (node)"), reason)
        assertTrue("Settings > CLI locations" in reason, reason)
        val thrown = assertFailsWith<IllegalStateException> { ExecutableResolver(env).require(CliTools.NODE) }
        assertTrue("/opt/homebrew/bin/node" in thrown.message.orEmpty(), "the failure lists the tried paths: ${thrown.message}")
    }

    /** CLIPATH-2: Pi's `.js` entry point runs on the node the INSTALLED resolver picks, not on a bare `node`. */
    @Test
    fun piRunsItsCliJsOnTheResolvedNode() {
        val restore = CliExecutables.install(
            ExecutableResolver(linux("/opt/node/bin/node"), ExecutableOverrides.InMemory(mapOf("node" to "/opt/node/bin/node"))),
        )
        try {
            assertEquals(listOf("/opt/node/bin/node", "/x/cli.js"), PiCommand.launcherFor("/x/cli.js"))
            assertEquals(listOf("/x/pi"), PiCommand.launcherFor("/x/pi"), "a native pi needs no node")
        } finally {
            restore()
        }
    }

    @Test
    fun nodeIsListedInTheBuiltInTableAfterTheExistingTools() {
        assertEquals(listOf("claude", "opencode", "pi", "git", "node"), CliTools.KNOWN.map { it.id })
        assertTrue(CliTools.known("node") === CliTools.NODE)
    }

    @Test
    fun aDescriptorCommandIsSearchedOnPathAndAnAbsoluteOneIsItsOwnDefault() {
        val env = linux("/usr/bin/echo-cli", "/opt/x/run.sh")
        assertEquals("/usr/bin/echo-cli", ExecutableResolver(env).resolve(CliTools.forCommand("echo-cli", "Echo", "echo-cli")).path)
        val absolute = ExecutableResolver(env).resolve(CliTools.forCommand("x", "X", "/opt/x/run.sh"))
        assertEquals("/opt/x/run.sh", absolute.path)
        assertEquals(ExecutableResolver.Source.DEFAULT_LOCATION, absolute.source)
    }

    @Test
    fun theInstalledResolverIsWhatLaunchersUseAndRestoreGivesTheOldOneBack() {
        val before = CliExecutables.resolver
        val fake = ExecutableResolver(linux("/fake/git"), ExecutableOverrides.InMemory(mapOf("git" to "/fake/git")))
        val restore = CliExecutables.install(fake)
        try {
            assertEquals("/fake/git", CliExecutables.require(CliTools.GIT))
        } finally {
            restore()
        }
        assertTrue(CliExecutables.resolver === before)
    }
}
