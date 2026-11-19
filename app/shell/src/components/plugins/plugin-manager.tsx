// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The plugin manager (RUST-UI-5; Kotlin `infantry.settings.plugins`): every module on the daemon host as a bordered card
// with tier, version, status and faults, an enable switch (owner, through `plugin_admin`), what it contributes to the
// shell, and its own settings cards. Filter by all / enabled / faults. Live from the daemon's plugin list.
import { useMemo, useState } from "react";
import { AlertTriangle, Puzzle, RefreshCw, Settings } from "lucide-react";
import { isFault, type PluginRow } from "@infantry/client";
import { Button, Chip, EmptyState, IconButton, Input, Switch, cn } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { TabPage } from "@/components/tabs/tab-page";
import { RefusalBanner, Segmented } from "@/components/knowledge/parts";
import { pluginsStore, usePlugins } from "@/plugins/use-plugins";
import { usePluginSettingsDialog } from "./plugin-settings-dialog";

type Filter = "all" | "enabled" | "faults";

function contributes(p: PluginRow): string[] {
  const u = p.ui;
  if (!u) return [];
  return [...u.tabs.map((t) => `tab ${t.id}`), ...u.panels.map((t) => `panel ${t.id}`), ...u.fills.map((t) => `fills ${t.id}`), ...u.slots.map((t) => `slot ${t.id}`)];
}

function PluginCard({ p, pending }: { p: PluginRow; pending: boolean }) {
  const fault = isFault(p);
  const parts = contributes(p);
  const name = p.ui?.displayName ?? p.id;
  const hasSettings = !!p.ui && (p.ui.config || p.ui.configNodes.length > 0); // shown even when the plugin is off
  return (
    <article className={cn("flex flex-col gap-3 rounded-lg border bg-card p-4", fault && "border-danger/60")} aria-label={name}>
      <div className="flex items-start gap-3">
        <div className="grid size-10 shrink-0 place-items-center rounded-md border bg-raised text-danger">{fault ? <AlertTriangle className="size-5" /> : <Puzzle className="size-5" />}</div>
        <div className="min-w-0 flex-1">
          <h3 className="truncate font-semibold">{name}</h3>
          <p className="truncate text-xs text-muted-foreground">
            {p.id}
            {p.version ? ` · v${p.version}` : ""}
            {p.license ? ` · ${p.license}` : ""}
          </p>
        </div>
        {hasSettings && (
          <IconButton label="Settings" className="shrink-0" onClick={() => usePluginSettingsDialog.getState().open(p.id)}>
            <Settings />
          </IconButton>
        )}
        <Switch checked={p.enabled} disabled={pending} onCheckedChange={(v) => pluginsStore().getState().setEnabled(p.id, v)} aria-label={`${p.enabled ? "Disable" : "Enable"} ${name}`} />
      </div>
      {p.ui?.description && <p className="text-[13px] leading-5 text-muted-foreground">{p.ui.description}</p>}
      <div className="flex flex-wrap items-center gap-2">
        <Chip tone={p.tier === "official" ? "red" : "blue"}>{p.tier}</Chip>
        <Chip tone={fault ? "red" : p.status === "loaded" ? "green" : "neutral"}>{pending ? "applying…" : p.status}</Chip>
        {p.updateTo && <Chip tone="green">update {p.updateTo}</Chip>}
        {(p.grants ?? []).map((g) => (
          <Chip key={g} mono>
            {g}
          </Chip>
        ))}
      </div>
      {parts.length > 0 && (
        <p className="truncate font-mono text-[11px] text-muted-foreground" title={parts.join("\n")}>
          {parts.join(" · ")}
        </p>
      )}
    </article>
  );
}

export function PluginManager() {
  const plugins = usePlugins((s) => s.plugins);
  const loaded = usePlugins((s) => s.loaded);
  const pending = usePlugins((s) => s.pending);
  const refusal = usePlugins((s) => s.refusal);
  const link = useDaemon((s) => s.connection.state);
  const [filter, setFilter] = useState<Filter>("all");
  const [query, setQuery] = useState("");

  const faults = plugins.filter(isFault).length;
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return plugins
      .filter((p) => (filter === "enabled" ? p.enabled : filter === "faults" ? isFault(p) : true))
      .filter((p) => !q || [p.id, p.ui?.displayName, p.ui?.description].some((v) => v?.toLowerCase().includes(q)));
  }, [plugins, filter, query]);

  return (
    <TabPage
      title="Plugins"
      description={loaded ? `${plugins.length} installed · ${plugins.filter((p) => p.enabled).length} enabled${faults ? ` · ${faults} with a fault` : ""}` : "App = OS, plugins = programs"}
      actions={
        <Button variant="ghost" size="sm" aria-label="Refresh" onClick={() => pluginsStore().getState().refresh()}>
          <RefreshCw />
        </Button>
      }
    >
      <RefusalBanner text={refusal} onDismiss={() => pluginsStore().getState().clearRefusal()} />
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Segmented<Filter>
          value={filter}
          onChange={setFilter}
          options={[
            { id: "all", label: "All" },
            { id: "enabled", label: "Enabled" },
            { id: "faults", label: `Faults${faults ? ` (${faults})` : ""}` },
          ]}
        />
        <Input aria-label="Search plugins" placeholder="Search plugins" className="h-8 max-w-60 text-xs" value={query} onChange={(e) => setQuery(e.target.value)} />
      </div>
      {!loaded ? (
        <EmptyState icon={<Puzzle className="size-6" />} title={link === "open" ? "Loading plugins…" : "Waiting for the daemon…"} />
      ) : shown.length === 0 ? (
        <EmptyState icon={<Puzzle className="size-6" />} title={plugins.length === 0 ? "No plugins on this host" : "No plugin matches"} />
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(280px,1fr))] gap-3">
          {shown.map((p) => (
            <PluginCard key={p.id} p={p} pending={pending.includes(p.id)} />
          ))}
        </div>
      )}
    </TabPage>
  );
}
