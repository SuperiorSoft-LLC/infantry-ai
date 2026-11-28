// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Every floating chat in the window: the Assistant box (top-bar button) and any session popped out from its header.
// All of them are a ChatPopout around the one ChatPanel.
import { useEffect, useRef, useState } from "react";
import { Button, Chip } from "@infantry/ui";
import { MessageSquare } from "lucide-react";
import { dockItems, LocalWindowManager, onPopoutWindowsChange, openSessionFloat, POPOUT_OS_TIMEOUT_MS, popoutWindows, type WindowInfo, type Windows } from "@infantry/client";
import { useDaemon } from "@/daemon/daemon";
import { host } from "@/host";
import { useAgent } from "@/daemon/agents";
import { useUi } from "@/state/ui-state";
import { ChatPopout, savedFloatBounds } from "./chat-popout";
import { useAssistantAsk, useAssistantId } from "./use-assistant";

/** What the box says while there is no Assistant to show: the link is down, the daemon refused, or the Assistant is on its way. */
function assistantEmpty(online: boolean, refusal: string | null, retry: () => void) {
  if (!online) return { title: "Not connected to the daemon", hint: "The app reconnects on its own." };
  if (refusal)
    return {
      title: "The Assistant did not start",
      hint: refusal,
      action: (
        <Button variant="outline" size="sm" onClick={retry}>
          Try again
        </Button>
      ),
    };
  return { title: "Starting the Assistant…", hint: "It opens here as soon as the daemon has it ready." };
}

/**
 * The Assistant box. The daemon makes the one installation-wide Assistant (`record.global`) when the box asks for it, and it
 * lives here: it is no card of the agents lists (`useAgentList` leaves it out), and with none yet the box has no composer,
 * so nothing typed here can start an ordinary agent instead.
 */
function AssistantBox({ z, onFocus }: { z: number; onFocus: () => void }) {
  const ui = useUi();
  const id = useAssistantId();
  const assistant = useAgent(id ?? "");
  const online = useDaemon((s) => s.connection.state === "open");
  const { refusal, ask } = useAssistantAsk();
  // Opening the box asks the daemon for the Assistant (it makes it on the first ask and pushes it as a `session` frame).
  useEffect(() => {
    if (online && !id) ask();
  }, [online, id, ask]);
  return (
    <ChatPopout
      id="assistant"
      title="Assistant"
      sessionId={id}
      sessionRequired
      badge={assistant && <Chip tone="green">{`${assistant.provider} · ${assistant.model}`}</Chip>}
      z={z}
      onFocus={onFocus}
      onClose={() => ui.set("assistantOpen", false)}
      empty={assistantEmpty(online, refusal, ask)}
    />
  );
}

/**
 * FLOAT-ADOPT-1: how a session pop-out is drawn. Under BattalionOS with os_win_* the OS draws it as a float window in this
 * webview (its chrome: minimize to the BOS taskbar, maximize, snap, "Pop out to its own window"), so the app draws nothing;
 * the OS closing it clears the popout. Standalone, in a browser, or when the OS refuses (BOS older than 0.2.0): the inline
 * ChatPopout (whose Minimize then shows the one-time update notice).
 */
/** Opens the session's OS float window; "os" when the OS drew it, "inline" when there is no OS window manager or it refused. */
export async function openOsFloat(sessionId: string, title: string, onClosed: () => void, loc: { pathname: string; search: string } = window.location): Promise<"os" | "inline"> {
  try {
    const ws = await popoutWindows();
    if (ws.backend.name !== "os") return "inline";
    const open = openSessionFloat(ws, { sessionId, title, pathname: loc.pathname, search: loc.search, bounds: savedFloatBounds(`session.${sessionId}`) }, onClosed);
    await new Promise<void>((ok, fail) => {
      const t = setTimeout(() => fail(new Error("no answer from the window manager")), POPOUT_OS_TIMEOUT_MS);
      open.then(() => (clearTimeout(t), ok()), (e) => (clearTimeout(t), fail(e)));
    });
    return "os";
  } catch {
    return "inline";
  }
}

