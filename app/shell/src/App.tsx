// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { UiProvider } from "@/state/ui-state";
import { TooltipProvider } from "@infantry/ui";
import { AppShell } from "@/components/layout/app-shell";

export default function App() {
  return (
    <UiProvider>
      <TooltipProvider delayDuration={400}>
        <AppShell />
      </TooltipProvider>
    </UiProvider>
  );
}
