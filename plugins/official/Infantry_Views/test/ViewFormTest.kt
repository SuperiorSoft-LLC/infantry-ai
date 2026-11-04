// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.views

import com.superiorsoftllc.infantry.plugin.api.ViewAccess
import com.superiorsoftllc.infantry.plugin.api.ViewInfo
import com.superiorsoftllc.infantry.plugin.api.ViewOrigin
import com.superiorsoftllc.infantry.plugin.api.ViewState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DV-5: ui.form parsing, read-only unsupported types, validation gate, merge rule, hidden fields, edits across re-fetch. */
class ViewFormTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private class FakeAccess : ViewAccess {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun list(): List<ViewInfo> = emptyList()
        override fun open(module: String, view: String, params: JsonObject): Flow<ViewState> = emptyFlow()
        override suspend fun act(origin: ViewOrigin, tool: String, args: JsonObject): Result<String> { calls += tool to args; return Result.success("ok") }
    }

    private val cfg = obj(
        """{"fields":[
          {"key":"name","type":"text","label":"Name","required":true,"minLength":3},
          {"key":"count","type":"number","min":1,"max":5},
          {"key":"mode","type":"select","options":["a","b"]},
          {"key":"extra","type":"text","visibleWhen":{"key":"mode","eq":"b"},"required":true},
          {"key":"tint","type":"color"}],
         "values":{"name":"abc","mode":"a","tint":"#fff"},
         "submit":{"label":"Save","action":{"tool":"t","args":{"name":"old","fixed":1}}}}""",
    )
    private val model = FormModel.parse(cfg)

    @Test fun `fields parse and an unsupported type is read-only`() {
        assertEquals(listOf("name", "count", "mode", "extra", "tint"), model.fields.map { it.key })
        assertTrue(model.fields[0].editable)
        assertFalse(model.fields[4].editable)
        assertEquals("Save", model.submit.label)
        assertFalse("tint" in model.problems(emptyMap()), "a read-only field is not validated")
        assertFalse("tint" in model.payload(emptyMap()), "a read-only field is not sent")
    }

    @Test fun `validation refusal blocks submit`() = runBlocking {
        val access = FakeAccess()
        val runner = ActionRunner(access, ViewOrigin("m", "v"), {}, {}, {})
        val problems = submitForm(model, mapOf("name" to JsonPrimitive("ab")), runner)
        assertEquals(setOf("name"), problems.keys)
        assertTrue(access.calls.isEmpty())
    }

    @Test fun `form value wins over a same-named arg and numbers are typed`() = runBlocking {
        val access = FakeAccess()
        val runner = ActionRunner(access, ViewOrigin("m", "v"), {}, {}, {})
        val edits = mapOf("name" to JsonPrimitive("new"), "count" to JsonPrimitive("3"))
        assertTrue(submitForm(model, edits, runner).isEmpty())
        val args = access.calls.single().second
        assertEquals("new", (args["name"] as JsonPrimitive).content)
        assertEquals("1", (args["fixed"] as JsonPrimitive).content)
        assertFalse((args["count"] as JsonPrimitive).isString)
    }

    @Test fun `hidden field is not sent and not validated`() {
        assertFalse("extra" in model.payload(emptyMap()))
        assertFalse("extra" in model.problems(emptyMap()))
        assertEquals("Required", model.problems(mapOf("mode" to JsonPrimitive("b")))["extra"])
    }

    @Test fun `local edits win over a re-fetched values object`() {
        val edits = FormEdits()
        edits.set("name", JsonPrimitive("typing"))
        val refetched = FormModel.parse(obj("""{"fields":[{"key":"name","type":"text"}],"values":{"name":"server"}}"""))
        assertEquals("typing", (refetched.current("name", edits.map) as JsonPrimitive).content)
        assertEquals("server", (refetched.current("name", emptyMap()) as JsonPrimitive).content)
    }
}
