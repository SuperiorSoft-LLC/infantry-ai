package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.harness.HarnessRegistry
import com.superiorsoftllc.infantry.harness.descriptor.EchoCliFixture
import com.superiorsoftllc.infantry.harness.descriptor.HarnessDescriptorLoader
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.ProviderModels
import com.superiorsoftllc.infantry.provider.HarnessModelCatalogs
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ROLE-FIRST S5: a harness declared ONLY by a JSON descriptor with a `models` section shows its models in the
 * existing `models` frame of a REAL daemon -- no Kotlin code for that harness, no wire change.
 */
class DescriptorModelsDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun models(descriptor: String): List<ProviderModels> {
        val dir = EchoCliFixture.tempDir("s5-daemon")
        File(dir, "acme.json").writeText(descriptor)
        val registry = HarnessRegistry()
        val report = HarnessDescriptorLoader(dir).loadInto(registry)
        assertTrue(report.notes.isEmpty(), "unexpected notes: ${report.notes}")
        val fixture = DaemonFixture.launch(harnesses = registry, modelCatalogs = HarnessModelCatalogs.EMPTY)
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.MODELS))
            return json.decodeFromString(ListSerializer(ProviderModels.serializer()), fixture.client.await(Envelope.MODELS).data)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun aDescriptorStaticModelsListReachesTheModelsFrame() {
        val all = models(
            """{"id": "acme", "command": "acme-cli", "output": {"format": "text"},
               "models": {"list": [
                 {"provider": "openai", "id": "openai/gpt-x", "label": "OpenAI"},
                 {"provider": "openai", "id": "openai/gpt-y"},
                 {"provider": "local", "id": "local/llama"}
               ]}}""",
        )
        val acme = all.filter { it.provider == "acme" }
        assertEquals(listOf("openai", "local"), acme.map { it.group }, "$all")
        assertEquals(listOf("openai/gpt-x", "openai/gpt-y"), acme.first().models)
        assertEquals("OpenAI", acme.first().label)
        assertEquals("local", acme.last().label, "no label = the provider")
        assertTrue(all.any { it.provider == "stub" }, "the configured provider is still listed: $all")
    }

    @Test
    fun aDescriptorModelsCommandReachesTheModelsFrame() {
        val dir = EchoCliFixture.tempDir("s5-cmd")
        val src = File(dir, "Lister.java").apply {
            writeText(
                """
                public class Lister { public static void main(String[] a) {
                    System.out.println("openai/gpt-x"); System.out.println("");
                    System.out.println("deepseek/deepseek-v4"); System.out.println("not a model line");
                } }
                """.trimIndent(),
            )
        }
        val q = { s: String -> Json.encodeToString(kotlinx.serialization.serializer<String>(), s) }
        val all = models(
            """{"id": "acme", "command": "acme-cli", "output": {"format": "text"},
               "models": {"modelsCommand": [${q(EchoCliFixture.javaExecutable())}, ${q(src.path)}]}}""",
        )
        val acme = all.filter { it.provider == "acme" }
        assertEquals(listOf("openai", "deepseek"), acme.map { it.group }, "$all")
        assertEquals(listOf("deepseek/deepseek-v4"), acme.last().models)
    }
}
