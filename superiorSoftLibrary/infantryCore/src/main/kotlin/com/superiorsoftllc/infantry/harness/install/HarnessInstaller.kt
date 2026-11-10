// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.install

import com.superiorsoftllc.infantry.executables.CliTool
import com.superiorsoftllc.infantry.executables.CliTools
import com.superiorsoftllc.infantry.executables.ExecutableEnvironment
import com.superiorsoftllc.infantry.executables.ExecutableOverrides
import com.superiorsoftllc.infantry.executables.ExecutableResolver
import com.superiorsoftllc.infantry.executables.HarnessPrefix
import com.superiorsoftllc.infantry.executables.HostOs
import com.superiorsoftllc.infantry.protocol.HarnessInstallOutcome
import com.superiorsoftllc.infantry.protocol.HarnessInstallReport
import com.superiorsoftllc.infantry.protocol.HarnessInstallResult
import com.superiorsoftllc.infantry.protocol.HarnessInstallStatus
import java.io.File
import java.util.concurrent.TimeUnit

/** One harness CLI the installer knows: its [tool] and the npm package that ships it. */
class InstallableHarness(val tool: CliTool, val npmPackage: String, val sample: File? = null) {
    val id: String get() = tool.id
}

/** What a finished child process left behind. [exit] -1 = it could not be started or timed out ([output] says which). */
class ProcessResult(val exit: Int, val output: String)

/** Runs a child process; replaceable so the installer is tested against a fake npm and never touches the network. */
fun interface HarnessProcessRunner {
    fun run(argv: List<String>, env: Map<String, String>, timeoutMillis: Long, onLine: (String) -> Unit): ProcessResult
}

/** The real thing: argv child, stdout+stderr merged, killed on timeout. */
object SystemProcessRunner : HarnessProcessRunner {
    override fun run(argv: List<String>, env: Map<String, String>, timeoutMillis: Long, onLine: (String) -> Unit): ProcessResult {
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(true).also { it.environment().putAll(env) }.start()
        } catch (e: Exception) {
            return ProcessResult(-1, "could not start ${argv.first()}: ${e.message ?: e::class.simpleName}")
        }
        process.outputStream.close()
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        val reader = Thread {
            process.inputStream.bufferedReader().forEachLine { lines += it; onLine(it) }
        }.also { it.isDaemon = true; it.start() }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly().waitFor()
            reader.join(2_000)
            return ProcessResult(-1, "timed out after ${timeoutMillis / 1000}s\n" + lines.joinToString("\n"))
        }
        reader.join(5_000)
        return ProcessResult(process.exitValue(), lines.joinToString("\n"))
    }
}

/**
 * HARNESS-INSTALL-1: installs and updates the harness CLIs (claude, opencode, pi) through npm, into the app's OWN
 * prefix dir ([prefixDir], `<INFANTRY_HOME>/harness-tools`), so it needs no admin rights and never touches a global npm
 * prefix, the user's data or their own installs. Only Node/npm is required, and only for these CLIs, not for Infantry AI.
 *
 *  - DETECT: [status] resolves each CLI through the launch resolver and reads `--version`.
 *  - INSTALL: [install] runs `npm install -g --prefix <prefixDir> <package>@<version|latest>`, one at a time. Idempotent:
 *    a CLI that is already installed is skipped without running npm unless `update` asks for a refresh.
 *  - VERSIONS: default is npm's `latest`, because these CLIs track fast-moving provider APIs and an old pin stops working.
 *    A pin is opt-in per harness through `INFANTRY_<ID>_VERSION` (e.g. `INFANTRY_CLAUDE_VERSION=2.1.0`); a pinned CLI is
 *    `outdated` when its version differs, and `update` installs exactly that version.
 *  - CREDENTIALS: never handled here. Each CLI keeps its own login (`claude` sign-in, `opencode auth login`, `pi`, or
 *    the provider API-key environment variables the user sets); the installer stores no key and passes none to npm.
 */
