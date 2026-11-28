// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { UiProvider } from "@/state/ui-state";
import { TooltipProvider } from "@infantry/ui";
import { LayoutHost } from "@/plugins/layout-host";
import { PopoutView } from "@/components/session/popout-view";
import { popoutView } from "@/components/session/use-native-popout";
import { BootstrapScreen, useBootPending, useBootstrap } from "@/plugins/bootstrap";

export default function App() {
  // POPOUT-1: a native pop-out window (`?popout=session:<id>`) draws that one chat, no layout.
  // INSTALLER-1: while the daemon says nothing but its bootstrap plugin is installed, that plugin is the window.
  const bootstrap = useBootstrap();
  const pending = useBootPending(bootstrap);
  return (
    <UiProvider>
      <TooltipProvider delayDuration={400}>{pending ? null : bootstrap?.active ? <BootstrapScreen info={bootstrap} /> : popoutView ? <PopoutView sessionId={popoutView.id} /> : <LayoutHost />}</TooltipProvider>
    </UiProvider>
  );
}
