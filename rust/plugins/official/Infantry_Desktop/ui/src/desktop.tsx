// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// DESK-2: the desktop layout. A plain wallpaper with an icon per app. An app opens full screen under a slim app title bar with
// Minimize (back to the desktop, the app stays open and keeps its state) and Close (the app ends; agents keep running because
// they live in the daemon). The orchestrator is today's whole shell, drawn untouched. Built from the kit only.
import { useReducer, useState, type ComponentType, type ReactNode } from "react";
import { ChevronsDown, LayoutDashboard, MoreVertical, PackageOpen, X } from "lucide-react";
import { AppDialog, cn, DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger, IconButton, PanelCard, Switch, Tooltip, TooltipContent, TooltipTrigger } from "@infantry/ui";
import { AgentAvatar, SCOPE_ATTRIBUTE, useAgentActivity, usePreference, type ShellProps } from "@infantry/ui-plugin-api";
import { initialState, reduce, toggle, type AppId, type DesktopState } from "./state";

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

/**
 * The app menu of the orchestrator: it sits in the shell's own header (the layout hands it over as `headerActions`), so an open
 * app needs no title bar of its own. Minimize goes back to the desktop, Close ends the app, About opens the app's About.
 */
function AppMenu({ app, onMinimize, onClose, onAbout }: { app: AppInfo; onMinimize: () => void; onClose: () => void; onAbout: () => void }) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <IconButton label={`${app.name} app menu`} data-testid="app-menu">
          <MoreVertical />
        </IconButton>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem onSelect={onMinimize}>Minimize</DropdownMenuItem>
        <DropdownMenuItem onSelect={onClose}>Close</DropdownMenuItem>
        <DropdownMenuItem onSelect={onAbout}>About</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** What the shell draws is outside this plugin's style scope; slot content that carries our classes is wrapped in it again. */
