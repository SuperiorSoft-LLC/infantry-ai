package com.superiorsoftllc.agenthq.ui.model

/** PANEL-TOGGLE-1: the window's two side panels, the agent list on the left and the Changes/Review/... panel on the right. */
enum class PanelSide { LEFT, RIGHT }

/** MOBILE-LAYOUT-1: the one pane a compact (phone-width) window shows at a time. */
enum class CompactScreen { LIST, OUTPUT, RIGHT }

/**
 * PANEL-TOGGLE-1 (docs/AGENTHQ-MOBILE-UI-PLAN.md section 2.3): whether each docked side panel is on screen. Pure and
 * client-only; it lives in [AppState] and changes only through `Controller.togglePanel` / `revealPanel`, so a later
 * narrow-window shell can drive slide-out drawers from the same model. Both shown is the first-launch look.
 *
 * Memory only: there is no UI-prefs store, so a restart shows both panels again (like the panel widths).
 *
 * MOBILE-LAYOUT-2: [leftShown]/[rightShown] are the flags of the window class in use ([compactWindow]); the other
 * class's flags wait in [parked] until the window crosses back (see [forWindow]).
 */
data class PanelLayout(
    val leftShown: Boolean = true,
    val rightShown: Boolean = true,
    /** MOBILE-LAYOUT-2: whether the flags above belong to a compact window (below [COMPACT_BELOW_DP]). */
    val compactWindow: Boolean = false,
    /** MOBILE-LAYOUT-2: the other window class's flags, kept while this one is in use; null = never crossed. */
    val parked: Parked? = null,
) {
    /** MOBILE-LAYOUT-2: one window class's two side-panel flags, parked while the window is in the other class. */
    data class Parked(val leftShown: Boolean, val rightShown: Boolean)

    fun isShown(side: PanelSide): Boolean = if (side == PanelSide.LEFT) leftShown else rightShown

    /** The title-bar toggle: hides a shown panel, shows a hidden one; the other side is untouched. */
    fun toggle(side: PanelSide): PanelLayout =
        if (side == PanelSide.LEFT) copy(leftShown = !leftShown) else copy(rightShown = !rightShown)

    /**
     * The title-bar toggle in either window class. Wide: [toggle]. Compact: the panels are drawers over one screen, so a
     * toggle opens that side's screen, or returns to the output when it is already the one on screen.
     */
    fun toggleForWindow(side: PanelSide): PanelLayout {
        if (!compactWindow) return toggle(side)
        val target = if (side == PanelSide.LEFT) CompactScreen.LIST else CompactScreen.RIGHT
        return showCompact(if (compactScreen() == target) CompactScreen.OUTPUT else target)
    }

    /** Anything that targets a panel (a shortcut, the attention chip) shows it first; a shown panel stays as it is. */
    fun reveal(side: PanelSide): PanelLayout = if (isShown(side)) this else toggle(side)

    /**
     * MOBILE-LAYOUT-1: below [COMPACT_BELOW_DP] the window shows one pane, DERIVED from the same two flags (no parallel
     * state): the list while it is shown, else the right panel while it is shown, else the selected agent's output.
     * So the first launch (both shown) opens on the list, and the title-bar toggles still move between screens.
     */
    fun compactScreen(): CompactScreen = when {
        leftShown -> CompactScreen.LIST
        rightShown -> CompactScreen.RIGHT
        else -> CompactScreen.OUTPUT
    }

    /** MOBILE-LAYOUT-1: the flags that make [compactScreen] return [screen]. Going back to the list leaves the right flag alone. */
    fun showCompact(screen: CompactScreen): PanelLayout = when (screen) {
        CompactScreen.LIST -> copy(leftShown = true)
        CompactScreen.OUTPUT -> copy(leftShown = false, rightShown = false)
        CompactScreen.RIGHT -> copy(leftShown = false, rightShown = true)
    }

    /**
     * MOBILE-LAYOUT-2: the layout for a window that is [compact] (or not). Same class: unchanged. A crossing parks the
     * flags in use and brings back the ones parked at the last crossing, so moving between compact screens never
     * rewrites the wide layout, and a wide visit never loses the compact screen. The first crossing has nothing parked,
     * so the flags carry over and the screen shown on narrowing is the one [compactScreen] derives from them.
     */
    fun forWindow(compact: Boolean): PanelLayout =
        if (compact == compactWindow) this
        else PanelLayout(
            // MOBILE-DRAWERS-1: a compact window's panels are overlay drawers, so the first narrowing starts with both closed.
            leftShown = parked?.leftShown ?: (leftShown && !compact),
            rightShown = parked?.rightShown ?: (rightShown && !compact),
            compactWindow = compact,
            parked = Parked(leftShown, rightShown),
        )

    companion object {
        /**
         * MOBILE-DRAWERS-1: a window narrower than this (in dp) is compact: the chat is full width and the two panels are
         * drawers over it. A ~411dp phone and an unfolded foldable (~680dp) are; a desktop window is not.
         */
        const val COMPACT_BELOW_DP = 840f

        fun isCompact(widthDp: Float): Boolean = widthDp < COMPACT_BELOW_DP
    }
}
