// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class IgnoredToolArgsTest {
    private val schema = buildJsonObject { put("properties", buildJsonObject { put("title", buildJsonObject {}) }) }

    @Test fun undeclaredArgIsListed() =
        assertEquals(listOf("epicId"), ignoredToolArgs(schema, buildJsonObject { put("title", "t"); put("epicId", "e") }))

    @Test fun declaredArgsAreQuiet() =
        assertEquals(emptyList(), ignoredToolArgs(schema, buildJsonObject { put("title", "t") }))

    @Test fun schemaWithoutPropertiesIsQuiet() =
        assertEquals(emptyList(), ignoredToolArgs(buildJsonObject {}, buildJsonObject { put("x", 1) }))
}