function Scoped({ plugin, children }: { plugin: string; children: ReactNode }) {
  return (
    <div {...{ [SCOPE_ATTRIBUTE]: plugin }} style={{ display: "contents" }}>
      {children}
    </div>
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

/** Avatars of the busy agents the taskbar has room for. */
const TASKBAR_AVATARS = 4;

/** The orchestrator entry's badge: agents working, and (highlighted) agents that need the user. Reads the app's session store. */
function AgentBadge() {
  const { working, needsInput } = useAgentActivity();
  if (working === 0 && needsInput === 0) return null;
  return (
    <span className="flex items-center gap-1" data-testid="taskbar-badge">
      {working > 0 && (
        <span title={`${working} working`} aria-label={`${working} agents working`} data-testid="taskbar-working" className="grid h-5 min-w-5 place-items-center rounded border border-ok/70 px-1 text-[11px] text-ok">
          {working}
        </span>
      )}
      {needsInput > 0 && (
        <span title={`${needsInput} need you`} aria-label={`${needsInput} agents need you`} data-testid="taskbar-needs-you" className="grid h-5 min-w-5 place-items-center rounded border border-danger/70 bg-danger/10 px-1 text-[11px] font-semibold text-danger">
          {needsInput}
        </span>
      )}
    </span>
  );
}

function TaskbarAvatars() {
  const { agents } = useAgentActivity();
  const shown = agents.slice(0, TASKBAR_AVATARS);
  if (shown.length === 0) return null;
  return (
    <span className="hidden items-center gap-1 sm:flex" data-testid="taskbar-avatars">
      {shown.map((a) => (
        <Tooltip key={a.id}>
          <TooltipTrigger asChild>
            <span className="inline-flex">
              <AgentAvatar agentId={a.id} size="xs" />
            </span>
          </TooltipTrigger>
          <TooltipContent>
            {a.name} · {a.statusLine}
          </TooltipContent>
        </Tooltip>
      ))}
    </span>
  );
}

/** Along the bottom on a desktop, a dock on a phone: one entry per open app. Clicking a minimized entry restores it; the front one minimizes. */
function Taskbar({ state, onToggle }: { state: DesktopState; onToggle: (app: AppInfo) => void }) {
  const open = APPS.filter((a) => state.windows[a.id] !== "closed");
  return (
    <nav aria-label="Taskbar" data-testid="taskbar" className="flex h-full min-w-0 items-center gap-1">
      {open.length === 0 && <span className="px-1 text-xs text-muted-foreground">No apps open</span>}
      {open.map((a) => {
        const Icon = a.icon;
        const front = state.front === a.id;
        return (
          <div key={a.id} className="flex items-center gap-2 max-sm:gap-1">
            <button
              type="button"
              onClick={() => onToggle(a)}
              aria-label={`${a.name}${front ? "" : " (minimized)"}`}
              aria-pressed={front}
              data-testid={`taskbar-${a.id}`}
              data-state={front ? "open" : "minimized"}
              title={front ? `Minimize ${a.name}` : `Restore ${a.name}`}
              className={cn(
                "flex h-5 items-center gap-2 rounded border px-2 text-xs hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none max-sm:h-12 max-sm:min-w-12 max-sm:justify-center max-sm:rounded-lg max-sm:px-3",
                front ? "border-foreground/40 bg-accent font-medium" : "bg-background",
              )}
            >
              <Icon className="size-3.5 max-sm:size-4" />
              <span className="hidden sm:inline">{a.name}</span>
              {a.id === "orchestrator" && <AgentBadge />}
            </button>
            {a.id === "orchestrator" && <TaskbarAvatars />}
          </div>
        );
      })}
    </nav>
  );
}

export function Desktop({ plugin, Shell, View }: { plugin: string; Shell: ComponentType<ShellProps>; View: ComponentType<{ plugin: string; id: string }> }) {
  const [openAtStart] = usePreference("desktop.openAtStart", false);
  const [state, dispatch] = useReducer(reduce, openAtStart, initialState);
  const [about, setAbout] = useState<AppInfo | null>(null);
  const taskbar = (
    <Scoped plugin={plugin}>
      <Taskbar state={state} onToggle={(a) => dispatch(toggle(state, a.id))} />
    </Scoped>
  );
  const orchestrator = APPS[0]!;
  const body = (id: AppId): ReactNode =>
    id === "orchestrator" ? (
      <Shell
        className="min-h-0 flex-1"
        statusLeading={taskbar}
        headerActions={
          <Scoped plugin={plugin}>
            <AppMenu app={orchestrator} onMinimize={() => dispatch({ type: "minimize", app: "orchestrator" })} onClose={() => dispatch({ type: "close", app: "orchestrator" })} onAbout={() => setAbout(orchestrator)} />
          </Scoped>
        }
      />
    ) : (
      <View plugin="Infantry_Installer" id="infantry.installer" />
    );
  return (
    <div className="flex h-full w-full flex-col overflow-hidden bg-gradient-to-br from-background via-muted to-background">
     <div className="relative min-h-0 flex-1 overflow-hidden">
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
              {a.id !== "orchestrator" && <AppBar app={a} onMinimize={() => dispatch({ type: "minimize", app: a.id })} onClose={() => dispatch({ type: "close", app: a.id })} />}
              <div className="relative min-h-0 flex-1">
                <div className="absolute inset-0 flex flex-col">{body(a.id)}</div>
              </div>
            </Window>
          ),
      )}
     </div>
      {/* The orchestrator's status line carries the taskbar; with another app or the wallpaper in front, a strip of the same height does. */}
      {state.front !== "orchestrator" && <footer className="flex h-6 shrink-0 items-center border-t bg-background px-3 max-sm:h-14">{taskbar}</footer>}
      <About app={about} onClose={() => setAbout(null)} />
    </div>
  );
}

/** The plugin's card under Plugins: pick this layout, and whether the orchestrator opens at start. */
export function DesktopSettings({ plugin }: { plugin: string }) {
  // "" = never chosen: the start layout, which this plugin's manifest declares (defaultLayout), so the switch reads on.
  const [choice, setChoice] = usePreference<string>("layout", "");
  const [openAtStart, setOpenAtStart] = usePreference("desktop.openAtStart", false);
  const mine = layoutChoice(plugin);
  return (
    <PanelCard title="Desktop">
      <div className="space-y-3 text-sm">
        <label className="flex items-center justify-between gap-3">
          <span>Use the desktop layout on this device</span>
          <Switch checked={choice === mine || choice === ""} onCheckedChange={(on: boolean) => setChoice(on ? mine : "default")} aria-label="Use the desktop layout" />
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
