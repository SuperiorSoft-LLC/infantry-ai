package com.superiorsoftllc.infantry.ui.shell

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** CONFIG-FIELDS-3 (G8): a denied node reads as unconfigured; an allowed one is answered by the inner config. */
class GatedConfigTest {
    private val inner = object : ConfigContext {
        override fun values(nodeId: String): ConfigValues = object : ConfigValues {
            override val keys = setOf("title")
            override fun string(key: String): String? = "from-$nodeId"
            override fun int(key: String): Int? = null
            override fun double(key: String): Double? = null
            override fun boolean(key: String): Boolean? = null
        }
        override fun <T : Any> implementation(type: KClass<T>, key: String): T? = null
    }

    @Test fun deniedNodeIsEmptyAndAllowedNodeIsAnswered() {
        val gated = GatedConfig(inner) { it.startsWith("acme.") }
        assertEquals("from-acme.a", gated.values("acme.a").string("title"))
        assertNull(gated.values("infantry.board").string("title"))
        assertEquals(emptySet(), gated.values("infantry.board").keys)
    }
}
