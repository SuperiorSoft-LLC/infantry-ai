// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Pure maths behind the scroll strip (arrows on an overflowing tab or chip row).

export interface StripEdges {
  overflow: boolean;
  atStart: boolean;
  atEnd: boolean;
}

/** Which arrows a row needs: none when it fits, the left one unless at the start, the right one unless at the end. */
export function stripEdges(scrollLeft: number, clientWidth: number, scrollWidth: number): StripEdges {
  const overflow = scrollWidth - clientWidth > 1;
  return {
    overflow,
    atStart: !overflow || scrollLeft <= 1,
    atEnd: !overflow || scrollLeft + clientWidth >= scrollWidth - 1,
  };
}

/** The scrollLeft that brings an item fully into view with a small margin; the current one when already visible. */
export function scrollToReveal(
  scrollLeft: number,
  clientWidth: number,
  itemLeft: number,
  itemWidth: number,
  margin = 8,
): number {
  if (itemLeft - margin < scrollLeft) return Math.max(0, itemLeft - margin);
  if (itemLeft + itemWidth + margin > scrollLeft + clientWidth) return itemLeft + itemWidth + margin - clientWidth;
  return scrollLeft;
}

/** A vertical wheel turn becomes a sideways scroll; a sideways one is left to the browser. */
export function wheelDelta(deltaX: number, deltaY: number): number {
  return Math.abs(deltaX) >= Math.abs(deltaY) ? 0 : deltaY;
}
