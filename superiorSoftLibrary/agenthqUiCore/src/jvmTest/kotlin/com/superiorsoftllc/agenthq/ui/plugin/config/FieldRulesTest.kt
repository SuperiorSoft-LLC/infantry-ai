package com.superiorsoftllc.agenthq.ui.plugin.config

import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** CONFIG-FIELDS-2: required / pattern / minLength / maxLength / visibleWhen on a field declaration. */
class FieldRulesTest {
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
}
