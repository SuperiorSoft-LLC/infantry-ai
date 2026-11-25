// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { useSyncExternalStore } from "react";
import { chatViewOf, draftOf, subscribeChatView, type ChatViewState } from "@infantry/client";

/** The composer view state (minimized / expanded) of one chat window; see `@infantry/client` chat-view. */
export function useChatView(win: string): ChatViewState {
  return useSyncExternalStore(subscribeChatView, () => chatViewOf(win));
}

/** CHAT-DRAFT-1: the unsent text of one chat (`chatKey`) in one window. */
export function useChatDraft(win: string, chat: string): string {
  return useSyncExternalStore(subscribeChatView, () => draftOf(win, chat));
}
