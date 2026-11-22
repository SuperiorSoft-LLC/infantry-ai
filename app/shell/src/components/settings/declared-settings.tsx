// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The daemon-declared Settings: a search box, then the matching cards grouped by tab, then the cards web UI plugins
// draw there (the `settings.card` slot, WEBUI-1: a contribution's `tab` names its tab, Plugins when it names none).
// Loading, empty and refused states are shown, not hidden.
import { useEffect, useMemo } from "react";
import { Search, X } from "lucide-react";
import { byTab, declLabel, searchGroups, type ConfigGroup, type SlotFill } from "@infantry/client";
import { Button, EmptyState, Input } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { usePlugins } from "@/plugins/use-plugins";
import { PluginSlot, useSlotFills } from "@/plugins/web-plugins";
import { useUi } from "@/state/ui-state";
import { usePluginSettingsDialog } from "@/components/plugins/plugin-settings-dialog";
import { ConfigCard } from "./config-card";
import { NotificationsCard } from "./notifications-card";
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

/** The Settings tab a web UI plugin's card sits in. */
const cardTab = (f: SlotFill): string => (typeof f.contribution.tab === "string" && f.contribution.tab.trim() ? f.contribution.tab.trim() : "Plugins");

/** The tab of the wake rules and held notices: drawn from the daemon's notice-kind registry, not from a declaration. */
const NOTIFICATIONS_TAB = "Notifications";

/** Whether a web UI plugin's card answers a search: its label, id or plugin. */
const cardMatches = (f: SlotFill, q: string): boolean => [declLabel(f.contribution), f.contribution.id, f.plugin].some((s) => s.toLowerCase().includes(q));

/**
 * The tabs the daemon's declarations form, for the dialog's side nav, then the ones web UI plugins' cards name. Core
 * settings only: plugin-owned cards are in the plugin's cog.
 */
export function useDeclaredTabs(): string[] {
  const owners = useOwners();
  const groups = useSettings((s) => s.groups).filter((g) => !owners.has(g.node));
  const cards = useSlotFills("settings.card");
  const link = useDaemon((s) => s.connection.state);
  useEffect(() => {
    if (link === "open") settingsStore().getState().ensureLoaded();
  }, [link]);
  const tabs = byTab(groups).map((t) => t.tab);
  if (!tabs.includes(NOTIFICATIONS_TAB)) tabs.push(NOTIFICATIONS_TAB);
  for (const c of cards) if (!tabs.includes(cardTab(c))) tabs.push(cardTab(c));
  return tabs;
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
  const q = query.trim().toLowerCase();
  const cards = useSlotFills("settings.card").filter((f) => (q ? cardMatches(f, q) : cardTab(f) === tab));
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
      {shown.length === 0 && (pointers.length > 0 || cards.length > 0) ? null : shown.length === 0 ? <EmptyState icon={<Search className="size-5" />} title={empty} /> : shown.map((g) => <ConfigCard key={`${g.node}/${g.group}`} group={g} />)}
      {tab === NOTIFICATIONS_TAB && !query.trim() && <NotificationsCard />}
      <PluginSlot fills={cards} frame="card" />
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
