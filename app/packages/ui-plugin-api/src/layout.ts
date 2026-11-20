// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// WEBUI-5: the layout seam. Plugins contribute components (a tab, a panel); they never say where it goes. A LAYOUT decides
// placement: a document of regions, each holding component ids with optional sizes. The shell asks a [LayoutProvider]
// "what goes in region X?" and draws the answer; the default provider reproduces the app as it always was, and a layout
// plugin can supply another (LAYOUT-1). No side effects and no imports: the shell, a layout plugin and tests all import it.

/** A component placed in a region: its id, and how big it is there when the region cares (a share, a pixel size...). */
export interface LayoutSlot {
  /** The component's id: a declared tab/panel id (`infantry.git`) or a shell view's own id (`mail`). */
  id: string;
  /** A size hint the region may honour (a pixel width, a flex share). Absent = the region's own default. */
  size?: number;
}

/** One region of the screen and what it holds, in order. */
export interface LayoutRegion {
  /** `center` and `right` are the regions the shell draws today; a layout may name more. */
  id: string;
  components: LayoutSlot[];
}

/** A whole layout: a name and its regions. Plain data, so a layout can be saved, shared and swapped. */
export interface LayoutDocument {
  id: string;
  name: string;
  regions: LayoutRegion[];
}

/**
 * A layout that owns the whole screen: a component the shell renders INSTEAD of its own window. It gets today's shell as
 * `Shell` (the "orchestrator app") and may draw it, wrap it, shrink it, or not draw it at all. Typed loosely so this file
 * stays free of React: in the shell, `Shell` is a component and the return value a React node.
 */
export type LayoutRoot<Shell = unknown, Node = unknown> = (props: { Shell: Shell }) => Node;

/** What the shell asks. A layout plugin implements this; the shell never reads a layout document directly. */
export interface LayoutProvider {
  /** The whole document (for an editor or a debug view). */
  layout(): LayoutDocument;
  /** The components of region [regionId], in order. An unknown region holds nothing. */
  componentsIn(regionId: string): readonly LayoutSlot[];
  /** Where [componentId] sits in region [regionId]: its index, or -1 when the layout does not place it there. */
  positionOf(regionId: string, componentId: string): number;
  /** Absent = the shell's own window with this layout's tab order. Present = this layout owns the whole screen (see [LayoutRoot]). */
  readonly root?: LayoutRoot;
}

/** A provider over a plain document. The default provider and any layout plugin can use it. */
export function createLayoutProvider(doc: LayoutDocument, options: { root?: LayoutRoot } = {}): LayoutProvider {
  const byRegion = new Map(doc.regions.map((r) => [r.id, r.components] as const));
  return {
    layout: () => doc,
    componentsIn: (regionId) => byRegion.get(regionId) ?? [],
    positionOf: (regionId, componentId) => (byRegion.get(regionId) ?? []).findIndex((c) => c.id === componentId),
    ...(options.root ? { root: options.root } : {}),
  };
}

/** A reactive holder for the active layout: `set` swaps it and tells every subscriber (the shell re-renders, no reload). */
export interface LayoutStore {
  get(): LayoutProvider;
  set(next: LayoutProvider): void;
  /** Calls [listener] after every swap; returns the unsubscribe. Shaped for React's `useSyncExternalStore`. */
  subscribe(listener: () => void): () => void;
}

export function createLayoutStore(initial: LayoutProvider): LayoutStore {
  let current = initial;
  const listeners = new Set<() => void>();
  return {
    get: () => current,
    set(next) {
      if (next === current) return;
      current = next;
      for (const l of [...listeners]) l();
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => void listeners.delete(listener);
    },
  };
}

/**
 * Orders [items] by the layout: those the region places come first, in the region's order; the rest follow in their given
 * order (a stable sort, so a component the layout has never heard of lands last rather than disappearing).
 */
export function arrangeBy<T>(provider: LayoutProvider, regionId: string, items: readonly T[], idOf: (item: T) => string): T[] {
  const at = (item: T): number => {
    const i = provider.positionOf(regionId, idOf(item));
    return i < 0 ? Number.MAX_SAFE_INTEGER : i;
  };
  return items
    .map((item, index) => ({ item, index, at: at(item) }))
    .sort((a, b) => a.at - b.at || a.index - b.index)
    .map((x) => x.item);
}
