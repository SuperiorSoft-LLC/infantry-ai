package com.superiorsoftllc.agenthq.ui.plugin.config

import com.superiorsoftllc.agenthq.plugin.api.ConfigClassSlot
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CONFIG-9: the compile-time config (wasm, Android) and the `classes` / `list` field types. */
class CompiledConfigTest {
    private val text = """[
      {"module":"Infantry_A","config":[
        {"node":"n","group":"G","fields":[{"key":"keep","type":"text","default":"a"},{"key":"deskOnly","type":"text","platforms":["desktop"]},{"key":"clash","type":"text","default":"live"}]},
        {"node":"d","group":"D","platforms":["desktop"],"fields":[{"key":"x","type":"text"}]}]},
      {"module":"Demo_B","config":[{"node":"n","group":"G","fields":[{"key":"clash","type":"text","default":"demo"}]}]}]"""

    @Test fun `output is each module's config in order, the platform filter drops desktop-only groups and fields`() {
        val problems = mutableListOf<String>()
        val web = CompiledConfig.parse(text, "web", problems)
        assertTrue(problems.isEmpty())
        assertEquals(listOf("Infantry_A", "Demo_B"), web.keys.toList())
        assertEquals(listOf("n"), web.getValue("Infantry_A").map { it.node })
        assertEquals(listOf("keep"), web.getValue("Infantry_A").single().fields.map { it.key })
        assertEquals(listOf("keep", "deskOnly"), CompiledConfig.parse(text, "desktop", problems).getValue("Infantry_A").first().fields.map { it.key })
    }

    @Test fun `a later module overrides the earlier default of the same key`() {
        val web = CompiledConfig.parse(text, "web", mutableListOf())
        assertEquals("demo", web.getValue("Demo_B").single().fields.single().default.let { (it as JsonPrimitive).content })
    }

    @Test fun `bad text is one problem and no config`() {
        val problems = mutableListOf<String>()
        assertTrue(CompiledConfig.parse("nope", "web", problems).isEmpty())
        assertEquals(1, problems.size)
    }

    private val classes = ConfigFieldDeclaration(
        "classes", ConfigFieldTypes.CLASSES,
        slots = mapOf("card" to ConfigClassSlot(listOf("compact", "roomy", "outlined"), "roomy"), "free" to ConfigClassSlot()),
    )

    @Test fun `classes accept allow-listed tokens and refuse others`() {
        ConfigFieldValidator.validate(classes, JsonObject(mapOf("card" to JsonPrimitive("compact outlined"), "free" to JsonPrimitive("anything"))))
        assertFailsWith<IllegalArgumentException> { ConfigFieldValidator.validate(classes, JsonObject(mapOf("card" to JsonPrimitive("huge")))) }
        assertFailsWith<IllegalArgumentException> { ConfigFieldValidator.validate(classes, JsonObject(mapOf("nope" to JsonPrimitive("x")))) }
        assertEquals(null, FieldTypes.validateBuiltIn(classes, JsonObject(mapOf("card" to JsonPrimitive("roomy")))))
        assertTrue(FieldTypes.validateBuiltIn(classes, JsonObject(mapOf("card" to JsonPrimitive("huge")))) != null)
    }

    @Test fun `ConfigValues classes splits the tokens of a slot`() {
        val v = JsonValues(mapOf("classes" to JsonObject(mapOf("card" to JsonPrimitive("compact  outlined")))))
        assertEquals(listOf("compact", "outlined"), v.classes("card"))
        assertNull(v.classes("title"))
        assertNull(JsonValues(emptyMap()).classes("card"))
    }

    @Test fun `list checks every item against the item field`() {
        val list = ConfigFieldDeclaration("hosts", ConfigFieldTypes.LIST, item = ConfigFieldDeclaration("x", ConfigFieldTypes.NUMBER, min = 1.0))
        ConfigFieldValidator.validate(list, JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(5))))
        assertFailsWith<IllegalArgumentException> { ConfigFieldValidator.validate(list, JsonArray(listOf(JsonPrimitive(0)))) }
        assertFailsWith<IllegalArgumentException> { ConfigFieldValidator.validate(list, JsonPrimitive("x")) }
    }
}