class HarnessInstaller(
    private val prefixDir: File,
    private val resolver: ExecutableResolver,
    private val environment: ExecutableEnvironment,
    private val overrides: ExecutableOverrides = ExecutableOverrides.NONE,
    private val runner: HarnessProcessRunner = SystemProcessRunner,
    private val builtIn: List<InstallableHarness> = DEFAULT_HARNESSES,
    /**
     * HARNESS-PLUGIN-4 H5a: the CLIs the loaded harness modules declare in `harness.cli.install` (read on every call, so a
     * module loaded or unloaded since is seen). A built-in id wins; the manifest side is checked by [ManifestHarnessCli].
     */
    private val fromModules: () -> List<InstallableHarness> = { emptyList() },
    /** HARNESS-DESCRIPTOR-INSTALL-1: JSON descriptors with an `install` field (user folder, then shipped samples), read per call. */
    private val fromDescriptors: () -> List<InstallableHarness> = { emptyList() },
    /** Called before a shipped sample's install: copies it into the user's harnesses folder and registers it. */
    private val onSampleAdopted: (InstallableHarness) -> Unit = {},
) {
    private val lock = Any()

    /** Built-ins first (unchanged order), then each module's CLI whose id no earlier entry holds. */
    private fun harnesses(): List<InstallableHarness> {
        val ids = builtIn.map { it.id }.toMutableSet()
        val extra = runCatching(fromModules).getOrDefault(emptyList()) + runCatching(fromDescriptors).getOrDefault(emptyList())
        return builtIn + extra.filter { ids.add(it.id) }
    }

    fun status(checkLatest: Boolean = false): HarnessInstallReport {
        val npm = resolver.resolve(NPM)
        val harnesses = harnesses()
        return HarnessInstallReport(
            harnesses = harnesses.map { statusOf(it, checkLatest, npm.path) },
            prefixDir = prefixDir.path,
            npmPath = npm.path,
            guidance = if (npm.path == null) NODE_GUIDANCE else null,
        )
    }

    /**
     * Install / update [ids] (empty = everything missing, plus everything outdated when [update]). One run at a time; a
     * second caller waits. An unknown id is an [IllegalArgumentException] and nothing runs.
     */
    fun install(ids: List<String>, update: Boolean, onLine: (String) -> Unit = {}): HarnessInstallResult = synchronized(lock) {
        val harnesses = harnesses()
        val unknown = ids.filter { id -> harnesses.none { it.id == id } }
        require(unknown.isEmpty()) { "unknown harness ${unknown.joinToString()} (installable: ${harnesses.joinToString { it.id }})" }

        val log = ArrayList<String>()
        fun note(line: String) { onLine(line); if (log.size < LOG_CAP) log += line }

        val npm = resolver.resolve(NPM)
        val before = harnesses.map { statusOf(it, checkLatest = false, npmPath = npm.path) }
        val targets = before.filter { s ->
            if (ids.isNotEmpty()) s.id in ids else s.state == "missing" || (update && s.state == "outdated")
        }
        val outcomes = ArrayList<HarnessInstallOutcome>()
        for (s in targets) {
            if (s.state == "user-managed") {
                outcomes += HarnessInstallOutcome(s.id, "skipped", s.installedVersion, "user-managed: a path is set in Settings, so Infantry AI never installs or updates it")
                continue
            }
            if (s.state != "missing" && !update) {
                outcomes += HarnessInstallOutcome(s.id, "skipped", s.installedVersion, "already installed")
                continue
            }
            val npmPath = npm.path
            if (npmPath == null) {
                outcomes += HarnessInstallOutcome(s.id, "failed", null, NODE_GUIDANCE)
                continue
            }
            val h = harnesses.first { it.id == s.id }
            if (h.sample != null) {
                note("[${h.id}] adopting the shipped descriptor ${h.sample.name}")
                val err = runCatching { onSampleAdopted(h) }.exceptionOrNull()
                if (err != null) {
                    outcomes += HarnessInstallOutcome(h.id, "failed", null, "could not copy the descriptor: ${err.message ?: err::class.simpleName}")
                    continue
                }
            }
            outcomes += runInstall(h, s, npmPath, ::note)
        }
        HarnessInstallResult(outcomes, log, status())
    }

    private fun runInstall(h: InstallableHarness, before: HarnessInstallStatus, npmPath: String, note: (String) -> Unit): HarnessInstallOutcome {
        // Defence in depth: the package and the pinned version reach npm as argv elements, never as a flag.
        if (!ManifestHarnessCli.NPM_PACKAGE.matches(h.npmPackage)) return HarnessInstallOutcome(h.id, "failed", null, "'${h.npmPackage.take(60)}' is not an npm package name")
        before.wanted?.let { if (!VERSION_SPEC.matches(it)) return HarnessInstallOutcome(h.id, "failed", null, "the pinned version '${it.take(40)}' is not a version") }
        val spec = h.npmPackage + "@" + (before.wanted ?: "latest")
        if (!prefixDir.isDirectory && !prefixDir.mkdirs()) {
            return HarnessInstallOutcome(h.id, "failed", null, "cannot create the install directory ${prefixDir.path}")
        }
        note("[${h.id}] npm install $spec -> ${prefixDir.path}")
        val argv = listOf(npmPath, "install", "-g", "--prefix", prefixDir.path, "--no-audit", "--no-fund", "--loglevel", "error", spec)
        val result = runner.run(argv, childEnv(npmPath), INSTALL_TIMEOUT_MS) { note("[${h.id}] $it") }
        if (result.exit != 0) {
            val tail = result.output.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
            return HarnessInstallOutcome(h.id, "failed", null, "npm exited ${result.exit}: ${tail.ifEmpty { "no output" }}")
        }
        val after = statusOf(h, checkLatest = false, npmPath = npmPath)
        return if (after.path == null) {
            HarnessInstallOutcome(h.id, "failed", null, "npm reported success but ${h.tool.label} was not found afterwards")
        } else {
            HarnessInstallOutcome(h.id, if (before.state == "missing") "installed" else "updated", after.installedVersion)
        }
    }

    private fun statusOf(h: InstallableHarness, checkLatest: Boolean, npmPath: String?): HarnessInstallStatus {
        val resolution = resolver.resolve(h.tool)
        val userPath = runCatching { overrides.get(h.id) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val wanted = environment.env("INFANTRY_${h.id.uppercase().replace('-', '_')}_VERSION")?.trim()?.takeIf { it.isNotEmpty() }
        val latest = if (checkLatest && npmPath != null) latestVersion(h, npmPath) else null
        val path = resolution.path
            ?: return if (userPath != null) {
                HarnessInstallStatus(h.id, h.tool.label, h.npmPackage, "user-managed", latest = latest, note = resolution.reason)
            } else {
                HarnessInstallStatus(h.id, h.tool.label, h.npmPackage, "missing", wanted = wanted, latest = latest,
                    note = if (h.sample != null) "available, not installed: a shipped sample; installing copies ${h.sample.name} into your harnesses folder" else resolution.reason)
            }
        val version = detectVersion(path)
        val target = wanted ?: latest
        if (userPath != null) {
            // The owner set a path, so they installed it: only report whether that path really runs here (a real check, server-side).
            val why = if (path == null) resolution.reason else if (version == null) "found at $path but --version could not be read" else null
            return HarnessInstallStatus(h.id, h.tool.label, h.npmPackage, "user-managed", version, path, false, null, latest, why)
        }
        val state = when {
            version == null -> "unknown"
            wanted != null && compareVersions(version, wanted) != 0 -> "outdated"
            wanted == null && target != null && compareVersions(version, target) < 0 -> "outdated"
            else -> "installed"
        }
        return HarnessInstallStatus(h.id, h.tool.label, h.npmPackage, state, version, path, HarnessPrefix.contains(prefixDir, path), wanted, latest)
    }

    private fun latestVersion(h: InstallableHarness, npmPath: String): String? {
        val r = runner.run(listOf(npmPath, "view", h.npmPackage, "version"), childEnv(npmPath), VIEW_TIMEOUT_MS) {}
        return if (r.exit == 0) VERSION.find(r.output)?.value else null
    }

    /** `<exe> --version`, first version-looking token; a Pi `.js` entry point runs on node. Null = unreadable. */
    private fun detectVersion(path: String): String? {
        val argv = if (path.endsWith(".js", ignoreCase = true)) {
            val node = resolver.resolve(CliTools.NODE).path ?: return null
            listOf(node, path, "--version")
        } else {
            listOf(path, "--version")
        }
        val r = runner.run(argv, emptyMap(), VERSION_TIMEOUT_MS) {}
        return if (r.exit == 0) VERSION.find(r.output)?.value else null
    }

    /** npm's scripts re-invoke `node`: put npm's and node's directories first on PATH. */
    private fun childEnv(npmPath: String): Map<String, String> {
        val dirs = listOfNotNull(File(npmPath).parent, resolver.resolve(CliTools.NODE).path?.let { File(it).parent }).distinct()
        val key = if (environment.os == HostOs.WINDOWS) "Path" else "PATH"
        val existing = environment.env("PATH") ?: environment.env("Path")
        return mapOf(key to (dirs + listOfNotNull(existing)).joinToString(File.pathSeparator))
    }

    companion object {
        val CLAUDE = InstallableHarness(CliTools.CLAUDE, "@anthropic-ai/claude-code")
        val OPENCODE = InstallableHarness(CliTools.OPENCODE, "opencode-ai")
        val PI = InstallableHarness(CliTools.PI, "@earendil-works/pi-coding-agent")
        val DEFAULT_HARNESSES = listOf(CLAUDE, OPENCODE, PI)

        /** npm: its env var, beside the node install locations, then PATH. Not Settings-listed: no run launches it. */
        val NPM = CliTool(
            id = "npm",
            label = "npm",
            commandNames = listOf("npm"),
            envVar = "INFANTRY_NPM_EXE",
            defaults = { e ->
                CliTools.NODE.defaults(e).mapNotNull { node ->
                    File(node).parent?.let { e.join(it, if (e.os == HostOs.WINDOWS) "npm.cmd" else "npm") }
                }
            },
        )

        const val NODE_GUIDANCE =
            "Node.js / npm was not found, so the harness CLIs cannot be installed here. Install Node.js 20 or newer " +
                "(https://nodejs.org, or `winget install OpenJS.NodeJS.LTS`, `brew install node`, `apt install nodejs npm`), " +
                "then run the install again. Node is needed only for the harness CLIs, not for Infantry AI. " +
                "If npm lives somewhere unusual, set its path in INFANTRY_NPM_EXE."

        private val VERSION_SPEC = Regex("[0-9A-Za-z][0-9A-Za-z.+-]{0,63}")
        private val VERSION = Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.\-]+)?""")
        private const val LOG_CAP = 400
        private const val INSTALL_TIMEOUT_MS = 600_000L
        private const val VIEW_TIMEOUT_MS = 30_000L
        private const val VERSION_TIMEOUT_MS = 15_000L

        /** Numeric major.minor.patch compare; a pre-release suffix sorts before the release. */
        fun compareVersions(a: String, b: String): Int {
            fun core(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val x = core(a)
            val y = core(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            val pa = a.contains('-')
            val pb = b.contains('-')
            return if (pa == pb) 0 else if (pa) -1 else 1
        }
    }
}