export function useOsFloat(sessionId: string, title: string, onClosed: () => void): "pending" | "os" | "inline" {
  const [mode, setMode] = useState<"pending" | "os" | "inline">(host.os ? "pending" : "inline");
  const closedRef = useRef(onClosed);
  closedRef.current = onClosed;
  useEffect(() => {
    if (!host.os) return;
    let live = true;
    void openOsFloat(sessionId, title, () => live && closedRef.current()).then((m) => live && setMode(m));
    // The OS window outlives a re-render; the OS closes it, and its close event clears the popout.
    return () => void (live = false);
    // The title is the window's at open; a later rename does not reopen it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);
  return mode;
}

function SessionPopout({ sessionId, z, onFocus }: { sessionId: string; z: number; onFocus: () => void }) {
  const ui = useUi();
  const agent = useAgent(sessionId);
  const title = agent ? `${agent.name} · ${agent.sessionId}` : "Chat";
  const mode = useOsFloat(sessionId, title, () => ui.closePopout(sessionId));
  // A session that is gone closes its window.
  useEffect(() => {
    if (!agent) ui.closePopout(sessionId);
  }, [agent, sessionId, ui]);
  if (mode !== "inline") return null;
  return (
    <ChatPopout
      id={`session.${sessionId}`}
      title={title}
      attention={agent?.status === "needs-input"}
      sessionId={sessionId}
      z={z}
      onFocus={onFocus}
      onClose={() => ui.closePopout(sessionId)}
    />
  );
}

/**
 * WINDOW-MIN-1: the in-app dock, the taskbar of the local window manager (standalone app, browser): one item per minimized
 * floating chat along the bottom edge; a click restores it. Under BattalionOS the OS taskbar shows the items and this is empty.
 * DOCK-CLIP-1: it is a flex row of the shell column (AppShell mounts it above the status bar), never an overlay, so it takes
 * real height and the composer above it is never covered; with no minimized window it renders nothing.
 */
export function PopoutDock() {
  // MIN-TASKBAR-1: under BattalionOS (any version) the OS taskbar is the only place a minimized chat goes.
  if (host.os) return null;
  return <LocalDock />;
}

function LocalDock() {
  const [wm, setWm] = useState<LocalWindowManager | null>(null);
  const [items, setItems] = useState<WindowInfo[]>([]);
  useEffect(() => {
    let off = () => {};
    let gone = false;
    const attach = () =>
      void popoutWindows().then((ws: Windows) => {
        if (gone || !(ws.backend instanceof LocalWindowManager)) return;
        off();
        const local = ws.backend;
        setWm(local);
        const read = () => setItems(dockItems(local.windows()));
        read();
        off = local.onChange(read);
      });
    attach();
    // WINDOW-MIN-2: the OS window manager did not answer and the in-app dock took over.
    const offSwitch = onPopoutWindowsChange(attach);
    return () => {
      gone = true;
      off();
      offSwitch();
    };
  }, []);
  if (!wm || items.length === 0) return null;
  return (
    <nav aria-label="Minimized windows" data-popout-dock className="flex h-10 shrink-0 items-center gap-1 overflow-x-auto border-t bg-card px-2">
      {items.map((w) => (
        <Button key={w.wid} data-wid={w.wid} variant="outline" size="sm" className={w.attention ? "max-w-48 border-red-500" : "max-w-48"} title={w.title} onClick={() => void wm.call("os_win_focus", { wid: w.wid })}>
          <MessageSquare className="size-3.5 shrink-0" />
          <span className="truncate">{w.title || w.key}</span>
          {w.badge?.count ? <Chip tone="red">{String(w.badge.count)}</Chip> : w.badge?.text ? <Chip>{w.badge.text}</Chip> : null}
        </Button>
      ))}
    </nav>
  );
}

export function PopoutHost() {
  const ui = useUi();
  // Click-to-front: the last focused window gets the highest z.
  const [order, setOrder] = useState<string[]>([]);
  const front = (k: string) => setOrder((o) => (o[o.length - 1] === k ? o : [...o.filter((x) => x !== k), k]));
  const z = (k: string) => 40 + Math.max(0, order.indexOf(k));
  return (
    <>
      {ui.assistantOpen && <AssistantBox z={z("assistant")} onFocus={() => front("assistant")} />}
      {ui.popouts.map((id) => (
        <SessionPopout key={id} sessionId={id} z={z(id)} onFocus={() => front(id)} />
      ))}
    </>
  );
}
