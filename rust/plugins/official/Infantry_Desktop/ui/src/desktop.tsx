// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// DESK-2: the desktop layout. A plain wallpaper with an icon per app. An app opens full screen under a slim app title bar with
// Minimize (back to the desktop, the app stays open and keeps its state) and Close (the app ends; agents keep running because
// they live in the daemon). The orchestrator is today's whole shell, drawn untouched. Built from the kit only.
// DESK-5: the Start menu at the left end of the bottom strip lists the apps; the header's minimize / maximize / close are the
// shell's own and drive the real window. The app menu acts on the APP inside this OS (minimize to the taskbar, close).
import { useEffect, useLayoutEffect, useMemo, useReducer, useRef, useState, type ComponentType, type ReactNode } from "react";
import { useStore } from "zustand";
import { ChevronsDown, Download, LayoutDashboard, LayoutGrid, Minus, MoreVertical, PackageOpen, Power, X } from "lucide-react";
import {
  AppDialog,
  Button,
  cn,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  IconButton,
  PanelCard,
  Switch,
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@infantry/ui";
import { AgentAvatar, SCOPE_ATTRIBUTE, osApp, useAgentActivity, usePreference, windowFrame, type FrameLink, type ShellProps } from "@infantry/ui-plugin-api";
import { cellAt, CELL_H, CELL_W, drop, DRAG_THRESHOLD, GRID_PAD, gridSize, neighbor, parsePositions, place, POSITIONS_KEY, serializePositions, type Direction, type GridSize } from "./grid";
import { appsListed, initialState, updatesShown, reduce, toggle, type AppId, type DesktopState } from "./state";
import { appRoute, createUpdatesStore, versionLine, type AppUpdate, type UpdatesStore } from "./updates";

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

/** BOS-24: the apps this window lists (no Apps app under BattalionOS). */
const listed = (): AppInfo[] => appsListed(APPS, osApp() !== null);

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

function DesktopIcon({ app, running }: { app: AppInfo; running: boolean }) {
  const Icon = app.icon;
  return (
    <>
      <span className="grid size-14 place-items-center rounded-xl border bg-card shadow-sm group-hover:border-foreground/40">
        <Icon className="size-7" />
      </span>
      <span className="flex items-center gap-1">
        {running && <span className="size-1.5 rounded-full bg-foreground" aria-label="running" />}
        {app.name}
      </span>
    </>
  );
}

const ARROWS: Record<string, Direction> = { ArrowLeft: "left", ArrowRight: "right", ArrowUp: "up", ArrowDown: "down" };

/**
 * DESKTOP-GRID-1: the icons on a grid. They fill columns from the top left; a mouse or pen drags one, it follows the pointer and
 * snaps to the nearest cell on release (a free cell takes it, an occupied one swaps). Esc or a release outside cancels. Positions are
 * saved by app id in a preference. Touch does not drag (it scrolls and taps as before). Arrow keys move the focus, Enter opens.
 */
function IconGrid({ apps, running, onOpen, onAbout }: { apps: readonly AppInfo[]; running: (id: AppId) => boolean; onOpen: (id: AppId) => void; onAbout: (a: AppInfo) => void }) {
  const [raw, setRaw] = usePreference<string>(POSITIONS_KEY, "");
  const saved = useMemo(() => parsePositions(raw), [raw]);
  const box = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState<GridSize>({ cols: 1, rows: 1 });
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const measure = () => setSize((o) => { const n = gridSize(el.clientWidth, el.clientHeight); return n.cols === o.cols && n.rows === o.rows ? o : n; });
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const ids = useMemo(() => apps.map((a) => a.id as string), [apps]);
  const shown = useMemo(() => place(ids, saved, size), [ids, saved, size]);
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});
  const [drag, setDrag] = useState<{ id: string; x: number; y: number } | null>(null);
  const press = useRef<{ id: string; px: number; py: number; ox: number; oy: number; moved: boolean; pointer: number } | null>(null);
  const swallowClick = useRef(false);

  const finish = (e: { clientX: number; clientY: number } | null) => {
    const p = press.current;
    press.current = null;
    setDrag(null);
    if (!p || !p.moved) return;
    swallowClick.current = true;
    const rect = box.current?.getBoundingClientRect();
    const cell = e && rect ? cellAt(e.clientX - rect.left, e.clientY - rect.top, size) : null;
    if (!cell) return;
    const next = drop(ids, saved, size, p.id, cell);
    if (next !== saved) setRaw(serializePositions(next));
  };

  return (
    <div ref={box} data-tauri-drag-region className="absolute inset-0 overflow-hidden" role="group" aria-label="Desktop" data-testid="desktop-grid">
      {apps.map((a) => {
        const cell = shown[a.id]!;
        const dragging = drag?.id === a.id;
        const left = GRID_PAD + cell.col * CELL_W;
        const top = GRID_PAD + cell.row * CELL_H;
        return (
          <button
            key={a.id}
            ref={(el) => {
              refs.current[a.id] = el;
            }}
            type="button"
            data-testid={`desktop-icon-${a.id}`}
            data-cell={`${cell.col},${cell.row}`}
            onClick={() => {
              if (swallowClick.current) {
                swallowClick.current = false;
                return;
              }
              onOpen(a.id);
            }}
            onContextMenu={(e) => {
              e.preventDefault();
              onAbout(a);
            }}
            onKeyDown={(e) => {
              if (e.key === "Escape" && press.current) {
                press.current.moved = true;
                finish(null);
                return;
              }
              const dir = ARROWS[e.key];
              if (!dir) return;
              const to = neighbor(shown, a.id, dir);
              if (to) {
                e.preventDefault();
                refs.current[to]?.focus();
              }
            }}
            onPointerDown={(e) => {
              if (e.button !== 0 || e.pointerType === "touch") return;
              press.current = { id: a.id, px: e.clientX, py: e.clientY, ox: left, oy: top, moved: false, pointer: e.pointerId };
              e.currentTarget.setPointerCapture?.(e.pointerId);
            }}
            onPointerMove={(e) => {
              const p = press.current;
              if (!p || p.id !== a.id) return;
              const dx = e.clientX - p.px;
              const dy = e.clientY - p.py;
              if (!p.moved && Math.hypot(dx, dy) < DRAG_THRESHOLD) return;
              p.moved = true;
              setDrag({ id: a.id, x: p.ox + dx, y: p.oy + dy });
            }}
            onPointerUp={(e) => {
              if (press.current?.id === a.id) finish(e);
            }}
            onPointerCancel={() => finish(null)}
            onLostPointerCapture={() => {
              if (press.current?.id === a.id) finish(null);
            }}
            title={`${a.name} (right-click for About)`}
            style={{ left: dragging ? drag.x : left, top: dragging ? drag.y : top, width: CELL_W - 8, touchAction: "manipulation" }}
            className={cn(
              "group absolute flex flex-col items-center gap-1.5 rounded-lg p-2 text-xs select-none hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
              dragging && "z-10 cursor-grabbing opacity-80 shadow-lg",
            )}
          >
            <DesktopIcon app={a} running={running(a.id)} />
          </button>
        );
      })}
    </div>
  );
}

