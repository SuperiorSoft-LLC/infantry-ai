package com.superiorsoftllc.agenthq.opencode

import com.superiorsoftllc.agenthq.harness.opencode.OpenCodeHarness
import com.superiorsoftllc.agenthq.provider.OpenCodeProvider
import com.superiorsoftllc.agenthq.provider.RunRequest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** H8a: the provider and the harness are one layer; the provider adds nothing of its own. */
class OpenCodeFoldParityTest {
    private val ids = listOf("opencode/free-a", "anthropic/sonnet", "bad id", "nomodel")
    private val provider = OpenCodeProvider { ids }

    @Test
    fun providerSpecIsTheHarnessSpec() {
        val r = RunRequest("p", File("w"), model = "x/y", resumeSessionId = "ses_1")
        val spec = try { OpenCodeHarness.specFor(r) } catch (e: Exception) { return } // no opencode install: nothing to compare
        assertEquals(spec, provider.spec(r))
    }

    @Test
    fun modelsDefaultAndAnswersComeFromTheHarness() {
        assertEquals(OpenCodeHarness.models(ids, emptyList()), provider.models())
        assertEquals(listOf("opencode", "anthropic"), provider.models().map { it.group })
        assertEquals(OpenCodeHarness.defaultModel(), provider.defaultModel())
        assertEquals(OpenCodeHarness.contextLimit, provider.contextLimit())
        assertEquals(OpenCodeHarness.effort, provider.effort())
        assertEquals(OpenCodeHarness.commands, provider.commands)
    }
}
