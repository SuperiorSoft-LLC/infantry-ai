package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.harness.AgentProvider

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PROV-1: a provider is added by dropping a JSON file in a provider directory -- no recompile.
 *
 * The whole value of the feature is that the definition lives in DATA, not in a compiled
 * [AgentProvider] subclass, so the seam ([ProviderFactory.fromFlag]) resolves it exactly like a
 * built-in. One test, one file, argv read straight from the JSON.
 */
class ConfigProviderLoaderTest {

    @Test
    fun jsonProviderIsResolvedById() {
        val dir: File = Files.createTempDirectory("agenthq-prov1-").toFile()
        File(dir, "aider.json").writeText(
            """{"id":"aider","command":"aider","args":["--message-file","-"]}""",
        )

        ConfigProviderLoader(dir).load()

        assertEquals("aider", ProviderFactory.fromFlag("aider").id)
    }
}
