package com.superiorsoftllc.infantry.harness.descriptor

import com.superiorsoftllc.infantry.harness.BoundedCommand
import com.superiorsoftllc.infantry.protocol.ProviderModels
import com.superiorsoftllc.infantry.provider.HarnessModelCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One model a descriptor declares: [provider] is the row group, [id] what the harness's `--model` accepts. */
data class DescriptorModel(val provider: String, val id: String, val label: String? = null)

/**
 * ROLE-FIRST S5 - the optional `models` section of a harness descriptor: where that harness's provider/model listing
 * comes from, so a descriptor harness shows models in the picker with no Kotlin code. Exactly one of:
 *
 * ```json
 * "models": { "list": [ {"provider": "openai", "id": "openai/gpt-5", "label": "OpenAI"}, ... ] }
 * "models": { "modelsCommand": ["my-cli", "--list-models"] }
 * ```
 *
 * `list`: static rows. `label` is optional and names the provider GROUP (the first label given for a provider wins;
 * none = the provider id) -- the `models` frame has no per-model label, and this section adds none.
 * `modelsCommand`: an argv list (never a shell) run as the S4 bounded child (20 s timeout, 1 MiB stdout cap, stdin
 * closed, killed with its descendants); stdout is one `provider/model` per line. Blank lines and lines that are not
 * a plain `provider/model` token are skipped; the provider is the text before the first `/`.
 */
sealed interface DescriptorModels {
    data class Static(val models: List<DescriptorModel>) : DescriptorModels
    data class Command(val argv: List<String>) : DescriptorModels

    companion object {
        const val MAX_ROWS = 2000

        internal val PROVIDER = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}")
        internal val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@+/-]{0,255}")

        /**
         * A `modelsCommand` element may not carry what sh or cmd.exe would interpret: `; & | < > $ % ^ * ? ' "`, the
         * backtick, CR, LF. No shell runs it, but a `.cmd`/`.bat` target IS run by cmd.exe on Windows. Parentheses
         * stay allowed, for `C:\Program Files (x86)`.
         */
        private val SHELL_META = Regex("[;&|<>`$%^*?'\"\\r\\n]")

        /** Validate the section ([element] null = no section). Every failure is a FIXED [InvalidDescriptor] reason. */
        fun parse(element: JsonElement?): DescriptorModels? {
            if (element == null) return null
            val obj = element as? JsonObject ?: throw InvalidDescriptor("field 'models' is not an object")
            val list = obj["list"]
            val command = obj["modelsCommand"]
            return when {
                list != null && command != null -> throw InvalidDescriptor("field 'models' has both 'list' and 'modelsCommand'")
                list != null -> Static(parseList(list))
                command != null -> Command(parseCommand(command))
                else -> throw InvalidDescriptor("field 'models' has neither 'list' nor 'modelsCommand'")
            }
        }

        private fun parseList(element: JsonElement): List<DescriptorModel> {
            val array = element as? JsonArray ?: throw InvalidDescriptor("'models.list' is not an array")
            if (array.size > MAX_ROWS) throw InvalidDescriptor("'models.list' holds more than $MAX_ROWS models")
            val seen = HashSet<Pair<String, String>>()
            return array.map { item ->
                val row = item as? JsonObject ?: throw InvalidDescriptor("a 'models.list' entry is not an object")
                val provider = row.text("provider")?.takeIf { PROVIDER.matches(it) }
                    ?: throw InvalidDescriptor("a 'models.list' entry has an empty or invalid provider")
                val id = row.text("id")?.takeIf { MODEL_ID.matches(it) }
                    ?: throw InvalidDescriptor("a 'models.list' entry has an empty or invalid id")
                if (row["label"] != null && row.text("label") == null) throw InvalidDescriptor("a 'models.list' label is not a string")
                if (!seen.add(provider to id)) throw InvalidDescriptor("'models.list' repeats a provider and id")
                DescriptorModel(provider, id, row.text("label")?.trim()?.takeIf { it.isNotEmpty() }?.take(128))
            }
        }

        private fun parseCommand(element: JsonElement): List<String> {
            val array = element as? JsonArray ?: throw InvalidDescriptor("'models.modelsCommand' is not an array")
            val argv = array.map { it.string() ?: throw InvalidDescriptor("'models.modelsCommand' holds a non-string") }
            if (argv.isEmpty() || argv.first().isBlank()) throw InvalidDescriptor("'models.modelsCommand' is empty")
            if (argv.any { SHELL_META.containsMatchIn(it) }) throw InvalidDescriptor("'models.modelsCommand' holds a shell metacharacter")
            return argv
        }

        /** `modelsCommand` stdout -> models, in printed order, deduplicated, at most [MAX_ROWS]. */
        fun parseCommandOutput(output: String): List<DescriptorModel> {
            val out = LinkedHashSet<DescriptorModel>()
            for (raw in output.lineSequence()) {
                val line = raw.trim()
                if (!MODEL_ID.matches(line)) continue
                val provider = line.substringBefore('/', "")
                if (provider.isEmpty() || line.length == provider.length + 1 || !PROVIDER.matches(provider)) continue
                out += DescriptorModel(provider, line)
            }
            return out.toList()
        }
    }
}

/**
 * ROLE-FIRST S5 - a descriptor harness's [HarnessModelCatalog]: rows in the S4 `models` frame shape (`provider` =
 * the harness id, `group` = the upstream provider). A `modelsCommand` that fails, or prints no models, yields ONE
 * noted empty row built from a fixed reason -- never the command's output (the CP-1 leak rule). A listing over
 * [DescriptorModels.MAX_ROWS] is cut there and noted. Results are cached for [ttlMs], as Pi's are.
 */
class DescriptorModelCatalog(
    private val harnessId: String,
    private val section: DescriptorModels,
    private val run: (List<String>) -> String = { BoundedCommand.runBounded(it) },
    private val ttlMs: Long = BoundedCommand.DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : HarnessModelCatalog {
    private val lock = Any()
    private var cached: List<ProviderModels>? = null
    private var cachedAt = 0L

    override fun models(): List<ProviderModels> = synchronized(lock) {
        val now = clock()
        cached?.takeIf { now - cachedAt < ttlMs }?.let { return it }
        compute().also { cached = it; cachedAt = now }
    }

    private fun compute(): List<ProviderModels> = when (section) {
        is DescriptorModels.Static -> rows(section.models)
        is DescriptorModels.Command -> {
            val listed = try {
                DescriptorModels.parseCommandOutput(run(section.argv))
            } catch (e: Exception) {
                return noted("modelsCommand failed: ${(e.message ?: e::class.simpleName.orEmpty()).take(200)}")
            }
            when {
                listed.isEmpty() -> noted("modelsCommand listed no models")
                listed.size > DescriptorModels.MAX_ROWS -> rows(listed.take(DescriptorModels.MAX_ROWS)) +
                    ProviderModels(harnessId, BoundedCommand.NOTES_GROUP, "notes", emptyList(), "modelsCommand listed more than ${DescriptorModels.MAX_ROWS} models; the rest are not shown")
                else -> rows(listed)
            }
        }
    }

    private fun noted(why: String) = listOf(ProviderModels(harnessId, "", harnessId, emptyList(), "could not list models: $why"))

    private fun rows(models: List<DescriptorModel>): List<ProviderModels> =
        models.groupBy { it.provider }.map { (provider, group) ->
            ProviderModels(harnessId, provider, group.firstNotNullOfOrNull { it.label } ?: provider, group.map { it.id })
        }
}
