package com.superiorsoftllc.infantry.agents.registry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 3.3): the user's override layer over the registry, behind ONE interface.
 * Owner O5: overrides belong in the unified config JSON (docs/CONFIG-MANIFEST-DESIGN.md). [FileAgentOverrides] is the INTERIM
 * store; the swap point is this interface: a config-backed implementation replaces it in DaemonMain, nothing else changes.
 */
interface AgentOverrides {
    /** The agent ids the user disabled. */
    fun disabled(): Set<String>

    /** Records [id] as disabled ([disabled] = true) or not; true when that changed anything. */
    fun setDisabled(id: String, disabled: Boolean): Boolean
}

/** In memory only (tests, a daemon without a state dir). */
class MemoryAgentOverrides : AgentOverrides {
    private val ids = LinkedHashSet<String>()
    @Synchronized override fun disabled(): Set<String> = ids.toSet()
    @Synchronized override fun setDisabled(id: String, disabled: Boolean): Boolean = if (disabled) ids.add(id) else ids.remove(id)
}

/** The interim store: `<state dir>/agents/overrides.json` (`{"disabled":[...]}`); unreadable = no overrides (logged). */
class FileAgentOverrides(private val file: File, private val log: (String) -> Unit = { System.err.println(it) }) : AgentOverrides {
    @Serializable
    private data class Shape(val disabled: List<String> = emptyList())

    private val ids: MutableSet<String> = load().toMutableSet()

    @Synchronized override fun disabled(): Set<String> = ids.toSet()

    @Synchronized override fun setDisabled(id: String, disabled: Boolean): Boolean {
        val changed = if (disabled) ids.add(id) else ids.remove(id)
        if (changed) save()
        return changed
    }

    private fun load(): List<String> {
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString(Shape.serializer(), file.readText()).disabled }
            .onFailure { log("[agents] ${file.path} is unreadable, ignored: ${it.message}") }.getOrDefault(emptyList())
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(json.encodeToString(Shape.serializer(), Shape(ids.sorted())))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        /** `<state dir>/agents/overrides.json`. */
        fun inStateDir(stateDir: File, log: (String) -> Unit = { System.err.println(it) }) = FileAgentOverrides(File(stateDir, "agents/overrides.json"), log)

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
