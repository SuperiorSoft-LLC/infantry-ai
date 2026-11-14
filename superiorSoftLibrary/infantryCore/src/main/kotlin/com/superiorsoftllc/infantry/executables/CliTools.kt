// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.executables

/**
 * CLIPATH-1: one CLI the daemon launches. [id] is the harness/provider id (or `git`) and the key its Settings override
 * is stored under. [commandNames] are the bare names searched on PATH (Windows adds PATHEXT extensions).
 * [extraExtensions] are file types that are launchable through something else (Pi's `cli.js`, run with node).
 * [defaults] is the tool's known install locations for the environment's OS, most specific first.
 * [prefixBin] (HARNESS-PLUGIN-4 H5a) is where the harness installer's npm install leaves the CLI inside its prefix on
 * Windows (`node_modules/<package>/<bin>`), for a tool declared by a harness module's `harness.cli.install`; null = the
 * built-in table in [HarnessPrefix].
 */
class CliTool(
    val id: String,
    val label: String,
    val commandNames: List<String>,
    val envVar: String? = null,
    val extraExtensions: Set<String> = emptySet(),
    val defaults: (ExecutableEnvironment) -> List<String> = { emptyList() },
    val prefixBin: String? = null,
) {
    override fun toString(): String = "CliTool($id)"
}

/**
 * The known-default-locations table, one entry per CLI, per OS. Each entry was checked against the CLI's own installer:
 *
 *  - claude: the native installer (`claude.ai/install.sh` / `install.ps1`) puts `claude` / `claude.exe` in
 *    `~/.local/bin`; the older local install used `~/.claude/local`; `npm i -g @anthropic-ai/claude-code` ships a native
 *    `bin/claude.exe` under the npm prefix (`%APPDATA%\npm` on Windows) next to its `claude.cmd` shim; Homebrew puts it
 *    in `/opt/homebrew/bin` (Apple silicon) or `/usr/local/bin`.
 *  - opencode: the install script defaults to `~/.opencode/bin` (`$OPENCODE_INSTALL_DIR` overrides it); npm
 *    (`opencode-ai`) ships a native `bin/opencode.exe`; scoop shims to `~/scoop/shims`; Homebrew as above.
 *  - pi (`@earendil-works/pi-coding-agent`, npm only): the bundled `dist/bundle/cli.js` under the npm prefix is
 *    preferred on Windows (run with node, no `.cmd` shell hop, see [com.superiorsoftllc.infantry.harness.pi.PiCommand]);
 *    the `pi.cmd` shim, then the Unix npm prefixes (`$NPM_CONFIG_PREFIX`, `~/.npm-global`, `/usr/local`, Homebrew).
 *  - node (CLIPATH-2): what Pi's `cli.js` runs on; see [NODE] for its table.
 *  - git: Git for Windows installs to `%ProgramFiles%\Git\cmd\git.exe` (per-user: `%LOCALAPPDATA%\Programs\Git`);
 *    macOS has `/usr/bin/git` (Xcode CLT) and Homebrew; Linux distributions ship `/usr/bin/git`.
 *
 * Native executables come before npm `.cmd` shims on Windows: a `.cmd` puts cmd.exe between the daemon and the process
 * it must be able to kill.
 */
object CliTools {
    val CLAUDE = CliTool(
        id = "claude",
        label = "Claude Code",
        commandNames = listOf("claude"),
        envVar = "INFANTRY_CLAUDE_EXE",
        prefixBin = "node_modules/@anthropic-ai/claude-code/bin/claude.exe",
        defaults = { e ->
            val home = e.home()
            when (e.os) {
                HostOs.WINDOWS -> buildList {
                    val npmNative = "npm/node_modules/@anthropic-ai/claude-code/bin/claude.exe"
                    e.env("APPDATA")?.let { add(e.join(it, npmNative)) }
                    e.env("LOCALAPPDATA")?.let { add(e.join(it, npmNative)) }
                    (e.env("USERPROFILE") ?: home)?.let {
                        add(e.join(it, ".local/bin/claude.exe"))
                        add(e.join(it, ".claude/local/claude.exe"))
                    }
                    e.env("APPDATA")?.let { add(e.join(it, "npm/claude.cmd")) }
                }
                HostOs.MAC -> buildList {
                    home?.let { add(e.join(it, ".local/bin/claude")); add(e.join(it, ".claude/local/claude")) }
                    add("/opt/homebrew/bin/claude")
                    add("/usr/local/bin/claude")
                }
                HostOs.LINUX -> buildList {
                    home?.let { add(e.join(it, ".local/bin/claude")); add(e.join(it, ".claude/local/claude")) }
                    add("/usr/local/bin/claude")
                    add("/usr/bin/claude")
                }
            }
        },
    )

