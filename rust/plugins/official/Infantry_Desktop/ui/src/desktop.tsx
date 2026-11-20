// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// DESK-2: the desktop layout. A plain wallpaper with an icon per app. An app opens full screen under a slim app title bar with
// Minimize (back to the desktop, the app stays open and keeps its state) and Close (the app ends; agents keep running because
// they live in the daemon). The orchestrator is today's whole shell, drawn untouched. Built from the kit only.
import { useReducer, useState, type ComponentType, type ReactNode } from "react";
import { ChevronsDown, LayoutDashboard, PackageOpen, X } from "lucide-react";
import { AppDialog, IconButton, PanelCard, Switch } from "@infantry/ui";
import { usePreference } from "@infantry/ui-plugin-api";
import { initialState, reduce, type AppId } from "./state";

/** What the "About" of an app says: the owner's legibility rule (an app is a plugin under Apache-2.0, not core). */
interface AppInfo {
  id: AppId;
  name: string;
  icon: ComponentType<{ className?: string }>;
  plugin: string;
  version: string;
}

const APPS: readonly AppInfo[] = [
  { id: "orchestrator", name: "Infantry AI", icon: LayoutDashboard, plugin: "Infantry_Ui", version: "1.0.0" },
  { id: "apps", name: "Apps", icon: PackageOpen, plugin: "Infantry_Installer", version: "1.0.0" },
];

/** The layout contribution's preference value for this plugin: `<Plugin>/<id>`. */
export const layoutChoice = (plugin: string): string => `${plugin}/infantry.desktop`;

function About({ app, onClose }: { app: AppInfo | null; onClose: () => void }) {
  return (
    <AppDialog open={app !== null} onOpenChange={(o) => !o && onClose()} title={app?.name ?? ""} description="About this app">
      {app && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
          <dt className="text-muted-foreground">Version</dt>
          <dd>{app.version}</dd>
          <dt className="text-muted-foreground">Licence</dt>
          <dd>Apache-2.0</dd>
          <dt className="text-muted-foreground">Kind</dt>
          <dd>Plugin ({app.plugin}), not core</dd>
        </dl>
      )}
    </AppDialog>
  );
}

function DesktopIcon({ app, running, onOpen, onAbout }: { app: AppInfo; running: boolean; onOpen: () => void; onAbout: () => void }) {
  const Icon = app.icon;
  return (
    <button
      type="button"
      onClick={onOpen}
      onContextMenu={(e) => {
        e.preventDefault();
        onAbout();
      }}
      title={`${app.name} (right-click for About)`}
      className="group flex w-24 flex-col items-center gap-1.5 rounded-lg p-2 text-xs hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
    >
      <span className="grid size-14 place-items-center rounded-xl border bg-card shadow-sm group-hover:border-foreground/40">
        <Icon className="size-7" />
      </span>
      <span className="flex items-center gap-1">
        {running && <span className="size-1.5 rounded-full bg-foreground" aria-label="running" />}
        {app.name}
      </span>
    </button>
  );
}

/** The slim title bar of an open app. Its buttons are the APP's: the OS window controls (desktop app) sit at the far right. */
function AppBar({ app, onMinimize, onClose }: { app: AppInfo; onMinimize: () => void; onClose: () => void }) {
  const Icon = app.icon;
  return (
    <header data-tauri-drag-region className="flex h-8 shrink-0 items-center gap-1 border-b bg-muted/50 pl-1 text-xs select-none">
      <IconButton label={`Minimize ${app.name} to the desktop`} size="icon-xs" onClick={onMinimize}>
        <ChevronsDown />
      </IconButton>
      <IconButton label={`Close ${app.name}`} size="icon-xs" onClick={onClose}>
        <X />
      </IconButton>
      <Icon className="ml-1 size-3.5 text-muted-foreground" />
      <span data-tauri-drag-region className="font-medium">
        {app.name}
      </span>
      <span data-tauri-drag-region className="h-full flex-1" />
    </header>
  );
}

/** Hidden, not unmounted, while minimized: the app keeps running and reopens as it was. */
function Window({ show, children }: { show: boolean; children: ReactNode }) {
  return (
    <div className={show ? "absolute inset-0 flex flex-col bg-background" : "hidden"} aria-hidden={!show}>
      {children}
    </div>
  );
}

export function Desktop({ Shell, View }: { Shell: ComponentType<{ className?: string }>; View: ComponentType<{ plugin: string; id: string }> }) {
  const [openAtStart] = usePreference("desktop.openAtStart", false);
  const [state, dispatch] = useReducer(reduce, openAtStart, initialState);
  const [about, setAbout] = useState<AppInfo | null>(null);
  const body = (id: AppId): ReactNode => (id === "orchestrator" ? <Shell className="min-h-0 flex-1" /> : <View plugin="Infantry_Installer" id="infantry.installer" />);
  return (
    <div className="relative h-full w-full overflow-hidden bg-gradient-to-br from-background via-muted to-background">
      {state.front === null && (
        <div data-tauri-drag-region className="absolute inset-0 flex flex-wrap content-start gap-2 p-4" role="group" aria-label="Desktop">
          {APPS.map((a) => (
            <DesktopIcon key={a.id} app={a} running={state.windows[a.id] !== "closed"} onOpen={() => dispatch({ type: "open", app: a.id })} onAbout={() => setAbout(a)} />
          ))}
        </div>
      )}
      {APPS.map(
        (a) =>
          state.windows[a.id] !== "closed" && (
            <Window key={a.id} show={state.front === a.id}>
              <AppBar app={a} onMinimize={() => dispatch({ type: "minimize", app: a.id })} onClose={() => dispatch({ type: "close", app: a.id })} />
              <div className="relative min-h-0 flex-1">
                <div className="absolute inset-0 flex flex-col">{body(a.id)}</div>
              </div>
            </Window>
          ),
      )}
      <About app={about} onClose={() => setAbout(null)} />
    </div>
  );
}

/** The plugin's card under Plugins: pick this layout, and whether the orchestrator opens at start. */
export function DesktopSettings({ plugin }: { plugin: string }) {
  const [choice, setChoice] = usePreference<string>("layout", "default");
  const [openAtStart, setOpenAtStart] = usePreference("desktop.openAtStart", false);
  const mine = layoutChoice(plugin);
  return (
    <PanelCard title="Desktop">
      <div className="space-y-3 text-sm">
        <label className="flex items-center justify-between gap-3">
          <span>Use the desktop layout on this device</span>
          <Switch checked={choice === mine} onCheckedChange={(on: boolean) => setChoice(on ? mine : "default")} aria-label="Use the desktop layout" />
        </label>
        <label className="flex items-center justify-between gap-3">
          <span>Open the orchestrator at start</span>
          <Switch checked={openAtStart} onCheckedChange={setOpenAtStart} aria-label="Open the orchestrator at start" />
        </label>
        <p className="text-xs text-muted-foreground">Turn the layout off to get today's tab layout back, exactly as it was.</p>
      </div>
    </PanelCard>
  );
}
