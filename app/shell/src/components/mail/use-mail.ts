// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one mail store and the one attention store of this window, bound to the daemon store's link.
import { useStore } from "zustand";
import {
  createAttentionStore,
  createMailStore,
  type AttentionActions,
  type AttentionState,
  type AttentionStore,
  type MailActions,
  type MailState,
  type MailStore,
} from "@infantry/client";
import { daemonStore } from "@/daemon/daemon";

let mail: MailStore | null = null;
let attention: AttentionStore | null = null;

export function mailStore(): MailStore {
  return (mail ??= createMailStore(daemonStore().getState()));
}

export function attentionStore(): AttentionStore {
  return (attention ??= createAttentionStore(daemonStore().getState()));
}

export function useMail<T>(select: (s: MailState & MailActions) => T): T {
  return useStore(mailStore(), select);
}

export function useAttention<T>(select: (s: AttentionState & AttentionActions) => T): T {
  return useStore(attentionStore(), select);
}
