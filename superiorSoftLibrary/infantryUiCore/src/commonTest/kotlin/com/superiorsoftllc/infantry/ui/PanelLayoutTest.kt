package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.CompactScreen
import com.superiorsoftllc.infantry.ui.model.PanelLayout
import com.superiorsoftllc.infantry.ui.model.PanelSide
import com.superiorsoftllc.infantry.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** PANEL-TOGGLE-1: the docked side-panel model (docs/INFANTRY-MOBILE-UI-PLAN.md section 2.3) and its Controller entry points. */
class PanelLayoutTest {
    @Test
    fun `compact toggles open their screen and a second tap returns to the output`() {
        val compact = PanelLayout().forWindow(true)
        val right = compact.toggleForWindow(PanelSide.RIGHT)
        assertEquals(CompactScreen.RIGHT, right.compactScreen(), "from the list, the right toggle shows the right panel")
        assertEquals(CompactScreen.OUTPUT, right.toggleForWindow(PanelSide.RIGHT).compactScreen())
        assertEquals(CompactScreen.LIST, right.toggleForWindow(PanelSide.LEFT).compactScreen())
        assertEquals(PanelLayout().toggle(PanelSide.RIGHT), PanelLayout().toggleForWindow(PanelSide.RIGHT), "wide windows keep the plain toggle")
    }

    @Test fun bothPanelsAreShownOnFirstLaunch() {
        val p = AppState().panels
        assertTrue(p.isShown(PanelSide.LEFT) && p.isShown(PanelSide.RIGHT))
    }

    @Test fun toggleFlipsOnlyItsOwnSide() {
        assertEquals(PanelLayout(leftShown = false, rightShown = true), PanelLayout().toggle(PanelSide.LEFT))
        assertEquals(PanelLayout(leftShown = true, rightShown = false), PanelLayout().toggle(PanelSide.RIGHT))
        assertEquals(PanelLayout(), PanelLayout().toggle(PanelSide.LEFT).toggle(PanelSide.LEFT))
    }

    @Test fun revealShowsAHiddenSideAndLeavesAShownOneAlone() {
        val hidden = PanelLayout(leftShown = false, rightShown = false)
        assertEquals(PanelLayout(leftShown = true, rightShown = false), hidden.reveal(PanelSide.LEFT))
        assertEquals(PanelLayout(leftShown = false, rightShown = true), hidden.reveal(PanelSide.RIGHT))
        val shown = PanelLayout()
        assertSame(shown, shown.reveal(PanelSide.LEFT))
        assertSame(shown, shown.reveal(PanelSide.RIGHT))
    }

    @Test fun controllerTogglesAndRevealsThroughAppState() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = Controller(scope)
            c.togglePanel(PanelSide.RIGHT)
            assertEquals(PanelLayout(leftShown = true, rightShown = false), c.state.value.panels)
            c.revealPanel(PanelSide.RIGHT)
            assertEquals(PanelLayout(), c.state.value.panels)
            c.revealPanel(PanelSide.RIGHT)
            assertEquals(PanelLayout(), c.state.value.panels, "reveal on a shown panel never hides it")
            c.togglePanel(PanelSide.LEFT)
            assertEquals(PanelLayout(leftShown = false, rightShown = true), c.state.value.panels)
        } finally {
            scope.cancel()
        }
    }

    /** MOBILE-LAYOUT-1: the compact screen is derived from the two flags; 600dp is the first non-compact width. */
    @Test fun compactScreenIsDerivedFromThePanelFlags() {
        assertEquals(CompactScreen.LIST, PanelLayout().compactScreen(), "first launch opens on the list")
        assertEquals(CompactScreen.RIGHT, PanelLayout(leftShown = false, rightShown = true).compactScreen())
        assertEquals(CompactScreen.OUTPUT, PanelLayout(leftShown = false, rightShown = false).compactScreen())
        for (screen in CompactScreen.entries) {
            for (from in listOf(PanelLayout(), PanelLayout(false, false), PanelLayout(false, true), PanelLayout(true, false))) {
                assertEquals(screen, from.showCompact(screen).compactScreen(), "showCompact($screen) from $from")
            }
        }
        assertTrue(PanelLayout.isCompact(400f) && PanelLayout.isCompact(680f) && PanelLayout.isCompact(839.9f))
        assertTrue(!PanelLayout.isCompact(840f) && !PanelLayout.isCompact(1400f))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = Controller(scope)
            c.showCompactScreen(CompactScreen.OUTPUT)
            assertEquals(CompactScreen.OUTPUT, c.state.value.panels.compactScreen())
            c.showCompactScreen(CompactScreen.LIST)
            assertEquals(CompactScreen.LIST, c.state.value.panels.compactScreen())
        } finally {
            scope.cancel()
        }
    }

    /**
     * MOBILE-LAYOUT-2: moving between compact screens (a phone in portrait) never rewrites the wide layout, so when the
     * window widens again (rotation, unfolding, a resize) every side panel is back exactly as it was before it narrowed.
     * Each wide layout goes through the same compact walk: open an agent, open the side panel, back to the output, back
     * to the list, open an agent again. Narrowing once more brings back the compact screen the walk ended on.
     */
    @Test fun theWideLayoutSurvivesAWideCompactWideRoundTrip() {
        val wideLayouts = listOf(true to true, false to true, true to false, false to false)
        for ((left, right) in wideLayouts) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val c = Controller(scope)
                if (!left) c.togglePanel(PanelSide.LEFT)
                if (!right) c.togglePanel(PanelSide.RIGHT)
                c.setCompactWindow(false)
                assertEquals(PanelLayout(leftShown = left, rightShown = right), c.state.value.panels, "a wide window staying wide changes nothing")
                c.setCompactWindow(true)
                val walk = listOf(CompactScreen.OUTPUT, CompactScreen.RIGHT, CompactScreen.OUTPUT, CompactScreen.LIST, CompactScreen.OUTPUT)
                for (screen in walk) {
                    c.showCompactScreen(screen)
                    assertEquals(screen, c.state.value.panels.compactScreen(), "the compact walk reaches $screen")
                }
                c.setCompactWindow(false)
                c.setCompactWindow(false)
                val p = c.state.value.panels
                assertEquals(
                    "left=$left right=$right", "left=${p.leftShown} right=${p.rightShown}",
                    "the wide layout after the compact walk $walk",
                )
                c.setCompactWindow(true)
                assertEquals(walk.last(), c.state.value.panels.compactScreen(), "narrowing again shows the screen the walk ended on")
            } finally {
                scope.cancel()
            }
        }
    }

    /** MOBILE-DRAWERS-1: the first narrowing closes both drawers; widening restores the docked flags. */
    @Test fun firstNarrowingStartsWithBothDrawersClosed() {
        val narrow = PanelLayout().forWindow(true)
        assertEquals(CompactScreen.OUTPUT, narrow.compactScreen())
        assertTrue(!narrow.leftShown && !narrow.rightShown)
        val wide = narrow.forWindow(false)
        assertTrue(wide.leftShown && wide.rightShown)
    }
}