/** The slim title bar of an open app. Its buttons are the APP's (minimize, close); the real window's are in the Start menu. */
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

/**
 * The OS level (DESK-5): the Start button at the left end of the bottom strip. It is the strip's height (h-5 inside the h-6 strip,
 * like the taskbar entries), never taller. Its menu lists the apps, then the real window's own actions: Minimize Infantry and
 * Exit Infantry. Those two appear only where the app has a window frame to act on (the desktop app; a browser or phone has none).
 */
function StartMenu({ state, onOpen }: { state: DesktopState; onOpen: (app: AppInfo) => void }) {
  const frame = windowFrame();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="xs"
          aria-label="Start"
          data-testid="start-button"
          className="h-5 gap-1.5 rounded px-1.5 max-sm:h-12 max-sm:min-w-12 max-sm:rounded-lg max-sm:px-3"
        >
          <LayoutGrid className="size-3.5 max-sm:size-4" />
          <span className="hidden sm:inline">Start</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent side="top" align="start" className="w-auto min-w-48" data-testid="start-menu">
        <DropdownMenuLabel className="text-xs text-muted-foreground">Apps</DropdownMenuLabel>
        {listed().map((a) => {
          const Icon = a.icon;
          return (
            <DropdownMenuItem key={a.id} onSelect={() => onOpen(a)} data-testid={`start-app-${a.id}`}>
              <Icon />
              {a.name}
              {state.windows[a.id] !== "closed" && <span className="ml-auto size-1.5 rounded-full bg-foreground" aria-label="running" />}
            </DropdownMenuItem>
          );
        })}
        {frame && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={() => frame.minimize()} data-testid="start-minimize-window">
              <Minus />
              Minimize Infantry
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={() => frame.close()} data-testid="start-exit-window">
              <Power />
              Exit Infantry
            </DropdownMenuItem>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * ARMORY-2: the Updates item. It is there only while the daemon reports an installed app (or the shell) with a newer version; its
 * badge counts them and its menu lists each as "name, installed → available". Picking one opens the Apps app on that app's page.
 */
function UpdatesMenu({ store, onPick }: { store: UpdatesStore; onPick: (u: AppUpdate) => void }) {
  const updates = updatesShown(useStore(store, (s) => s.updates), osApp() !== null);
  if (updates.length === 0) return null;
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="xs"
          aria-label={`Updates (${updates.length})`}
          data-testid="updates-button"
          className="h-5 gap-1.5 rounded px-1.5 max-sm:h-12 max-sm:min-w-12 max-sm:rounded-lg max-sm:px-3"
        >
          <Download className="size-3.5 max-sm:size-4" />
          <span className="hidden sm:inline">Updates</span>
          <span data-testid="updates-badge" className="grid h-5 min-w-5 place-items-center rounded border border-foreground/40 bg-accent px-1 text-[11px] font-semibold">
            {updates.length}
          </span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent side="top" align="start" className="w-auto min-w-56" data-testid="updates-menu">
        <DropdownMenuLabel className="text-xs text-muted-foreground">Updates available</DropdownMenuLabel>
        {updates.map((u) => (
          <DropdownMenuItem key={u.id} onSelect={() => onPick(u)} data-testid={`update-${u.id}`}>
            <Download />
            <span className="font-medium">{u.name}</span>
            <span className="ml-auto pl-4 text-xs text-muted-foreground">{versionLine(u)}</span>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Along the bottom on a desktop, a dock on a phone: the Start button, then one entry per open app. Clicking a minimized entry restores it; the front one minimizes. */
function Taskbar({ state, updates, onToggle, onOpen, onOpenUpdate }: { state: DesktopState; updates: UpdatesStore | null; onToggle: (app: AppInfo) => void; onOpen: (app: AppInfo) => void; onOpenUpdate: (u: AppUpdate) => void }) {
  const open = listed().filter((a) => state.windows[a.id] !== "closed");
  return (
    <nav aria-label="Taskbar" data-testid="taskbar" className="flex h-full min-w-0 items-center gap-1">
      <StartMenu state={state} onOpen={onOpen} />
      {updates && <UpdatesMenu store={updates} onPick={onOpenUpdate} />}
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

export function Desktop({ plugin, link, Shell, View }: { plugin: string; link: FrameLink; Shell: ComponentType<ShellProps>; View: ComponentType<{ plugin: string; id: string; route?: string }> }) {
  const [openAtStart] = usePreference("desktop.openAtStart", false);
  const [state, dispatch] = useReducer(reduce, openAtStart, initialState);
  const [about, setAbout] = useState<AppInfo | null>(null);
  // Made in an effect (and disposed with it) so a remount re-subscribes to the link.
  const [updates, setUpdates] = useState<UpdatesStore | null>(null);
  useEffect(() => {
    const s = createUpdatesStore(link);
    setUpdates(s);
    return () => s.getState().dispose();
  }, [link]);
  const taskbar = (
    <Scoped plugin={plugin}>
      <Taskbar
        state={state}
        updates={updates}
        onToggle={(a) => dispatch(toggle(state, a.id))}
        onOpen={(a) => dispatch({ type: "open", app: a.id })}
        onOpenUpdate={(u) => dispatch({ type: "open", app: "apps", route: appRoute(u) })}
      />
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
      <View plugin="Infantry_Installer" id="infantry.installer" route={state.routes.apps} />
    );
  return (
    <div className="flex h-full w-full flex-col overflow-hidden bg-gradient-to-br from-background via-muted to-background">
     <div className="relative min-h-0 flex-1 overflow-hidden">
      {state.front === null && (
        <IconGrid apps={listed()} running={(id) => state.windows[id] !== "closed"} onOpen={(id) => dispatch({ type: "open", app: id })} onAbout={setAbout} />
      )}
      {listed().map(
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
