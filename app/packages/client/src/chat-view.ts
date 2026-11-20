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
