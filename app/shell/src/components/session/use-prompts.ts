// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one owner-prompt store of this window (tool approvals and questions a session waits on), bound to the daemon
// store's link.
import { useStore } from "zustand";
import { createPromptsStore, type PromptsActions, type PromptsState, type PromptsStore } from "@infantry/client";
import { daemonStore } from "@/daemon/daemon";

let store: PromptsStore | null = null;

export function promptsStore(): PromptsStore {
  return (store ??= createPromptsStore(daemonStore().getState()));
}

export function usePrompts<T>(select: (s: PromptsState & PromptsActions) => T): T {
  return useStore(promptsStore(), select);
}
