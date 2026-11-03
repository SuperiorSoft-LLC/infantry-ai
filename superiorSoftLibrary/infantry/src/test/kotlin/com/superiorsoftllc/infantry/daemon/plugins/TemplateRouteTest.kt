package com.superiorsoftllc.infantry.daemon.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemplateRouteTest {
    private fun m(t: String, p: String) = DaemonPluginHost.matchTemplate(t.trim('/').split('/'), p.trim('/').split('/'))

    @Test fun `a template binds its path params`() = assertEquals(mapOf("id" to "abc"), m("/hooks/{id}", "/hooks/abc"))
    @Test fun `a different literal or length does not match`() {
        assertNull(m("/hooks/{id}", "/other/abc"))
        assertNull(m("/hooks/{id}", "/hooks/abc/x"))
        assertNull(m("/hooks/{id}", "/hooks/"))
    }
    @Test fun `an exact path has no params`() = assertEquals(emptyMap(), m("/a/b", "/a/b"))
}
