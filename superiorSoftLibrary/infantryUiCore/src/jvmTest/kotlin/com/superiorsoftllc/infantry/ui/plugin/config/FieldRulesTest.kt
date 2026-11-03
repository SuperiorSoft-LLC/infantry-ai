// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldValidator
import com.superiorsoftllc.infantry.plugin.api.ConfigVisibleWhen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import com.superiorsoftllc.infantry.ui.shell.configAs
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** CONFIG-FIELDS-2: required / pattern / minLength / maxLength / visibleWhen on a field declaration. */
@Serializable
private data class Greeting(val text: String = "Hi", val size: Int = 1, val loud: Boolean = false)

class FieldRulesTest {
    @Test fun `configAs decodes the resolved map into a bean, ignores unknown keys, keeps defaults, null on a wrong type`() {
        fun values(vararg kv: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonValues(mapOf(*kv))
        assertEquals(Greeting("Yo", 3, false), values("text" to JsonPrimitive("Yo"), "size" to JsonPrimitive(3), "extra" to JsonPrimitive("x")).configAs<Greeting>())
        assertEquals(Greeting(), values().configAs<Greeting>())
        assertNull(values("size" to JsonPrimitive("big")).configAs<Greeting>())
    }

    private val code = ConfigFieldDeclaration("code", ConfigFieldTypes.TEXT, required = true, pattern = "[A-Z]{3}", patternMessage = "Three capitals", minLength = 3, maxLength = 3)

    @Test fun `built-in validation enforces required, length and pattern with the declared message`() {
        assertEquals("Required", FieldTypes.validateBuiltIn(code, JsonPrimitive("")))
        assertEquals("Required", FieldTypes.validateBuiltIn(code, null))
        assertEquals("At least 3 characters", FieldTypes.validateBuiltIn(code, JsonPrimitive("AB")))
        assertEquals("At most 3 characters", FieldTypes.validateBuiltIn(code, JsonPrimitive("ABCD")))
        assertEquals("Three capitals", FieldTypes.validateBuiltIn(code, JsonPrimitive("abc")))
        assertNull(FieldTypes.validateBuiltIn(code, JsonPrimitive("ABC")))
    }

    @Test fun `an optional field accepts empty and the daemon-side validator throws for a broken rule`() {
        val optional = code.copy(required = false)
        assertNull(FieldTypes.validateBuiltIn(optional, JsonPrimitive("")))
        assertNull(FieldTypes.validateBuiltIn(optional, JsonNull))
        assertFailsWith<IllegalArgumentException> { ConfigFieldValidator.validate(code, JsonPrimitive("abc")) }
        assertEquals(JsonPrimitive("ABC"), ConfigFieldValidator.validate(code, JsonPrimitive("ABC")))
    }

    @Test fun `old manifests and unknown keys still decode with the rules off`() {
        val f = Json { ignoreUnknownKeys = true }.decodeFromString(ConfigFieldDeclaration.serializer(), """{"key":"k","type":"text","future":1}""")
        assertEquals(false, f.required)
        assertNull(FieldTypes.validateBuiltIn(f, JsonPrimitive("anything")))
    }

    @Test fun `visibleWhen shows a field only while the sibling equals, and no visibleWhen is always visible`() {
        val f = ConfigFieldDeclaration("custom", ConfigFieldTypes.TEXT, visibleWhen = ConfigVisibleWhen("mode", "custom"))
        assertEquals(true, ConfigFieldValidator.isVisible(f) { JsonPrimitive("custom") })
        assertEquals(false, ConfigFieldValidator.isVisible(f) { JsonPrimitive("auto") })
        assertEquals(false, ConfigFieldValidator.isVisible(f) { null })
        assertEquals(true, ConfigFieldValidator.isVisible(f.copy(visibleWhen = null)) { null })
        assertEquals(true, ConfigFieldValidator.isVisible(f.copy(visibleWhen = ConfigVisibleWhen("on", "true"))) { JsonPrimitive(true) })
    }
}
