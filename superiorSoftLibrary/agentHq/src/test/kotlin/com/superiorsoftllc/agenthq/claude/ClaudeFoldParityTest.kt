package com.superiorsoftllc.agenthq.claude

import com.superiorsoftllc.agenthq.harness.claude.ClaudeHarness
import com.superiorsoftllc.agenthq.provider.ClaudeCodeProvider
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.provider.providerconfig.ClaudeCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** H7a: the provider and the harness are one layer; the provider adds nothing of its own. */
class ClaudeFoldParityTest {
    private val provider = ClaudeCodeProvider { ClaudeCatalog(listOf("sonnet"), null, true, listOf("n")) }

    @Test
    fun providerSpecIsTheHarnessSpec() {
        val r = RunRequest("p", File("w"), model = "sonnet", resumeSessionId = "abc")
        assertEquals(ClaudeHarness.specFor(r), provider.spec(r))
    }

    @Test
    fun modelsAndDefaultComeFromTheHarness() {
        assertEquals(ClaudeHarness.models(ClaudeCatalog(listOf("sonnet"), null, true, listOf("n"))), provider.models())
        assertEquals(ClaudeHarness.defaultModel(), provider.defaultModel())
        assertEquals("limited by your availableModels setting", provider.models().single().note?.substringAfter("n; "))
    }
}
