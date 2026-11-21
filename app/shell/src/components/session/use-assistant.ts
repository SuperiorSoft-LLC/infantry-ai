// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Assistant box's two reads: which session is the installation-wide Assistant, and why the daemon refused to make it. The
// ask store is the one of this window, bound to the daemon store's link (the same lazy singleton as the roles or scripts store).
import { useStore } from "zustand";
import { assistantOf, createAssistantAskStore, type AssistantAskStore } from "@infantry/client";
import { daemonStore, useDaemon } from "@/daemon/daemon";

let asker: AssistantAskStore | null = null;

export function assistantAskStore(): AssistantAskStore {
  return (asker ??= createAssistantAskStore(daemonStore().getState()));
}

/** The installation-wide Assistant session (`record.global`), or null until the daemon has made it. */
export function useAssistantId(): string | null {
  return useDaemon((s) => assistantOf(Object.values(s.sessions))?.record.id ?? null);
}

/** The daemon's reason for refusing the last ask for the Assistant (null: not refused), and the ask itself. */
export function useAssistantAsk(): { refusal: string | null; ask: () => void } {
  const refusal = useStore(assistantAskStore(), (s) => s.refusal);
  return { refusal, ask: assistantAskStore().getState().ask };
}
