package com.superiorsoftllc.infantry.harness.install

import com.superiorsoftllc.infantry.executables.CliTool
import com.superiorsoftllc.infantry.executables.CliTools
import com.superiorsoftllc.infantry.plugin.api.HarnessCliDeclaration

/**
 * HARNESS-PLUGIN-4 H5a (docs/HARNESS-PLUGINS.md 3.8): a harness module's `harness.cli` block as the CLI tool the resolver
 * looks up and the installable the [HarnessInstaller] installs. Nothing here names a harness: everything comes from the
 * manifest, which [refusal] checks before the module is loaded (a refused block refuses the whole module).
 */
object ManifestHarnessCli {
    /** An npm package name (scoped or not), lower case, no version, no flags: it is passed to npm as one argv element. */
    val NPM_PACKAGE = Regex("(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]{0,213}")

    /** A bare command name searched on PATH; never a path, so a manifest cannot point the daemon at an arbitrary file. */
    val COMMAND = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    private val CLI_ID = Regex("[a-z0-9][a-z0-9-]{0,39}")

    /** The CLI ids the daemon's own table holds (and npm, the installer's tool): a module may not take one over. */
    fun builtInIds(): Set<String> = CliTools.KNOWN.map { it.id }.toSet() + HarnessInstaller.NPM.id

    /** Why [cli] may not be used; null = it may. */
    fun refusal(cli: HarnessCliDeclaration, allowBuiltIn: Boolean = false): String? {
        if (!CLI_ID.matches(cli.id)) return "harness.cli.id '${cli.id.take(40)}' must match ${CLI_ID.pattern}"
        // H6c: a bundled/official module whose harness id IS the CLI id (the pi plugin) may name the CLI the daemon's table already holds: the table's entry resolves it.
        if (!allowBuiltIn && cli.id in builtInIds()) return "harness.cli.id '${cli.id}' is a built-in CLI of the daemon"
        cli.command?.let { if (!COMMAND.matches(it)) return "harness.cli.command '${it.take(40)}' must be a bare command name (${COMMAND.pattern}), not a path" }
        val install = cli.install ?: return null
        val npm = install.npm ?: return if (install.bin != null) "harness.cli.install.bin without harness.cli.install.npm" else null
        if (!NPM_PACKAGE.matches(npm)) return "harness.cli.install.npm '${npm.take(60)}' is not an npm package name"
        install.bin?.let { bin ->
            val parts = bin.split('/')
            if (bin.isBlank() || bin.startsWith("/") || '\\' in bin || ':' in bin || parts.any { it.isEmpty() || it == "." || it == ".." }) {
                return "harness.cli.install.bin '${bin.take(60)}' must be a relative path inside the package (forward slashes, no '..')"
            }
        }
        return null
    }

    /** The resolver's tool for [cli]: its command on PATH, and (when it installs through npm) its place in the installer's prefix. */
    fun tool(cli: HarnessCliDeclaration): CliTool {
        val command = cli.command ?: cli.id
        val install = cli.install
        val bin = install?.bin
        val prefixBin = if (install?.npm != null) "node_modules/${install.npm}/${bin ?: "bin/$command"}" else null
        val ext = bin?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() && it !in WINDOWS_NATIVE }
        return CliTool(
            id = cli.id,
            label = cli.label ?: command,
            commandNames = listOf(command),
            extraExtensions = ext?.let { setOf(".$it") }.orEmpty(),
            prefixBin = prefixBin,
        )
    }

    /** The installer's entry for [cli], or null when it declares no npm install. */
    fun installable(cli: HarnessCliDeclaration): InstallableHarness? =
        cli.install?.npm?.let { InstallableHarness(tool(cli), it) }

    private val WINDOWS_NATIVE = setOf("exe", "cmd", "bat", "com")
}
