package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One module's `config` as the build step wrote it into `GeneratedConfig.JSON`. */
@Serializable
class CompiledModuleConfig(val module: String, val config: List<ConfigDeclaration> = emptyList())

/**
 * CONFIG-9 (docs/CONFIG-MANIFEST-DESIGN.md section 7): the compile-time config of a wasm or Android build. The build step
 * writes `GeneratedConfig.JSON`, the module `config` of the compile-time module list in load order; the generated module
 * list installs it here, and [TabFactory][com.superiorsoftllc.infantry.ui.plugin.TabFactory] feeds it to the same
 * [ConfigTree.merge] the desktop feeds module.json to. The desktop never installs it (it reads module.json at run time).
 */
object CompiledConfig {
    private var json: String? = null
    private var platform: String? = null

    fun install(json: String, platform: String) {
        this.json = json
        this.platform = platform
    }

    /** Each module's declarations for the installed platform; empty when nothing is installed. */
    fun declarations(problems: MutableList<String>): Map<String, List<ConfigDeclaration>> =
        json?.let { parse(it, platform.orEmpty(), problems) }.orEmpty()

    private val format = Json { ignoreUnknownKeys = true }

    /**
     * Parses [text] and drops every group and field whose `platforms` omits [platform]. Load order decides a clash: a key
     * a later module declares again on the same node replaces the earlier default (demo plugins override live ones, D16).
     */
    fun parse(text: String, platform: String, problems: MutableList<String>): Map<String, List<ConfigDeclaration>> {
        val modules = runCatching { format.decodeFromString(ListSerializer(CompiledModuleConfig.serializer()), text) }
            .getOrElse { problems += "GeneratedConfig does not parse (${it.message?.lineSequence()?.firstOrNull()}); ignored"; return emptyMap() }
        val filtered = modules.map { m ->
            m.module to m.config.filter { it.platforms?.contains(platform) != false }
                .map { d -> d.copy(fields = d.fields.filter { it.platforms?.contains(platform) != false }) }
        }
        val out = LinkedHashMap<String, List<ConfigDeclaration>>()
        for ((i, m) in filtered.withIndex()) {
            val later = filtered.drop(i + 1).flatMap { it.second }.flatMap { d -> d.fields.map { d.node to it.key } }.toSet()
            out[m.first] = out[m.first].orEmpty() + m.second.map { d -> d.copy(fields = d.fields.filter { (d.node to it.key) !in later }) }
        }
        return out
    }
}
