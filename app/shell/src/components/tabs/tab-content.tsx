// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Picks the mock view for the active centre tab.
import { Box, FileText, GitCommit, IdCard, Layers, Play, Server, Zap } from "lucide-react";
import { epics, gitCommits, notes, pods, profiles, runCommands, services, triggers } from "@/mock/data";
import type { CentreView } from "@/state/ui-state";
import { BoardTab } from "./board-tab";
import { PluginsTab } from "./plugins-tab";
import { RolesTab } from "./roles-tab";
import { CalendarTab } from "./calendar-tab";
import { StatsTab } from "./stats-tab";
import { PaceTab } from "./pace-tab";
import { ListTab } from "./list-tab";

export function TabContent({ tab }: { tab: CentreView }) {
  switch (tab) {
    case "board":
      return <BoardTab />;
    case "pods":
      return <ListTab title="Pods" description="Groups of agents and where they run" rows={pods} icon={<Box className="size-4" />} actionLabel="New pod" />;
    case "roles":
      return <RolesTab />;
    case "profiles":
      return <ListTab title="Profiles" description="Harness, model and effort presets" rows={profiles} icon={<IdCard className="size-4" />} actionLabel="New profile" />;
    case "triggers":
      return <ListTab title="Triggers" description="Events that run Lua scripts" rows={triggers} icon={<Zap className="size-4" />} actionLabel="New trigger" />;
    case "calendar":
      return <CalendarTab />;
    case "epics":
      return <ListTab title="Epics" description="Groups of tickets" rows={epics} icon={<Layers className="size-4" />} actionLabel="New epic" />;
    case "notes":
      return <ListTab title="Notes" description="Project memory" rows={notes} icon={<FileText className="size-4" />} actionLabel="New note" />;
    case "run":
      return <ListTab title="Run" description="Saved run commands" rows={runCommands} icon={<Play className="size-4" />} actionLabel="New command" />;
    case "git":
      return <ListTab title="Git" description="ticket/e85faaeb · 3 recent commits" rows={gitCommits} icon={<GitCommit className="size-4" />} />;
    case "plugins":
      return <PluginsTab />;
    case "stats":
      return <StatsTab />;
    case "pace":
      return <PaceTab />;
    case "services":
      return <ListTab title="Services" description="Runner, orchestrator and web hosting" rows={services} icon={<Server className="size-4" />} />;
    case "cli":
      return null;
  }
}
