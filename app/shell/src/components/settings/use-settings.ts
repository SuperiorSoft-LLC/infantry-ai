// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one settings store of this window, bound to the daemon store's link.
import { useStore } from "zustand";
import { createSettingsStore, type SettingsActions, type SettingsState, type SettingsStore } from "@infantry/client";
import { daemonStore } from "@/daemon/daemon";

let store: SettingsStore | null = null;

export function settingsStore(): SettingsStore {
  return (store ??= createSettingsStore(daemonStore().getState()));
}

export function useSettings<T>(select: (s: SettingsState & SettingsActions) => T): T {
  return useStore(settingsStore(), select);
}
