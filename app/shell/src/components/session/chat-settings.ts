// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #13: the chat's own settings (Infantry_Chat module.json `daemon.config`, node `infantry.chat`). Each
// read falls back to the declared default until the settings load, so nothing changes until a user sets a value.
import { useEffect } from "react";
import { settingKey } from "@infantry/client";
import { daemonStore } from "@/daemon/daemon";
import { settingsStore, useSettings } from "@/components/settings/use-settings";

export const CHAT_NODE = "infantry.chat";

export type ReasoningMode = "inline" | "panel" | "hidden";

/** One chat setting's value in effect, or `fallback` (its declared default) while unset or of the wrong type. */
export function useChatSetting<T extends boolean | number | string>(key: string, fallback: T): T {
  const v = useSettings((s) => s.values[settingKey(CHAT_NODE, key)]);
  useEffect(() => settingsStore().getState().ensureLoaded(), []);
  return typeof v === typeof fallback ? (v as T) : fallback;
}

export function useReasoningMode(): ReasoningMode {
  const m = useChatSetting<string>("chat.reasoning", "inline");
  return m === "panel" || m === "hidden" ? m : "inline";
}

/** Keeps the client's history page (`limits.chat.replayTurns`) in step with the setting. */
export function useReplayTurnsSetting(): void {
  const turns = useChatSetting("limits.chat.replayTurns", 50);
  useEffect(() => daemonStore().getState().setReplayTurns(turns), [turns]);
}
