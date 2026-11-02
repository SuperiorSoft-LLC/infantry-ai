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

    private val boardRoots = java.util.concurrent.CopyOnWriteArrayList<File>()
    init { Runtime.getRuntime().addShutdownHook(Thread { boardRoots.forEach { runCatching { it.deleteRecursively() } } }) }

    /**
     * BOARD-API-2: a plugins root holding the built Infantry_Board daemon jar (+ [more] official modules), for a test whose daemon
     * must serve `agenthq_ticket_get`; deleted when the test JVM exits.
     */
    fun boardRoot(vararg more: String): File = root("official/Infantry_Board", *more).also { boardRoots += it }

    /** AR-4b-ii-b: a plugins root holding only the built Infantry_Roles daemon jar (the roles every daemon test assumes); deleted when the JVM exits. */
    fun rolesRoot(): File = root("official/Infantry_Roles").also { boardRoots += it }

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

    /** Copies module [src] to [dst]: module.json, `policies/`, and the jars its `daemon.jar` / `harness.jar` name (which must be built). */
    fun copy(src: File, dst: File) {
        dst.mkdirs()
        val manifest = File(src, "module.json")
        manifest.copyTo(File(dst, "module.json"), overwrite = true)
        File(src, "policies").takeIf { it.isDirectory }?.copyRecursively(File(dst, "policies"), overwrite = true)
        // HARNESS-PLUGIN-3: the harness half's jar too (`harness.jar`, built by :harness-plugin-<folder>-<Module>:jar).
        for (half in listOf("daemon", "harness")) {
            val jar = (Json.parseToJsonElement(manifest.readText()) as JsonObject)[half]?.let { it as? JsonObject }?.get("jar")?.jsonPrimitive?.content
                ?: continue
            val built = File(src, jar)
            check(built.isFile) { "${built.path} is not built (run :$half-plugin-<folder>-${src.name}:jar)" }
            built.copyTo(File(dst, jar), overwrite = true)
        }
    }
}
