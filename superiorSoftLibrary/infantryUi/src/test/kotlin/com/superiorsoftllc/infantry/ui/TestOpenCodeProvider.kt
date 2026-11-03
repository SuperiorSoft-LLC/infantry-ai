package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.protocol.ProviderModels
import com.superiorsoftllc.infantry.runner.ProcessSpec

/**
 * UI-TEST-COMPILE-2: stand-in for the core `OpenCodeProvider` that HARNESS H8b-3 deleted when opencode became the
 * Infantry_OpenCode plugin. These UI tests only need an `opencode`-id provider that (a) lists `provider/model` ids grouped by
 * vendor and (b) builds the one-shot `opencode run --format json -m <model> [--session <id>]` argv; both mirror
 * OpenCodeCommand in the plugin (the plugin is compileOnly against the harness API, so it is not on this classpath).
 */
class TestOpenCodeProvider(private val lister: () -> List<String> = { emptyList() }) : AgentProvider {
    override val id: String = "opencode"

    override fun spec(request: RunRequest): ProcessSpec = ProcessSpec(
        command = buildList {
            add("opencode"); add("run"); add("--format"); add("json")
            add("-m"); add(request.model ?: defaultModel())
            request.resumeSessionId?.takeIf { it.isNotBlank() }?.let { add("--session"); add(it) }
        },
        workingDir = request.workingDir,
    )

    override fun defaultModel(): String = System.getenv("INFANTRY_OPENCODE_MODEL") ?: "opencode/deepseek-v4.1-flash-free"

    override fun models(): List<ProviderModels> {
        val byVendor = LinkedHashMap<String, MutableList<String>>()
        for (line in lister().map { it.trim() }) {
            val vendor = line.substringBefore('/').takeIf { '/' in line && it.isNotEmpty() } ?: continue
            byVendor.getOrPut(vendor) { mutableListOf() }.add(line)
        }
        return byVendor.map { (vendor, ids) -> ProviderModels(id, vendor, vendor, ids) }
    }
}
