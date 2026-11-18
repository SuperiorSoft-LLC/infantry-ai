// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Installed plugins as cards: tier, version, update, enable switch.
import { Puzzle, Download } from "lucide-react";
import { plugins } from "@/mock/data";
import type { Plugin } from "@/mock/types";
import { Button } from "@infantry/ui";
import { Switch } from "@infantry/ui";
import { Chip } from "@infantry/ui";
import { TabPage } from "./tab-page";

function PluginCard({ p }: { p: Plugin }) {
  return (
    <article className="flex flex-col gap-3 rounded-lg border bg-card p-4">
      <div className="flex items-start gap-3">
        <div className="grid size-10 shrink-0 place-items-center rounded-md border bg-raised text-danger">
          <Puzzle className="size-5" />
        </div>
        <div className="min-w-0 flex-1">
          <h3 className="truncate font-semibold">{p.name}</h3>
          <p className="text-xs text-muted-foreground">
            v{p.version} · {p.author}
          </p>
        </div>
        <Switch defaultChecked={p.enabled} aria-label={`Enable ${p.name}`} />
      </div>
      <p className="flex-1 text-[13px] leading-5 text-muted-foreground">{p.description}</p>
      <div className="flex flex-wrap items-center gap-2">
        <Chip tone={p.tier === "official" ? "red" : "blue"}>{p.tier}</Chip>
        {p.updateAvailable && <Chip tone="green">update {p.updateAvailable}</Chip>}
        <span className="flex-1" />
        <Button variant="ghost" size="xs">
          Settings
        </Button>
      </div>
    </article>
  );
}

export function PluginsTab() {
  return (
    <TabPage
      title="Plugins"
      description="App = OS, plugins = programs"
      actions={
        <Button variant="outline" size="sm">
          <Download /> Browse catalog
        </Button>
      }
    >
      <div className="grid grid-cols-[repeat(auto-fill,minmax(260px,1fr))] gap-3">
        {plugins.map((p) => (
          <PluginCard key={p.id} p={p} />
        ))}
      </div>
    </TabPage>
  );
}
