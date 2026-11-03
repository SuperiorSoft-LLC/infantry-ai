package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Where opencode keeps what the user configured. EVERY location is a value, so a test points them at a
 * fixture directory and never touches the real home: nothing in [OpenCodeConfigReader] reaches for
 * `user.home` or the process environment on its own ([fromEnvironment] is the one place that does).
 *
 *  - [configFiles]: `opencode.json` then `opencode.jsonc` (later wins on a clash), then any
 *    `OPENCODE_CONFIG` / `OPENCODE_CONFIG_DIR` override. Custom providers/models, `disabled_providers`,
 *    `enabled_providers` and the default `model` live here.
 *  - [authFile]: `auth.json`, whose keys are the providers the user has signed in to (`opencode-go`, ...).
 *  - [modelsFile]: opencode's cached models.dev catalogue (`models.json`): the model list of every
 *    BUILT-IN provider. Without it only config-defined models are known.
 *  - [env]: the environment a provider's API-key variable is looked up in (`DEEPSEEK_API_KEY`, ...).
 */
data class OpenCodeConfigLocations(
    val configFiles: List<File>,
    val authFile: File,
    val modelsFile: File,
    val env: Map<String, String> = emptyMap(),
) {
    internal fun allFiles(): List<File> = configFiles + authFile + modelsFile

    companion object {
        /**
         * opencode's own layout (xdg-basedir, on every OS, Windows included): config in
         * `$XDG_CONFIG_HOME|~/.config/opencode`, auth in `$XDG_DATA_HOME|~/.local/share/opencode`, the
         * model cache in `$XDG_CACHE_HOME|~/.cache/opencode`.
         */
        fun fromEnvironment(
            env: Map<String, String> = System.getenv(),
            home: File = File(System.getProperty("user.home")),
        ): OpenCodeConfigLocations {
            fun base(xdg: String, fallback: String) =
                File(env[xdg]?.takeIf { it.isNotBlank() }?.let { File(it) } ?: File(home, fallback), "opencode")

            val configDir = base("XDG_CONFIG_HOME", ".config")
            val dataDir = base("XDG_DATA_HOME", ".local/share")
            val cacheDir = base("XDG_CACHE_HOME", ".cache")
            val files = buildList {
                add(File(configDir, "opencode.json"))
                add(File(configDir, "opencode.jsonc"))
                env["OPENCODE_CONFIG"]?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
                env["OPENCODE_CONFIG_DIR"]?.takeIf { it.isNotBlank() }?.let {
                    add(File(it, "opencode.json"))
                    add(File(it, "opencode.jsonc"))
                }
            }
            return OpenCodeConfigLocations(
                configFiles = files,
                authFile = File(dataDir, "auth.json"),
                modelsFile = env["OPENCODE_MODELS_PATH"]?.takeIf { it.isNotBlank() }?.let { File(it) }
                    ?: File(cacheDir, "models.json"),
                env = env,
            )
        }
    }
}

/** One provider the user can actually run: [models] are full `provider/model` ids, exactly what `-m` takes. */
data class ConfiguredProvider(val id: String, val name: String, val models: List<String>)

/**
 * What the user's opencode configuration offers right now.
 *
 * [notes] are things worth telling the user (an unreadable config file, a missing model cache). They are
 * never dropped: a broken file must read as a broken file, not as a smaller catalogue.
 */
data class OpenCodeCatalog(
    val providers: List<ConfiguredProvider>,
    val defaultModel: String?,
    val notes: List<String>,
) {
    fun modelIds(): List<String> = providers.flatMap { it.models }
}

/**
 * Reads the user's real opencode setup (providers they signed in to, custom providers and models they
 * wrote into `opencode.jsonc`) from files, so a provider or model added in opencode shows up without a
 * code change and without spawning the CLI.
 *
 * A provider is OFFERED when it is not disabled and is signed in (`auth.json`), has its API-key
 * environment variable set, or is defined in the config. Its models are the models.dev cache entry
 * (minus `deprecated` ones) plus the config's own `models`, honouring a provider's `whitelist` /
 * `blacklist`.
 *
 * Honest limit: a provider contributed by an opencode PLUGIN exists in neither file, so it is invisible
 * here. The result is memoised on the files' size/mtime, so editing a config is picked up on the next
 * read and an unchanged one costs nothing.
 */
class OpenCodeConfigReader(private val locations: OpenCodeConfigLocations) {
    private var memo: Pair<String, OpenCodeCatalog>? = null

    @Synchronized
    fun read(): OpenCodeCatalog {
        val stamp = Jsonc.fingerprint(locations.allFiles())
        memo?.takeIf { it.first == stamp }?.let { return it.second }
        return load().also { memo = stamp to it }
    }

    private fun load(): OpenCodeCatalog {
        val notes = mutableListOf<String>()
        fun parsed(file: File): JsonObject? = when (val r = Jsonc.read(file)) {
            ConfigFile.Absent -> null
            is ConfigFile.Unreadable -> { notes += "${r.file.name} is unreadable: ${r.reason.text}"; null }
            is ConfigFile.Parsed -> r.root
        }

        val configs = locations.configFiles.mapNotNull(::parsed)
        val auth = parsed(locations.authFile)
        val builtIn = parsed(locations.modelsFile)
        if (builtIn == null && notes.none { it.startsWith(locations.modelsFile.name) }) {
            notes += "opencode model cache (${locations.modelsFile.name}) not found: only models written in the config are known"
        }

        val disabled = configs.flatMap { it["disabled_providers"].asStringList().orEmpty() }.toSet()
        val enabled = configs.mapNotNull { it["enabled_providers"].asStringList() }.lastOrNull()?.toSet()
        val signedIn = auth?.keys.orEmpty()

        // Per-provider config blocks, merged across files (later file wins field by field).
        val customIds = linkedSetOf<String>()
        configs.forEach { c -> c["provider"].asObject()?.keys?.let(customIds::addAll) }
        fun customBlocks(id: String) = configs.mapNotNull { it["provider"].asObject()?.get(id).asObject() }

        val candidateIds = linkedSetOf<String>().apply {
            addAll(signedIn)
            addAll(customIds)
            builtIn?.forEach { (id, p) ->
                val envNames = p.asObject()?.get("env")?.asStringList().orEmpty()
                if (envNames.any { !locations.env[it].isNullOrBlank() }) add(id)
            }
        }

        val providers = candidateIds
            .filter { it !in disabled && (enabled == null || it in enabled) }
            .map { id ->
                val dev = builtIn?.get(id).asObject()
                val blocks = customBlocks(id)
                val name = blocks.mapNotNull { it["name"].asString() }.lastOrNull()
                    ?: dev?.get("name").asString() ?: id
                val ids = linkedSetOf<String>()
                dev?.get("models").asObject()?.forEach { (mid, m) ->
                    if (m.asObject()?.get("status").asString() != "deprecated") ids += mid
                }
                blocks.forEach { b -> b["models"].asObject()?.keys?.let(ids::addAll) }
                val whitelist = blocks.mapNotNull { it["whitelist"].asStringList() }.lastOrNull()?.takeIf { it.isNotEmpty() }
                val blacklist = blocks.flatMap { it["blacklist"].asStringList().orEmpty() }.toSet()
                val kept = ids.filter { (whitelist == null || it in whitelist) && it !in blacklist }
                ConfiguredProvider(id, name, kept.sorted().map { "$id/$it" })
            }
            .sortedBy { it.id }

        return OpenCodeCatalog(
            providers = providers,
            defaultModel = configs.mapNotNull { it["model"].asString() }.lastOrNull(),
            notes = notes,
        )
    }
}
