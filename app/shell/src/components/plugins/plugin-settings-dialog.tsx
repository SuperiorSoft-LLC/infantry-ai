// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RUST-UI-11: one plugin's settings in the ONE modal (AppDialog). Opened by the cog on a plugin card, or by a Settings
// search hit that points at the plugin. Rendered once, by the DialogHost.
import { create } from "zustand";
import { Button, AppDialog } from "@infantry/ui";
import { usePlugins } from "@/plugins/use-plugins";
import { PluginSettings } from "./plugin-settings";

interface Open {
  plugin: string | null;
  open: (plugin: string) => void;
  close: () => void;
}

export const usePluginSettingsDialog = create<Open>((set) => ({ plugin: null, open: (plugin) => set({ plugin }), close: () => set({ plugin: null }) }));

export function PluginSettingsDialog() {
  const id = usePluginSettingsDialog((s) => s.plugin);
  const close = usePluginSettingsDialog((s) => s.close);
  const row = usePlugins((s) => s.plugins.find((p) => p.id === id));
  if (!id) return null;
  const name = row?.ui?.displayName ?? id;
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && close()}
      title={`${name} settings`}
      description={row && !row.enabled ? "This plugin is off; its settings are kept and apply when you turn it on." : "Changes are saved as you make them."}
      wide
      footer={<Button onClick={close}>Done</Button>}
    >
      <PluginSettings plugin={id} />
    </AppDialog>
  );
}
