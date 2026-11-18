// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Custom title bar: brand, project, new/quick, avatars, attention, host, connection, window controls.
// Empty areas carry data-tauri-drag-region so the window moves by dragging the bar.
import { PanelLeft, PanelRight, Plus, Wand2, Moon, Sun, Settings } from "lucide-react";
import { connection } from "@/mock/data";
import { ConnectionBadge } from "./connection-badge";
import { useUi } from "@/state/ui-state";
import { IconButton } from "@infantry/ui";
import { AvatarStrip } from "@/components/agents/avatar-strip";
import { Brand } from "./brand";
import { ProjectPicker } from "./project-picker";
import { WindowControls } from "./window-controls";
import { isMobile } from "@/daemon/daemon";

export function TitleBar() {
  const ui = useUi();
  return (
    <header data-tauri-drag-region className="flex h-10 shrink-0 items-center gap-1 border-b bg-background pl-2 select-none sm:gap-2">
      <IconButton label={ui.leftOpen ? "Hide agents" : "Show agents"} onClick={() => ui.toggle("leftOpen")}>
        <PanelLeft />
      </IconButton>
      <span className="hidden sm:contents">
        <Brand />
      </span>
      <ProjectPicker />
      <IconButton label="New ticket" variant="outline" className="border-danger/70 text-danger" onClick={() => ui.set("dialog", "new-ticket")}>
        <Plus />
      </IconButton>
      <IconButton label="Quick agent" className="bg-danger text-white hover:bg-danger/85" onClick={() => ui.set("dialog", "new-agent")}>
        <Wand2 />
      </IconButton>
      <div className="ml-1 hidden min-w-0 lg:block">
        <AvatarStrip />
      </div>

      <div data-tauri-drag-region className="h-full min-w-4 flex-1" />

      <button
        type="button"
        onClick={() => {
          ui.set("rightTab", "attention");
          if (!ui.rightOpen) ui.toggle("rightOpen");
        }}
        className="h-7 rounded border border-danger/70 px-2 text-xs whitespace-nowrap text-danger hover:bg-danger/10"
      >
        {connection.attention}
        <span className="hidden sm:inline"> need attention</span>
      </button>
      <IconButton label={ui.rightOpen ? "Hide side panel" : "Show side panel"} onClick={() => ui.toggle("rightOpen")}>
        <PanelRight />
      </IconButton>
      <span className="hidden items-center gap-1.5 text-xs whitespace-nowrap md:flex">
        <span className="size-1.5 rounded-full bg-foreground" /> {connection.host}
      </span>
      <ConnectionBadge showUrl className="hidden font-mono text-xs whitespace-nowrap xl:flex" />
      <IconButton label={ui.theme === "dark" ? "Light theme" : "Dark theme"} className="hidden sm:inline-flex" onClick={ui.toggleTheme}>
        {ui.theme === "dark" ? <Sun /> : <Moon />}
      </IconButton>
      <IconButton label="Settings" onClick={() => ui.set("dialog", "settings")}>
        <Settings />
      </IconButton>
      {!isMobile && <WindowControls />}
    </header>
  );
}
