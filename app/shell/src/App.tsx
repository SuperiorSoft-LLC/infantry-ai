// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { UiProvider } from "@/state/ui-state";
import { TooltipProvider } from "@infantry/ui";
import { AppShell } from "@/components/layout/app-shell";
import { BootstrapScreen, useBootPending, useBootstrap } from "@/plugins/bootstrap";

export default function App() {
  // INSTALLER-1: while the daemon says nothing but its bootstrap plugin is installed, that plugin is the window.
  const bootstrap = useBootstrap();
  const pending = useBootPending(bootstrap);
  return (
    <UiProvider>
      <TooltipProvider delayDuration={400}>{pending ? null : bootstrap?.active ? <BootstrapScreen info={bootstrap} /> : <AppShell />}</TooltipProvider>
    </UiProvider>
  );
}
