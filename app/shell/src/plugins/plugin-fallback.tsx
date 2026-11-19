// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The generic view of a declared tab this shell has no renderer for yet: which plugin declared it, its status, and its
// settings cards. A plugin is never invisible just because its view is not built.
import { Puzzle } from "lucide-react";
import type { Contribution } from "@infantry/client";
import { Chip, EmptyState, PanelCard } from "@infantry/ui";
import { TabPage } from "@/components/tabs/tab-page";
import { PluginSettings } from "@/components/plugins/plugin-settings";
import { usePlugins } from "./use-plugins";

export function PluginFallback({ contribution: c }: { contribution: Contribution }) {
  const p = usePlugins((s) => s.plugins.find((x) => x.id === c.plugin));
  return (
    <TabPage title={p?.ui?.displayName ?? c.plugin} description={p?.ui?.description ?? c.decl.id}>
      <div className="space-y-3">
        <PanelCard title="Plugin view" action={p && <Chip tone={p.status === "loaded" || p.status === "ui only" ? "green" : "amber"}>{p.status}</Chip>}>
          <EmptyState icon={<Puzzle className="size-6" />} title={`${c.decl.id} has no view in this app yet`} hint={`Declared by ${c.plugin}${p?.version ? ` v${p.version}` : ""}.`} />
        </PanelCard>
        {p?.ui?.config && <PluginSettings plugin={p.id} />}
      </div>
    </TabPage>
  );
}
