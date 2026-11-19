// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The daemon-declared Settings: a search box, then the matching cards grouped by tab. Loading, empty and refused
// states are shown, not hidden.
import { useEffect, useMemo } from "react";
import { Search, X } from "lucide-react";
import { byTab, searchGroups, type ConfigGroup } from "@infantry/client";
import { Button, EmptyState, Input } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { usePlugins } from "@/plugins/use-plugins";
import { useUi } from "@/state/ui-state";
import { usePluginSettingsDialog } from "@/components/plugins/plugin-settings-dialog";
import { ConfigCard } from "./config-card";
import { settingsStore, useSettings } from "./use-settings";

/** RUST-UI-11: a plugin's settings live in its cog on the Plugins tab; this maps each such node to its plugin. */
function useOwners(): Map<string, { id: string; name: string }> {
  const plugins = usePlugins((s) => s.plugins);
  return useMemo(() => {
    const m = new Map<string, { id: string; name: string }>();
    for (const p of plugins) for (const n of p.ui?.configNodes ?? []) m.set(n, { id: p.id, name: p.ui?.displayName ?? p.id });
    return m;
  }, [plugins]);
}

/** The tabs the daemon's declarations form, for the dialog's side nav. Core settings only: plugin-owned cards are in the plugin's cog. */
export function useDeclaredTabs(): string[] {
  const owners = useOwners();
  const groups = useSettings((s) => s.groups).filter((g) => !owners.has(g.node));
  const link = useDaemon((s) => s.connection.state);
  useEffect(() => {
    if (link === "open") settingsStore().getState().ensureLoaded();
  }, [link]);
  return byTab(groups).map((t) => t.tab);
}

export function DeclaredSettings({ tab, query }: { tab: string | null; query: string }) {
  const all = useSettings((s) => s.groups);
  const owners = useOwners();
  const ui = useUi();
  const groups = all.filter((g) => !owners.has(g.node));
  const pointers = query.trim() ? [...new Map(searchGroups(all, query).filter((g) => owners.has(g.node)).map((g) => [owners.get(g.node)!.id, owners.get(g.node)!])).values()] : [];
  const loaded = useSettings((s) => s.loaded);
  const refusal = useSettings((s) => s.refusal);
  const link = useDaemon((s) => s.connection.state);

  const matches: ConfigGroup[] = searchGroups(groups, query);
  const shown = query.trim() ? matches : (byTab(groups).find((t) => t.tab === tab)?.groups ?? []);
  const toCog = (id: string) => {
    ui.set("dialog", null);
    usePluginSettingsDialog.getState().open(id);
  };
  const empty = !loaded ? (link === "open" ? "Loading settings…" : "Waiting for the daemon…") : query.trim() ? `No setting matches “${query.trim()}”.` : "No plugin declares settings here.";
  return (
    <div className="space-y-2">
      {refusal && (
        <div role="alert" className="flex items-start justify-between gap-2 rounded-lg border border-danger/60 bg-danger/10 p-2 text-xs text-danger">
          <span>{refusal}</span>
          <Button variant="ghost" size="icon" className="size-5" aria-label="Dismiss" onClick={() => settingsStore().getState().clearRefusal()}>
            <X className="size-3" />
          </Button>
        </div>
      )}
      {pointers.map((o) => (
        <div key={o.id} className="flex items-center justify-between gap-2 rounded-lg border bg-card p-3 text-xs">
          <span className="text-muted-foreground">
            Lives in <span className="font-medium text-foreground">{o.name} &gt; settings</span>
          </span>
          <Button variant="outline" size="xs" onClick={() => toCog(o.id)}>
            Open
          </Button>
        </div>
      ))}
      {shown.length === 0 && pointers.length > 0 ? null : shown.length === 0 ? <EmptyState icon={<Search className="size-5" />} title={empty} /> : shown.map((g) => <ConfigCard key={`${g.node}/${g.group}`} group={g} />)}
    </div>
  );
}

/** The search box over every declared card. */
export function SettingsSearch({ query, onQuery }: { query: string; onQuery: (q: string) => void }) {
  return (
    <div className="relative">
      <Search className="pointer-events-none absolute top-2 left-2.5 size-3.5 text-muted-foreground" />
      <Input aria-label="Search settings" placeholder="Search settings" className="h-8 pr-8 pl-8 text-xs" value={query} onChange={(e) => onQuery(e.target.value)} />
      {query && (
        <Button variant="ghost" size="icon" className="absolute top-1 right-1 size-6" aria-label="Clear search" onClick={() => onQuery("")}>
          <X className="size-3" />
        </Button>
      )}
    </div>
  );
}
