// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.harness.api.HarnessQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ASK-OTHER-1 (API 2.20.0): HarnessQuestion gains allowOther / multiSelect; the 2.19 constructor stays for compiled plugins. */
class HarnessQuestionCompatTest {
    @Test fun `new fields default to Other on and single-select`() {
        val q = HarnessQuestion("ok?", listOf("yes", "no"))
        assertTrue(q.allowOther)
        assertFalse(q.multiSelect)
        assertTrue(HarnessQuestion("ok?", listOf("a", "b"), multiSelect = true).multiSelect)
    }

    @Test fun `the 2_19 constructor and its defaults bridge still exist in the bytecode`() {
        val c = HarnessQuestion::class.java
        val old = c.getConstructor(String::class.java, List::class.java, String::class.java, java.lang.Long::class.java)
        val q = old.newInstance("ok?", listOf("yes"), "yes", 5L) as HarnessQuestion
        assertEquals("yes", q.recommended)
        assertEquals(5L, q.timeoutMillis)
        assertTrue(q.allowOther)
        assertFalse(q.multiSelect)
        // A 2.19 plugin that left recommended/timeout out called the synthetic defaults constructor (mask + marker).
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val bridge = c.declaredConstructors.firstOrNull {
            it.parameterTypes.toList() == listOf(String::class.java, List::class.java, String::class.java, java.lang.Long::class.java, Int::class.javaPrimitiveType, marker)
        }
        assertNotNull(bridge, "the 2.19 defaults constructor is kept")
        val d = bridge.newInstance("ok?", listOf("yes"), null, null, 0b1100, null) as HarnessQuestion
        assertEquals(null, d.recommended)
        assertTrue(d.allowOther)
    }
}
