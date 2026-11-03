package com.superiorsoftllc.infantry.plugin.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** GAP-AUDIT-1 (e): modules.json grants match by exact string or glob (`*` one path segment, `**` anything). */
class GrantPatternTest {
    @Test fun exactStringsStillMatchOnlyThemselves() {
        assertTrue(GrantPattern.matches("infantry.shell.body", "infantry.shell.body"))
        assertFalse(GrantPattern.matches("infantry.shell.body", "infantry.shell.bodyX"))
        assertFalse(GrantPattern.matches(emptyList(), "infantry.shell.body"))
    }

    @Test fun aStarIsAPrefixWithinOneSegment() {
        assertTrue(GrantPattern.matches("infantry.board.*", "infantry.board.ticket.row"))
        assertTrue(GrantPattern.matches("infantry.board.*", "infantry.board."))
        assertFalse(GrantPattern.matches("infantry.board.*", "infantry.boardX"))
        assertFalse(GrantPattern.matches("infantry.shell.frame/*", "infantry.shell.frame/infantry.shell.body/x"), "* stops at /")
        assertTrue(GrantPattern.matches("infantry.shell.frame/*", "infantry.shell.frame/infantry.shell.body"))
        assertTrue(GrantPattern.matches("*.footer", "infantry.shell.footer"))
    }

    @Test fun aDoubleStarCrossesSegments() {
        assertTrue(GrantPattern.matches("infantry.shell.frame/**", "infantry.shell.frame/infantry.shell.body/[1]"))
        assertTrue(GrantPattern.matches("**", "anything/at/all"))
        assertFalse(GrantPattern.matches("infantry.shell.frame/**", "infantry.shell.panes/x"))
    }

    @Test fun anyEntryOfTheListMayMatch() {
        assertTrue(GrantPattern.matches(listOf("a.b", "infantry.git.*"), "infantry.git.log"))
    }
}