    val OPENCODE = CliTool(
        id = "opencode",
        label = "OpenCode",
        commandNames = listOf("opencode"),
        envVar = "INFANTRY_OPENCODE_EXE",
        prefixBin = "node_modules/opencode-ai/bin/opencode.exe",
        defaults = { e ->
            val home = e.home()
            val installDir = e.env("OPENCODE_INSTALL_DIR")?.takeIf { it.isNotBlank() }
            when (e.os) {
                HostOs.WINDOWS -> buildList {
                    installDir?.let { add(e.join(it, "opencode.exe")) }
                    val npmNative = "npm/node_modules/opencode-ai/bin/opencode.exe"
                    e.env("APPDATA")?.let { add(e.join(it, npmNative)) }
                    e.env("LOCALAPPDATA")?.let { add(e.join(it, npmNative)) }
                    (e.env("USERPROFILE") ?: home)?.let {
                        add(e.join(it, ".opencode/bin/opencode.exe"))
                        add(e.join(it, "scoop/shims/opencode.exe"))
                    }
                    e.env("APPDATA")?.let { add(e.join(it, "npm/opencode.cmd")) }
                }
                HostOs.MAC -> buildList {
                    installDir?.let { add(e.join(it, "opencode")) }
                    home?.let { add(e.join(it, ".opencode/bin/opencode")) }
                    add("/opt/homebrew/bin/opencode")
                    add("/usr/local/bin/opencode")
                    home?.let { add(e.join(it, ".local/bin/opencode")) }
                }
                HostOs.LINUX -> buildList {
                    installDir?.let { add(e.join(it, "opencode")) }
                    home?.let { add(e.join(it, ".opencode/bin/opencode")); add(e.join(it, ".local/bin/opencode")) }
                    add("/usr/local/bin/opencode")
                    add("/usr/bin/opencode")
                }
            }
        },
    )

    /** Pi's bundled entry point under an npm prefix's `node_modules`. */
    private const val PI_CLI_JS = "node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js"

    val PI = CliTool(
        id = "pi",
        label = "Pi",
        commandNames = listOf("pi"),
        envVar = "INFANTRY_PI_EXE",
        prefixBin = PI_CLI_JS,
        extraExtensions = setOf(".js"),
        defaults = { e ->
            val home = e.home()
            val prefix = e.env("NPM_CONFIG_PREFIX")?.takeIf { it.isNotBlank() }
            when (e.os) {
                HostOs.WINDOWS -> buildList {
                    prefix?.let { add(e.join(it, PI_CLI_JS)) }
                    e.env("APPDATA")?.let { add(e.join(it, "npm", PI_CLI_JS)) }
                    e.env("LOCALAPPDATA")?.let { add(e.join(it, "npm", PI_CLI_JS)) }
                    prefix?.let { add(e.join(it, "pi.cmd")) }
                    e.env("APPDATA")?.let { add(e.join(it, "npm/pi.cmd")) }
                }
                HostOs.MAC, HostOs.LINUX -> buildList {
                    prefix?.let { add(e.join(it, "bin/pi")) }
                    home?.let { add(e.join(it, ".npm-global/bin/pi")) }
                    if (e.os == HostOs.MAC) add("/opt/homebrew/bin/pi")
                    add("/usr/local/bin/pi")
                    home?.let { add(e.join(it, ".local/bin/pi")) }
                    add("/usr/bin/pi")
                }
            }
        },
    )

