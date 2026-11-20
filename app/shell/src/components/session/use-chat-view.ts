// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { useSyncExternalStore } from "react";
import { chatViewOf, subscribeChatView, type ChatViewState } from "@infantry/client";

/** The composer view state (minimized / expanded / draft) of one chat window; see `@infantry/client` chat-view. */
export function useChatView(win: string): ChatViewState {
  return useSyncExternalStore(subscribeChatView, () => chatViewOf(win));
}
