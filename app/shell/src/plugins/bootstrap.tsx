// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// INSTALLER-1: the window while the daemon is in bootstrap mode (nothing but its bootstrap plugin is installed). The daemon
// names that plugin in its `boot_progress` report; the shell knows no plugin id. It draws the plugin's first tab as the whole
// window, so the owner can install the rest from there. The first install ends bootstrap mode, the report turns inactive,
// and `App` swaps to the normal shell with no reload.
import { useEffect, type ReactNode } from "react";
import { AlertTriangle, Loader2 } from "lucide-react";
import { useStore } from "zustand";
import { createBootstrapStore, type BootstrapInfo, type BootstrapStore } from "@infantry/client";
import { EmptyState } from "@infantry/ui";
import { daemonStore, useDaemon } from "@/daemon/daemon";
import { usePlugins, usePluginsLoaded } from "./use-plugins";
import { PluginView, useSlotFills } from "./web-plugins";

let store: BootstrapStore | null = null;
export const bootstrapStore = (): BootstrapStore => (store ??= createBootstrapStore(daemonStore().getState()));

/** What the daemon says about its bootstrap plugin; null until it has said (the app shows as it always did). Asks again whenever the link opens. */
export function useBootstrap(): BootstrapInfo | null {
  const link = useDaemon((s) => s.connection.state);
  useEffect(() => {
    if (link === "open") bootstrapStore().getState().refresh();
  }, [link]);
  return useStore(bootstrapStore(), (s) => s.info);
}

function Centered({ children }: { children: ReactNode }) {
  return <div className="grid h-full flex-1 place-items-center p-6">{children}</div>;
}

/** The bootstrap plugin's screen, full window. */
export function BootstrapScreen({ info }: { info: BootstrapInfo }) {
  usePluginsLoaded();
  const loaded = usePlugins((s) => s.loaded);
  const first = useSlotFills("tab", info.plugin ?? undefined)[0];
  return (
    <div className="flex h-full flex-col bg-background text-foreground" data-bootstrap={info.plugin ?? ""}>
      {info.error ? (
        <Centered>
          <EmptyState icon={<AlertTriangle className="size-6" />} title={info.plugin ? `The installer (${info.plugin}) could not start` : "The installer could not start"} hint={info.error} />
        </Centered>
      ) : first ? (
        <PluginView plugin={first.plugin} id={first.contribution.id} frame="page" />
      ) : (
        <Centered>
          {loaded ? (
            <EmptyState icon={<AlertTriangle className="size-6" />} title="The installer has no screen" hint={`${info.plugin ?? "The bootstrap plugin"} contributes no tab, so there is nothing to show.`} />
          ) : (
            <EmptyState icon={<Loader2 className="size-6 animate-spin" />} title="Starting the installer…" />
          )}
        </Centered>
      )}
    </div>
  );
}
