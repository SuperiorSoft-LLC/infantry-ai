// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// CARD-STALE-1: the stale-Running threshold (minutes) from the `limits.ui.staleRunningMinutes` setting; its default until loaded.
import { useEffect } from "react";
import { scopedBody } from "@infantry/client";
import { useDaemon } from "@/daemon/daemon";
import { scopedStore, useQuery } from "@/components/panels/use-panels";
import { STALE_RUNNING_DEFAULT, STALE_RUNNING_KEY } from "@/daemon/agents";

/** The setting's value as a number of minutes: a missing or unreadable value is the default, 0 is off. */
export function staleMinutesOf(raw: unknown): number {
  const n = Number(typeof raw === "string" ? raw.replace(/"/g, "") : raw);
  return raw === undefined || raw === null || raw === "" || !Number.isFinite(n) || n < 0 ? STALE_RUNNING_DEFAULT : n;
}

export function useStaleRunningMinutes(): number {
  const store = scopedStore();
  const link = useDaemon((s) => s.connection.state);
  const v = useQuery(store, (s) => s.data[STALE_RUNNING_KEY]);
  useEffect(() => {
    if (link === "open") store.getState().send("settings_get", scopedBody(STALE_RUNNING_KEY, {}));
  }, [link, store]);
  return staleMinutesOf(v?.value);
}
