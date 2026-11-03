package com.superiorsoftllc.infantry.policy

import com.superiorsoftllc.infantry.plugin.api.PolicyPreset
import kotlinx.serialization.json.Json
import java.io.File

/**
 * POLICY-1 (docs/POLICY.md section 2): where policy presets come from. Kept to one method on purpose: DAEMON-PLUGINS-1
 * registers its own providers through it. A provider never decides which preset is active; [PolicyRuntime] does.
 */
fun interface PolicyProvider {
    fun presets(): List<PolicyPreset>
}

/** The one preset core ships: production, nothing refused. */
object BuiltInPolicies : PolicyProvider {
    override fun presets(): List<PolicyPreset> = listOf(PolicyPreset.Production)
}

/**
 * Presets as data: every `*.json` in [dirs], read on each call (a dropped file shows up without a restart). An unreadable
 * file is logged and skipped, never fatal.
 */
class DirectoryPolicyProvider(
    private val dirs: () -> List<File>,
    private val log: (String) -> Unit = { System.err.println(it) },
) : PolicyProvider {
    override fun presets(): List<PolicyPreset> = dirs().flatMap { dir ->
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.mapNotNull { file ->
            runCatching { json.decodeFromString(PolicyPreset.serializer(), file.readText()) }
                .onFailure { log("[policy] ${file.path}: invalid preset, skipped (${it.message?.lineSequence()?.first()})") }
                .getOrNull()
        }
    }

    companion object {
        const val DIRS_ENV = "INFANTRY_POLICY_DIRS"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * [DIRS_ENV] (path list), then every `<plugins root>/<folder>/<Module>/policies/` (a dev run's plugins root is the
         * repo's `plugins/`, so its plugins' presets are found), then the bundled modules' ones.
         */
        fun defaultDirs(pluginsRoot: File?, bundled: File?, getenv: (String) -> String?): List<File> = buildList {
            getenv(DIRS_ENV)?.split(File.pathSeparatorChar)?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { add(File(it)) }
            for (root in listOfNotNull(pluginsRoot, bundled)) {
                root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.forEach { folder ->
                    folder.listFiles { f -> f.isDirectory && !f.name.startsWith("_") }.orEmpty().sortedBy { it.name }
                        .map { File(it, "policies") }.filter { it.isDirectory }.forEach(::add)
                }
            }
        }
    }
}
