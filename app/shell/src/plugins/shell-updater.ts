// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// ARMORY-3: the shell's own updater, lent to plugins (HostApi.shellUpdater). It wraps the existing Tauri updater commands
// (update_check, update_download, update_install) and the release feed set in Settings; it adds no feed of its own.
import type { Host, UpdateInfo } from "@infantry/client";
import type { ShellUpdater } from "@infantry/ui-plugin-api/contract";

/** The desktop app's updater, or null where there is none (a browser; a phone installs through its own store flow). */
export function shellUpdater(host: Pick<Host, "kind" | "call">): ShellUpdater | null {
  if (host.kind !== "desktop") return null;
  return {
    async check() {
      const u = await host.call<UpdateInfo>("update_check");
      return { available: u.available, version: u.version, current: u.current };
    },
    download: () => host.call<string>("update_download"),
    // Installs the downloaded copy and restarts the app. Only ever called from a click.
    relaunch: () => host.call<void>("update_install"),
  };
}
