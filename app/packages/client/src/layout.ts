// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The responsive layout rules (RUST-MOBILE-1): one shell, three widths. Breakpoints, not a separate phone app.
//   phone   (<= 640px)  both side panels are drawers; a pick in the left drawer closes it; board lanes are one screen
//                       wide and snap, so a swipe moves one lane; tap targets grow (the coarse-pointer CSS does that too).
//   tablet  (<= 900px)  the left panel is a drawer, the right one too (<= 1280px); lanes keep their minimum width.
//   desktop             both panels inline when open.

export const BREAKPOINTS = { phone: 640, leftInline: 900, rightInline: 1280 } as const;

export interface Layout {
  kind: "phone" | "tablet" | "desktop";
  /** The agents panel sits beside the session view (else it opens as a drawer over it). */
  leftInline: boolean;
  /** The side panel (attention, usage, ...) sits beside the session view. */
  rightInline: boolean;
  /** Board lanes take the whole width and snap, one lane per swipe. */
  laneSnap: boolean;
  /** Picking a session in the left drawer closes the drawer. */
  closeDrawerOnPick: boolean;
  /** Minimum tap target, px (WCAG 2.5.5 asks 44 on touch). */
  tapTarget: number;
}

export function layoutFor(width: number, coarsePointer = false): Layout {
  const phone = width <= BREAKPOINTS.phone;
  const leftInline = width > BREAKPOINTS.leftInline;
  const rightInline = width > BREAKPOINTS.rightInline;
  return {
    kind: phone ? "phone" : leftInline && rightInline ? "desktop" : "tablet",
    leftInline,
    rightInline,
    laneSnap: phone,
    closeDrawerOnPick: !leftInline,
    tapTarget: phone || coarsePointer ? 44 : 28,
  };
}

/**
 * Which panels to close when the layout changes from [prev] to [next]: crossing into a width where a panel turns into a
 * drawer collapses it (the title-bar toggle reopens it as a drawer). Widening never opens a panel by itself.
 */
export function panelsToClose(prev: Layout | null, next: Layout): { left: boolean; right: boolean } {
  return { left: !next.leftInline && (prev === null || prev.leftInline), right: !next.rightInline && (prev === null || prev.rightInline) };
}
