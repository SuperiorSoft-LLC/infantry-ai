// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Chat composer view state (Kotlin ChatPanelState, CHAT-MINIMIZE-1): per window, whether the composer is minimized
// (removed from view, one Restore floater remains) and expanded (a tall editor), plus the draft text so a minimize and
// restore, or switching agents, never loses it. A window is "main" or a popout id; the state lives for the app run.
export interface ChatViewState {
  minimized: boolean;
  expanded: boolean;
  draft: string;
}

const FRESH: ChatViewState = { minimized: false, expanded: false, draft: "" };
const windows = new Map<string, ChatViewState>();
const listeners = new Set<() => void>();

export const chatViewOf = (win: string): ChatViewState => windows.get(win) ?? FRESH;

export function patchChatView(win: string, patch: Partial<ChatViewState>): void {
  windows.set(win, { ...chatViewOf(win), ...patch });
  listeners.forEach((l) => l());
}

/** Minimize hides the composer; restoring shows it again (also un-expands nothing: the editor stays as it was). */
export const toggleMinimized = (win: string) => patchChatView(win, { minimized: !chatViewOf(win).minimized });
/** Expand grows the input to a tall editor; again returns to normal. */
export const toggleExpanded = (win: string) => patchChatView(win, { expanded: !chatViewOf(win).expanded });

export function subscribeChatView(l: () => void): () => void {
  listeners.add(l);
  return () => void listeners.delete(l);
}

/** Test hook: forget every window. */
export function resetChatViews(): void {
  windows.clear();
  listeners.forEach((l) => l());
}

// Composer layout rules (Kotlin ChatSurface CHAT-MINIMIZE-2/5): pixel sizes are the Kotlin dp values.
/** Every composer button's touch target. */
export const COMPOSER_TOUCH_PX = 48;
/** The hanging groups' inset from the box's sides (clear of its 16px corner plus the join fillet). */
export const COMPOSER_GROUP_INSET_PX = 26;
/** The gap between the two groups. */
export const COMPOSER_GROUP_GAP_PX = 24;

/** How many tool buttons fit in the tools group of a composer [width] wide, beside [sendCount] send buttons and [extras] px. */
export function composerToolsThatFit(width: number, sendCount: number, extras = 0): number {
  const free = width - 2 * COMPOSER_GROUP_INSET_PX - COMPOSER_GROUP_GAP_PX - sendCount * COMPOSER_TOUCH_PX - extras;
  return Math.max(0, Math.floor(free / COMPOSER_TOUCH_PX));
}

/** [count] tools in [fit] slots: all inline, or the first `fit - 1` inline and the rest behind "More actions" (the last slot). */
export function splitComposerTools(count: number, fit: number): { inline: number; overflow: number } {
  if (count <= fit) return { inline: count, overflow: 0 };
  const inline = Math.max(0, fit - 1);
  return { inline, overflow: count - inline };
}

/** The one status line above the box: permission mode (only once there is an agent), agent count, session state. */
export function composerStatusLine(mode: string | null | undefined, agents: number, state: string | null | undefined): string {
  return [mode && agents > 0 ? `permissions: ${mode}` : null, `${agents} agent${agents === 1 ? "" : "s"}`, state || null].filter(Boolean).join(" · ");
}

/** [text] with [insert] put at [at] (clamped), and the caret after it. */
export function insertAt(text: string, at: number, insert: string): { text: string; caret: number } {
  const i = Math.max(0, Math.min(at, text.length));
  return { text: text.slice(0, i) + insert + text.slice(i), caret: i + insert.length };
}
