package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.ServiceEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** SCRIPT-2: the generic name-keyed endpoint seam: publish, list by prefix, one per id, tier refusal, unload removes. */
class ServiceEndpointTest {
    private val echo = ServiceEndpoint { op, req -> "$op:$req" }

    @Test fun `publish then list by prefix and call`() {
        val r = ServiceRegistry()
        r.publishEndpoint("A", "x.point/one", echo)
        r.publishEndpoint("B", "x.point/two", echo)
        r.publishEndpoint("C", "y.point/one", echo)
        assertEquals(listOf("x.point/one", "x.point/two"), r.endpoints("x.point/").keys.toList())
        assertEquals("go:1", r.endpoints("x.point/")["x.point/one"]!!.call("go", "1"))
        assertTrue(r.endpoints("none/").isEmpty())
    }

    @Test fun `a second publisher of an id is refused and closing removes the endpoint`() {
        val r = ServiceRegistry()
        val h = r.publishEndpoint("A", "p/a", echo)
        assertFailsWith<IllegalStateException> { r.publishEndpoint("B", "p/a", echo) }
        h.close()
        assertTrue(r.endpoints("p/").isEmpty())
        assertFailsWith<IllegalArgumentException> { r.publishEndpoint("A", " ", echo) }
    }

    @Test fun `a community module needs the grant to publish, lookup is open`() {
        val r = ServiceRegistry()
        val closed = mutableListOf<AutoCloseable>()
        val denied = ScopedServices(r, "Acme", { false }) { closed += it }
        assertFailsWith<IllegalStateException> { denied.publishEndpoint("p/a", echo) }
        val ok = ScopedServices(r, "Infantry_X", { true }) { closed += it }
        ok.publishEndpoint("p/a", echo)
        assertEquals(1, denied.endpoints("p/").size)
        closed.forEach { it.close() }
        assertTrue(denied.endpoints("p/").isEmpty())
    }
}