    val GIT = CliTool(
        id = "git",
        label = "Git",
        commandNames = listOf("git"),
        envVar = "INFANTRY_GIT_EXE",
        defaults = { e ->
            when (e.os) {
                HostOs.WINDOWS -> buildList {
                    add(e.join(e.env("ProgramFiles") ?: "C:\\Program Files", "Git/cmd/git.exe"))
                    e.env("ProgramW6432")?.let { add(e.join(it, "Git/cmd/git.exe")) }
                    add(e.join(e.env("ProgramFiles(x86)") ?: "C:\\Program Files (x86)", "Git/cmd/git.exe"))
                    e.env("LOCALAPPDATA")?.let { add(e.join(it, "Programs/Git/cmd/git.exe")) }
                    (e.env("USERPROFILE") ?: e.home())?.let { add(e.join(it, "scoop/shims/git.exe")) }
                }
                HostOs.MAC -> listOf("/usr/bin/git", "/opt/homebrew/bin/git", "/usr/local/bin/git")
                HostOs.LINUX -> listOf("/usr/bin/git", "/usr/local/bin/git")
            }
        },
    )

    /**
     * CLIPATH-2: the Node.js runtime Pi's `cli.js` entry point runs on. `INFANTRY_NODE_EXE` is the pre-CLIPATH-2 knob.
     * Defaults, Windows: the official installer (`%ProgramFiles%\nodejs\node.exe`), then nvm-windows' active version
     * (the `%NVM_SYMLINK%` directory it re-points on `nvm use`), then the x86, per-user and scoop locations.
     * macOS/Linux: the official pkg / distro (`/usr/local/bin/node`), Homebrew (`/opt/homebrew/bin/node`), nvm's active
     * version (`$NVM_BIN` when the daemon was started from an nvm shell, then `~/.nvm/current/bin/node`, the
     * `NVM_SYMLINK_CURRENT` layout), then `/usr/bin/node`. PATH comes last, as for every tool.
     */
    val NODE = CliTool(
        id = "node",
        label = "Node.js",
        commandNames = listOf("node"),
        envVar = "INFANTRY_NODE_EXE",
        // ONBOARD-2: the runtime NodeRuntimeInstaller unpacks into the harness prefix (only when the machine had none).
        prefixBin = "node.exe",
        defaults = { e ->
            val home = e.home()
            when (e.os) {
                HostOs.WINDOWS -> buildList {
                    add(e.join(e.env("ProgramFiles") ?: "C:\\Program Files", "nodejs/node.exe"))
                    e.env("ProgramW6432")?.let { add(e.join(it, "nodejs/node.exe")) }
                    e.env("NVM_SYMLINK")?.takeIf { it.isNotBlank() }?.let { add(e.join(it, "node.exe")) }
                    add(e.join(e.env("ProgramFiles(x86)") ?: "C:\\Program Files (x86)", "nodejs/node.exe"))
                    e.env("LOCALAPPDATA")?.let { add(e.join(it, "Programs/nodejs/node.exe")) }
                    (e.env("USERPROFILE") ?: home)?.let { add(e.join(it, "scoop/apps/nodejs/current/node.exe")) }
                }
                HostOs.MAC, HostOs.LINUX -> buildList {
                    add("/usr/local/bin/node")
                    if (e.os == HostOs.MAC) add("/opt/homebrew/bin/node")
                    e.env("NVM_BIN")?.takeIf { it.isNotBlank() }?.let { add(e.join(it, "node")) }
                    home?.let { add(e.join(it, ".nvm/current/bin/node")) }
                    add("/usr/bin/node")
                }
            }
        },
    )

    /** The built-in table, in the order Settings lists it. CLIPATH-2 appended [NODE]: existing ids keep their places. */
    val KNOWN: List<CliTool> = listOf(CLAUDE, OPENCODE, PI, GIT, NODE)

    fun known(id: String): CliTool? = KNOWN.firstOrNull { it.id == id }

    /**
     * The tool for a harness with no built-in entry (a descriptor harness): its id, and [command] searched on PATH, or,
     * when [command] is already a path, that path as its one default location.
     */
    fun forCommand(id: String, label: String, command: String): CliTool {
        val isPath = command.contains('/') || command.contains('\\')
        return CliTool(
            id = id,
            label = label,
            commandNames = if (isPath) emptyList() else listOf(command),
            defaults = { if (isPath) listOf(command) else emptyList() },
        )
    }
}
