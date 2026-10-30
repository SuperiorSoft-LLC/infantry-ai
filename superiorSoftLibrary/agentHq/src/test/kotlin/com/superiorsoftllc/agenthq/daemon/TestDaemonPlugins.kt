package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.daemon.plugins.DaemonPluginHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files

/**
 * DAEMON-PLUGINS-1: plugins roots for daemon tests, built from the repo's real modules and their BUILT daemon jars (the
 * agentHq test task depends on each `:daemon-plugin-*:jar`), so a test runs the plugin exactly as a deployment loads it.
 */
object TestDaemonPlugins {
    val repo: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "plugins/modules.json").isFile }
            ?: error("the repo root (plugins/modules.json) was not found above ${File("").absolutePath}")
    }

    /** Trusts every official module (a test has no signing key). */
    val trustAll = DaemonPluginHost.Trust(emptyList(), devTrust = true, bundledRoot = null)

    /** An empty plugins root under [stateDir]: the daemon loads no plugin and never reads the environment's root. */
    fun none(stateDir: File): File = File(stateDir, "no-plugins").apply {
        mkdirs()
        File(this, "modules.json").writeText("""{"folders":[]}""")
    }

    /**
     * A temp plugins root holding [modules] (repo paths under `plugins/`, e.g. `demo/Demo_Lock`) under [folder] (tier
     * [tier]): each module.json, its `policies/` and its built daemon jar. [modulesJson] is the root's `modules` map.
     */
    fun root(vararg modules: String, folder: String = "official", tier: String = "official", modulesJson: String = "{}"): File {
        val root = Files.createTempDirectory("daemon-plugins-root").toFile()
        File(root, "modules.json").writeText("""{"folders":[{"path":"$folder","tier":"$tier"}],"modules":$modulesJson}""")
        for (m in modules) copy(File(repo, "plugins/$m"), File(root, "$folder/${File(m).name}"))
        return root
    }

    /** Copies module [src] to [dst]: module.json, `policies/`, and the jar its `daemon.jar` names (which must be built). */
    fun copy(src: File, dst: File) {
        dst.mkdirs()
        val manifest = File(src, "module.json")
        manifest.copyTo(File(dst, "module.json"), overwrite = true)
        File(src, "policies").takeIf { it.isDirectory }?.copyRecursively(File(dst, "policies"), overwrite = true)
        val jar = (Json.parseToJsonElement(manifest.readText()) as JsonObject)["daemon"]?.let { it as? JsonObject }?.get("jar")?.jsonPrimitive?.content
            ?: return
        val built = File(src, jar)
        check(built.isFile) { "${built.path} is not built (run :daemon-plugin-<folder>-${src.name}:jar)" }
        built.copyTo(File(dst, jar), overwrite = true)
    }
}
