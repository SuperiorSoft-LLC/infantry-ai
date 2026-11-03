// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.descriptor

import com.superiorsoftllc.infantry.harness.BoundedCommand
import com.superiorsoftllc.infantry.protocol.ProviderModels
import com.superiorsoftllc.infantry.provider.HarnessModelCatalogs
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ROLE-FIRST S5: the descriptor `models` section -- validation, command output, notes, cap, cache, registry. */
class DescriptorModelsTest {

    private fun descriptor(models: String?) = HarnessDescriptor.parse(
        Json.parseToJsonElement(
            """{"id": "acme", "command": "acme-cli", "output": {"format": "text"}${if (models == null) "" else ", \"models\": $models"}}""",
        ),
    )

    private fun rejected(models: String): String = assertFailsWith<InvalidDescriptor> { descriptor(models) }.detail

    // --- validation -----------------------------------------------------------------------------

    @Test
    fun noSectionMeansNoCatalog() {
        assertNull(descriptor(null).models)
        assertNull(DescriptorHarness(descriptor(null), com.superiorsoftllc.infantry.runner.PipedProcessRunner()).modelCatalog)
    }

    @Test
    fun aStaticListAndACommandParse() {
        val static = assertIs<DescriptorModels.Static>(descriptor("""{"list": [{"provider": "p", "id": "p/m", "label": "P"}]}""").models)
        assertEquals(listOf(DescriptorModel("p", "p/m", "P")), static.models)
        val command = assertIs<DescriptorModels.Command>(descriptor("""{"modelsCommand": ["C:\\Program Files (x86)\\x.exe", "--list"]}""").models)
        assertEquals(listOf("C:\\Program Files (x86)\\x.exe", "--list"), command.argv)
    }

    @Test
    fun emptyIdsAreRejected() {
        assertEquals("a 'models.list' entry has an empty or invalid id", rejected("""{"list": [{"provider": "p", "id": ""}]}"""))
        assertEquals("a 'models.list' entry has an empty or invalid id", rejected("""{"list": [{"provider": "p"}]}"""))
        assertEquals("a 'models.list' entry has an empty or invalid id", rejected("""{"list": [{"provider": "p", "id": "has space"}]}"""))
        assertEquals("a 'models.list' entry has an empty or invalid provider", rejected("""{"list": [{"provider": "", "id": "m"}]}"""))
    }

    @Test
    fun aDuplicateProviderAndIdIsRejectedButTheSameIdUnderAnotherProviderIsNot() {
        assertEquals(
            "'models.list' repeats a provider and id",
            rejected("""{"list": [{"provider": "p", "id": "m"}, {"provider": "p", "id": "m"}]}"""),
        )
        val ok = assertIs<DescriptorModels.Static>(descriptor("""{"list": [{"provider": "p", "id": "m"}, {"provider": "q", "id": "m"}]}""").models)
        assertEquals(2, ok.models.size)
    }

    @Test
    fun shellMetacharactersInModelsCommandAreRejected() {
        for (bad in listOf(";", "&", "|", "<", ">", "`", "$", "%", "^", "*", "?", "'")) {
            val arg = Json.encodeToString(kotlinx.serialization.serializer<String>(), "a${bad}b")
            assertEquals("'models.modelsCommand' holds a shell metacharacter", rejected("""{"modelsCommand": ["cli", $arg]}"""), bad)
        }
        assertEquals("'models.modelsCommand' holds a shell metacharacter", rejected("""{"modelsCommand": ["cli", "a\nb"]}"""))
        assertEquals("'models.modelsCommand' is empty", rejected("""{"modelsCommand": []}"""))
        assertEquals("'models.modelsCommand' holds a non-string", rejected("""{"modelsCommand": ["cli", 3]}"""))
    }

    @Test
    fun anOversizeListIsRejectedAtTheCap() {
        fun list(n: Int) = (1..n).joinToString(",", "{\"list\": [", "]}") { """{"provider": "p", "id": "m$it"}""" }
        assertEquals(DescriptorModels.MAX_ROWS, assertIs<DescriptorModels.Static>(descriptor(list(DescriptorModels.MAX_ROWS)).models).models.size)
        assertEquals("'models.list' holds more than 2000 models", rejected(list(DescriptorModels.MAX_ROWS + 1)))
    }

