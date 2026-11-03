// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.client

import com.superiorsoftllc.infantry.protocol.ProviderModels
import com.superiorsoftllc.infantry.protocol.SpawnOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PICKER 2/2: `/model <id>` resolves the provider from the `models` frame and reuses the `new` frame path. */
class ModelCommandTest {
    private val catalog = listOf(
        ProviderModels("alpha", "g1", "alpha / g1", listOf("g1/m1", "shared")),
        ProviderModels("alpha", "g2", "alpha / g2", listOf("g2/m2")),
        ProviderModels("beta", "", "beta", listOf("b-model", "shared")),
        ProviderModels("empty", "", "empty", emptyList(), note = "nothing"),
    )

    @Test
    fun `a unique id switches to the provider that lists it`() {
        assertEquals(ModelPick.Switch(SpawnOptions(provider = "alpha", model = "g2/m2")), resolveModelPick("g2/m2", catalog))
        assertEquals(ModelPick.Switch(SpawnOptions(provider = "beta", model = "b-model")), resolveModelPick("b-model", catalog))
    }

    @Test
    fun `an id two providers list is refused, naming both`() {
        val r = resolveModelPick("shared", catalog) as ModelPick.Refused
        assertTrue("alpha" in r.reason && "beta" in r.reason, r.reason)
    }

    @Test
    fun `an unknown id and an empty catalog are refused`() {
        assertTrue(resolveModelPick("nope", catalog) is ModelPick.Refused)
        assertTrue((resolveModelPick("b-model", emptyList()) as ModelPick.Refused).reason.contains("/models"))
    }
}
