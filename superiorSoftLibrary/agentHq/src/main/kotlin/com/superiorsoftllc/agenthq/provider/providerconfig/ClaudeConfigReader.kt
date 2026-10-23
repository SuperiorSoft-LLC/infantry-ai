package com.superiorsoftllc.agenthq.provider.providerconfig

import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Where Claude Code keeps what the user configured. Injectable like [OpenCodeConfigLocations]: a test
 * points [settingsFiles] at a fixture and passes its own [env]; only [fromEnvironment] looks at the
 * real home. [settingsFiles] are in precedence order, later wins (`settings.json`, then
 * `settings.local.json`).
 */
data class ClaudeConfigLocations(
    val settingsFiles: List<File>,
    val env: Map<String, String> = emptyMap(),
) {
    companion object {
        /** `$CLAUDE_CONFIG_DIR` or `~/.claude`, as the CLI itself resolves it. */
        fun fromEnvironment(
            env: Map<String, String> = System.getenv(),
            home: File = File(System.getProperty("user.home")),
        ): ClaudeConfigLocations {
            val dir = env["CLAUDE_CONFIG_DIR"]?.takeIf { it.isNotBlank() }?.let { File(it) } ?: File(home, ".claude")
            return ClaudeConfigLocations(listOf(File(dir, "settings.json"), File(dir, "settings.local.json")), env)
        }
    }
}

/**
 * What the user's Claude Code configuration offers right now.
 *
 * [models] are the ids/aliases `--model` takes: exactly what the picker shows. [restricted] is true
 * when the user's `availableModels` setting limits the CLI to that list, in which case [models] holds
 * ONLY those (the CLI's built-in aliases are then not selectable). [notes] carry unreadable files.
 */
data class ClaudeCatalog(
    val models: List<String>,
    val defaultModel: String?,
    val restricted: Boolean,
    val notes: List<String>,
)

/**
 * Reads the user's real Claude Code settings so a model they configured appears in the picker.
 *
 * The Claude CLI has no "list models" command, so the catalogue is assembled from the three places the
 * user can name a model, in this order:
 *  1. the CLI's own alias vocabulary ([CLI_ALIASES]) -- not user configuration, but always accepted by
 *     `--model`, and the reason the list is never empty;
 *  2. `settings.json`: `model`, `availableModels`, the keys of `modelOverrides` and of `modelSettings`;
 *  3. the model environment pins (`ANTHROPIC_MODEL`, `ANTHROPIC_DEFAULT_{OPUS,SONNET,HAIKU}_MODEL`,
 *     `ANTHROPIC_SMALL_FAST_MODEL`, `CLAUDE_CODE_SUBAGENT_MODEL`), from the settings `env` block and the
 *     process [ClaudeConfigLocations.env].
 *
 * Memoised on the files' size/mtime like [OpenCodeConfigReader], so a settings edit shows on the next read.
 */
class ClaudeConfigReader(private val locations: ClaudeConfigLocations) {
    private var memo: Pair<String, ClaudeCatalog>? = null

    @Synchronized
    fun read(): ClaudeCatalog {
        val stamp = Jsonc.fingerprint(locations.settingsFiles) + "|" + locations.env.filterKeys { it in MODEL_ENV }.toSortedMap()
        memo?.takeIf { it.first == stamp }?.let { return it.second }
        return load().also { memo = stamp to it }
    }

    private fun load(): ClaudeCatalog {
        val notes = mutableListOf<String>()
        val settings: List<JsonObject> = locations.settingsFiles.mapNotNull { f ->
            when (val r = Jsonc.read(f)) {
                ConfigFile.Absent -> null
                is ConfigFile.Unreadable -> { notes += "${r.file.name} is unreadable: ${r.reason.text}"; null }
                is ConfigFile.Parsed -> r.root
            }
        }

        val defaultModel = settings.mapNotNull { it["model"].asString() }.lastOrNull()
            ?: pinnedFromEnv(settings, "ANTHROPIC_MODEL")
        val available = settings.mapNotNull { it["availableModels"].asStringList() }.lastOrNull()?.takeIf { it.isNotEmpty() }

        val models = linkedSetOf<String>()
        if (available != null) {
            models += available
            defaultModel?.let(models::add)
            return ClaudeCatalog(models.toList(), defaultModel, restricted = true, notes = notes)
        }

        models += CLI_ALIASES
        defaultModel?.let(models::add)
        settings.forEach { s ->
            s["modelOverrides"].asObject()?.keys?.let(models::addAll)
            s["modelSettings"].asObject()?.keys?.let(models::addAll)
        }
        MODEL_ENV.forEach { name -> pinnedFromEnv(settings, name)?.let(models::add) }
        return ClaudeCatalog(models.toList(), defaultModel, restricted = false, notes = notes)
    }

    /** [name] from the settings `env` block (later file wins), else the process environment. */
    private fun pinnedFromEnv(settings: List<JsonObject>, name: String): String? =
        settings.mapNotNull { it["env"].asObject()?.get(name).asString() }.lastOrNull()
            ?: locations.env[name]?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /** The aliases the `claude` CLI documents for `--model`; it has no command that lists them. */
        val CLI_ALIASES: List<String> = listOf("opus", "sonnet", "haiku")

        internal val MODEL_ENV = listOf(
            "ANTHROPIC_MODEL",
            "ANTHROPIC_DEFAULT_OPUS_MODEL",
            "ANTHROPIC_DEFAULT_SONNET_MODEL",
            "ANTHROPIC_DEFAULT_HAIKU_MODEL",
            "ANTHROPIC_SMALL_FAST_MODEL",
            "CLAUDE_CODE_SUBAGENT_MODEL",
        )
    }
}
