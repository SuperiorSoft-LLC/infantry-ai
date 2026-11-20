// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// DESK-1: the shell's layout host. It subscribes to the layout store, so swapping the layout re-renders live. A layout with a
// `root` owns the whole screen: the host draws that root instead of the shell, handing it today's shell as `Shell` (the
// "orchestrator app"). A layout without one gets the shell as it always was.
import type { ComponentType, ReactNode } from "react";
import type { LayoutRoot } from "@infantry/ui-plugin-api/layout";
import { AppShell } from "@/components/layout/app-shell";
import { useLayoutProvider } from "./default-layout";

/** The window: the active layout's own root when it owns the screen, else the shell. */
export function LayoutHost({ Shell = AppShell }: { Shell?: ComponentType } = {}) {
  const Root = useLayoutProvider().root as LayoutRoot<ComponentType, ReactNode> | undefined;
  return Root ? <>{Root({ Shell })}</> : <Shell />;
}
