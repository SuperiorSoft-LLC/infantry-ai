// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// One plugin's settings cards (RUST-UI-5): the daemon-declared config groups whose node the plugin declares, drawn with
// the same ConfigCard the Settings dialog uses, then the cards its web UI half draws there (the `plugin.config` slot,
// WEBUI-1). Loading, empty and refused states are shown.
import { useEffect } from "react";
import { SlidersHorizontal } from "lucide-react";
import { EmptyState } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { ConfigCard } from "@/components/settings/config-card";
import { settingsStore, useSettings } from "@/components/settings/use-settings";
import { usePlugins } from "@/plugins/use-plugins";
import { PluginSlot, useSlotFills } from "@/plugins/web-plugins";

const NONE: string[] = [];

export function PluginSettings({ plugin }: { plugin: string }) {
  const nodes = usePlugins((s) => s.plugins.find((p) => p.id === plugin)?.ui?.configNodes ?? NONE);
  const groups = useSettings((s) => s.groups);
  const loaded = useSettings((s) => s.loaded);
  const link = useDaemon((s) => s.connection.state);
  useEffect(() => {
    if (link === "open") settingsStore().getState().ensureLoaded();
  }, [link]);
  const web = useSlotFills("plugin.config", plugin);
  const mine = groups.filter((g) => nodes.includes(g.node));
  if (!loaded && web.length === 0) return <EmptyState icon={<SlidersHorizontal className="size-5" />} title={link === "open" ? "Loading settings…" : "Waiting for the daemon…"} />;
  if (mine.length === 0 && web.length === 0) return <EmptyState icon={<SlidersHorizontal className="size-5" />} title="No settings from this plugin" hint={nodes.length ? "Its cards are not served by this daemon yet." : undefined} />;
  return (
    <div className="space-y-2">
      {mine.map((g) => (
        <ConfigCard key={`${g.node}/${g.group}`} group={g} />
      ))}
      <PluginSlot fills={web} props={{ plugin }} frame="card" />
    </div>
  );
}