    @Test
    fun aMalformedSectionIsRejectedWithAFixedReason() {
        assertEquals("field 'models' is not an object", rejected("[]"))
        assertEquals("field 'models' has neither 'list' nor 'modelsCommand'", rejected("{}"))
        assertEquals("field 'models' has both 'list' and 'modelsCommand'", rejected("""{"list": [], "modelsCommand": ["x"]}"""))
        val secret = rejected("""{"list": [{"provider": "p", "id": "sk-SECRET VALUE"}]}""")
        assertFalse("SECRET" in secret, "a reason never quotes the file: $secret")
    }

    // --- modelsCommand output and catalog -------------------------------------------------------

    @Test
    fun commandOutputIsOneProviderSlashModelPerLineTolerantly() {
        val out = "banner text here\n\nopenai/gpt-x\r\n  deepseek/deepseek/v4  \nopenai/gpt-x\nbare-model\n/nope\nnope/\n"
        assertEquals(
            listOf(DescriptorModel("openai", "openai/gpt-x"), DescriptorModel("deepseek", "deepseek/deepseek/v4")),
            DescriptorModels.parseCommandOutput(out),
        )
    }

    @Test
    fun aFailingCommandIsOneNotedRowWithAFixedReasonNotItsOutput() {
        val catalog = DescriptorModelCatalog("acme", DescriptorModels.Command(listOf("x")), run = { error("timed out after 20000ms") })
        val row = catalog.models().single()
        assertEquals("acme", row.provider)
        assertTrue(row.models.isEmpty())
        assertEquals("could not list models: modelsCommand failed: timed out after 20000ms", row.note)

        val empty = DescriptorModelCatalog("acme", DescriptorModels.Command(listOf("x")), run = { "SECRET-TOKEN-LINE\n" }).models().single()
        assertEquals("could not list models: modelsCommand listed no models", empty.note)
    }

    @Test
    fun aCommandListingOverTheCapIsCutAndNoted() {
        val big = (1..DescriptorModels.MAX_ROWS + 5).joinToString("\n") { "p/m$it" }
        val rows = DescriptorModelCatalog("acme", DescriptorModels.Command(listOf("x")), run = { big }).models()
        assertEquals(DescriptorModels.MAX_ROWS, rows.first { it.group == "p" }.models.size)
        assertEquals(BoundedCommand.NOTES_GROUP, rows.last().group)
    }

    @Test
    fun theCommandIsRunOncePerTtl() {
        var calls = 0
        var now = 0L
        val catalog = DescriptorModelCatalog("acme", DescriptorModels.Command(listOf("x")), run = { calls++; "p/m" }, ttlMs = 30_000, clock = { now })
        catalog.models(); catalog.models()
        assertEquals(1, calls)
        now = 30_000
        catalog.models()
        assertEquals(2, calls)
    }

    @Test
    fun theRealBoundedRunnerFeedsTheCatalog() {
        val dir = EchoCliFixture.tempDir("s5-real")
        val src = java.io.File(dir, "L.java").apply { writeText("public class L { public static void main(String[] a) { System.out.println(\"p/m1\"); } }") }
        val rows = DescriptorModelCatalog("acme", DescriptorModels.Command(listOf(EchoCliFixture.javaExecutable(), src.path))).models()
        assertEquals(listOf("p/m1"), rows.single().models)
    }

    @Test
    fun theRegistryAsksTheLookupOnlyWhenNoBuiltInSourceAndNotForACoveredId() {
        val acme = DescriptorModelCatalog("acme", DescriptorModels.Static(listOf(DescriptorModel("p", "p/m"))))
        val asked = ArrayList<String>()
        val rows = HarnessModelCatalogs.EMPTY.rows(listOf("acme", "covered", "none"), setOf("covered")) { id ->
            asked += id
            if (id == "acme") acme else null
        }
        assertEquals(listOf("acme", "none"), asked)
        // PICKER-ALL-1: an uncovered id with no source gets ONE noted row (not zero rows); the covered id gets none.
        assertEquals(listOf("p/m"), rows.filter { it.provider == "acme" }.single().models)
        assertEquals(
            listOf(ProviderModels("none", "", "none", emptyList(), HarnessModelCatalogs.NO_CATALOG_NOTE)),
            rows.filter { it.provider == "none" },
        )
        assertTrue(rows.none { it.provider == "covered" }, "a covered id must not produce a row")
    }
}
