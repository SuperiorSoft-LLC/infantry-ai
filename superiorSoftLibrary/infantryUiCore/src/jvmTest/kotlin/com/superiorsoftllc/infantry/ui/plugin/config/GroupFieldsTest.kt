package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldValidator
import com.superiorsoftllc.infantry.plugin.api.ConfigVisibleWhen
import com.superiorsoftllc.infantry.ui.shell.configAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Serializable
private data class Server(val host: String = "", val port: Int = 0)

@Serializable
private data class Cfg(val title: String = "", val primary: Server = Server(), val servers: List<Server> = emptyList())

/** CONFIG-FIELDS-4 (G3): `group` and `list` of objects: recursive validation, ConfigValues obj/list, configAs<T>(), old JSON unchanged. */
class GroupFieldsTest {
    private val host = ConfigFieldDeclaration("host", ConfigFieldTypes.TEXT, required = true, minLength = 2)
    private val port = ConfigFieldDeclaration("port", ConfigFieldTypes.NUMBER, min = 1.0, max = 65535.0)
    private val group = ConfigFieldDeclaration("primary", ConfigFieldTypes.GROUP, fields = listOf(host, port))
    private val servers = ConfigFieldDeclaration("servers", ConfigFieldTypes.LIST, fields = listOf(host, port))
    private fun obj(vararg kv: Pair<String, JsonElement>) = JsonObject(mapOf(*kv))
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test fun `a group validates its nested fields recursively and keeps unknown keys`() {
        val ok = obj("host" to JsonPrimitive("db"), "port" to JsonPrimitive(5432), "extra" to JsonPrimitive("kept"))
        assertEquals(ok, ConfigFieldValidator.validate(group, ok))
        assertFailsWith<IllegalArgumentException> { ConfigFieldValidator.validate(group, JsonPrimitive("x")) }
        assertFailsWith<IllegalArgumentException> { ConfigFieldValidator.validate(group, obj("host" to JsonPrimitive("db"), "port" to JsonPrimitive(0))) }
        assertFailsWith<IllegalArgumentException> { ConfigFieldValidator.validate(group, obj("port" to JsonPrimitive(80))) }
    }

    @Test fun `a hidden nested field is not validated`() {
        val g = ConfigFieldDeclaration("g", ConfigFieldTypes.GROUP, fields = listOf(
            ConfigFieldDeclaration("mode", ConfigFieldTypes.TEXT),
            ConfigFieldDeclaration("custom", ConfigFieldTypes.TEXT, required = true, visibleWhen = ConfigVisibleWhen("mode", "custom")),
        ))
        ConfigFieldValidator.validate(g, obj("mode" to JsonPrimitive("auto")))
        assertFailsWith<IllegalArgumentException> { ConfigFieldValidator.validate(g, obj("mode" to JsonPrimitive("custom"))) }
    }

    @Test fun `a list of objects validates every row, and a list of scalars is unchanged`() {
        ConfigFieldValidator.validate(servers, json("""[{"host":"a1","port":1},{"host":"b2"}]"""))
        assertFailsWith<IllegalArgumentException> { ConfigFieldValidator.validate(servers, json("""[{"host":"a1"},{"host":"b","port":2}]""")) }
        assertFailsWith<IllegalArgumentException> { ConfigFieldValidator.validate(servers, json("""["a1"]""")) }
        ConfigFieldValidator.validate(ConfigFieldDeclaration("tags", ConfigFieldTypes.LIST), json("""["x","y"]"""))
        val viaItem = ConfigFieldDeclaration("servers", ConfigFieldTypes.LIST, item = group)
        assertFailsWith<IllegalArgumentException> { ConfigFieldValidator.validate(viaItem, json("""[{"port":2}]""")) }
        assertEquals(listOf(host, port), viaItem.objectItemFields())
        assertEquals(listOf(host, port), servers.objectItemFields())
        assertNull(ConfigFieldDeclaration("tags", ConfigFieldTypes.LIST).objectItemFields())
    }

    @Test fun `built-in validation reports a bad row and an old declaration decodes with no fields`() {
        assertNotNull(FieldTypes.validateBuiltIn(servers, json("""[{"host":"a1","port":0}]""")))
        assertNull(FieldTypes.validateBuiltIn(servers, json("""[{"host":"a1","port":1}]""")))
        val plain = Json { ignoreUnknownKeys = true }.decodeFromString(ConfigFieldDeclaration.serializer(), """{"key":"k","type":"text","futureKey":1}""")
        assertEquals(emptyList(), plain.fields)
    }

    @Test fun `ConfigValues obj and list expose nested values and configAs decodes them`() {
        val values = JsonValues(mapOf(
            "title" to JsonPrimitive("T"),
            "primary" to json("""{"host":"db","port":5432}"""),
            "servers" to json("""[{"host":"a1","port":1},{"host":"b2","port":2}]"""),
            "bad" to json("""[{"host":"a"},1]"""),
        ))
        assertEquals("db", values.obj("primary")?.string("host"))
        assertEquals(5432, values.obj("primary")?.int("port"))
        assertEquals(listOf("a1", "b2"), values.list("servers")?.map { it.string("host") })
        assertNull(values.obj("title"))
        assertNull(values.list("bad"))
        assertNull(values.obj("missing"))
        assertEquals(Cfg("T", Server("db", 5432), listOf(Server("a1", 1), Server("b2", 2))), values.configAs<Cfg>())
    }
}
