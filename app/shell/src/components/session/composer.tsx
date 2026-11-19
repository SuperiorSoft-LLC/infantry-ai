// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Prompt composer. Idle session: Enter sends the prompt. Busy session: red border, Enter queues the text as its next
// turn; the queued turns are listed above with remove / send-now. No session: Enter starts a new one.
// Stop interrupts the running turn; Swap agent and Restart session open the session dialogs. The others are still mock.
// Typing `/` lists the session provider's commands (`command_catalog`, fetched once per session) by prefix: Up/Down
// move, Tab or Enter completes, Esc closes.
import { useEffect, useMemo, useState } from "react";
import { ArrowLeftRight, Play, CircleX, Trash2, RotateCw, Square, SendHorizontal, X, Zap } from "lucide-react";
import { cn } from "@infantry/ui";
import { Textarea } from "@infantry/ui";
import { IconButton } from "@infantry/ui";
import { matchSlash, slashCatalog, slashPrefix, type QueuedTurnView, type SlashCommand } from "@infantry/client";
import { useShallow } from "zustand/react/shallow";
import { daemonStore, useDaemon } from "@/daemon/daemon";
import { useUi } from "@/state/ui-state";
import { openSessionDialog } from "./session-actions";

const tools = [
  { label: "Swap agent", icon: ArrowLeftRight },
  { label: "Resume", icon: Play },
  { label: "Cancel turn", icon: CircleX },
  { label: "Delete session", icon: Trash2, danger: true },
  { label: "Restart session", icon: RotateCw },
];

const NO_QUEUE: QueuedTurnView[] = [];

export function Composer({ sessionId, busy }: { sessionId: string | null; busy: boolean }) {
  const [text, setText] = useState("");
  const ui = useUi();
  const actions: Record<string, (() => void) | undefined> = {
    Resume: () => ui.set("dialog", "resume"),
    "Cancel turn": sessionId && busy ? () => interrupt(sessionId) : undefined,
    "Swap agent": sessionId ? () => openSessionDialog("swap", sessionId) : undefined,
    "Restart session": sessionId ? () => openSessionDialog("restart", sessionId) : undefined,
    "Delete session": sessionId ? () => openSessionDialog("delete", sessionId) : undefined,
  };
  const queued = useDaemon((s) => (sessionId ? s.sessions[sessionId]?.record.queued : undefined)) ?? NO_QUEUE;
  const online = useDaemon((s) => s.connection.state === "open");
  // RUST-MIGRATE-1: a session live in the other app is never run here too (the daemon refuses it as well).
  const heldBy = useDaemon((s) => (sessionId ? s.sessions[sessionId]?.record.heldBy : undefined)) ?? undefined;
  // Actions are stable: picking them shallowly keeps the composer out of every streaming flush.
  const { send, spawn, interrupt, unqueue, forceQueued } = useDaemon(
    useShallow((s) => ({ send: s.send, spawn: s.spawn, interrupt: s.interrupt, unqueue: s.unqueue, forceQueued: s.forceQueued })),
  );

  const [catalog, setCatalog] = useState<SlashCommand[]>([]);
  const [pick, setPick] = useState(0);
  const [hideSlash, setHideSlash] = useState(false);
  useEffect(() => {
    setCatalog([]);
    if (!sessionId || !online) return;
    let live = true;
    void slashCatalog(daemonStore().getState(), sessionId).then((c) => live && setCatalog(c));
    return () => {
      live = false;
    };
  }, [sessionId, online]);
  const prefix = slashPrefix(text);
  const matches = useMemo(() => (prefix === null || hideSlash ? [] : matchSlash(catalog, prefix)), [catalog, prefix, hideSlash]);
  const at = Math.min(pick, Math.max(0, matches.length - 1));
  const complete = (c: SlashCommand) => (setText(`/${c.name}${c.argument || c.values?.length ? " " : ""}`), setPick(0));

  const submit = () => {
    if (!text.trim() || !online || heldBy) return;
    if (sessionId) send(sessionId, text);
    else spawn(text);
    setText("");
  };

  return (
    <div className="px-4 pb-3">
      {sessionId && queued.length > 0 && (
        <ul aria-label="Queued turns" className="mb-2 space-y-1">
          {queued.map((q) => (
            <li key={q.id} className="flex items-center gap-2 rounded-lg border border-dashed bg-card px-3 py-1 text-xs">
              <span className="shrink-0 text-muted-foreground">queued</span>
              <span className="min-w-0 flex-1 truncate">{q.text}</span>
              <IconButton label="Send now" className="size-6" onClick={() => forceQueued(sessionId, q.id)}>
                <Zap />
              </IconButton>
              <IconButton label="Remove from queue" className="size-6" onClick={() => unqueue(sessionId, q.id)}>
                <X />
              </IconButton>
            </li>
          ))}
        </ul>
      )}
      {matches.length > 0 && (
        <ul aria-label="Commands" role="listbox" className="mb-2 max-h-48 space-y-1 overflow-auto">
          {matches.map((c, i) => (
            <li
              key={c.name}
              role="option"
              aria-selected={i === at}
              onMouseDown={(e) => (e.preventDefault(), complete(c))}
              className={cn("flex cursor-pointer items-center gap-2 rounded-lg border bg-card px-3 py-1 text-xs", i === at && "border-ring")}
            >
              <span className="shrink-0 font-mono">/{c.name}</span>
              {c.argument && <span className="shrink-0 text-muted-foreground">{c.argument}</span>}
              <span className="min-w-0 flex-1 truncate text-muted-foreground">{c.description}</span>
            </li>
          ))}
        </ul>
      )}
      <div className={cn("rounded-xl border-2 bg-card transition-colors", busy ? "border-danger" : "border-border focus-within:border-ring")}>
        <Textarea
          value={text}
          onChange={(e) => (setText(e.target.value), setHideSlash(false))}
          onKeyDown={(e) => {
            if (matches.length > 0) {
              if (e.key === "ArrowDown" || e.key === "ArrowUp") {
                e.preventDefault();
                setPick((at + (e.key === "ArrowDown" ? 1 : matches.length - 1)) % matches.length);
                return;
              }
              if (e.key === "Tab" || (e.key === "Enter" && !e.shiftKey && prefix !== matches[at].name)) {
                e.preventDefault();
                complete(matches[at]);
                return;
              }
              if (e.key === "Escape") {
                e.preventDefault();
                setHideSlash(true);
                return;
              }
            }
            if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              submit();
            }
          }}
          aria-label="Prompt"
          disabled={!!heldBy}
          placeholder={
            heldBy
              ? `Live in ${heldBy}: resume it there, or stop it there first`
              : !sessionId
              ? "Start a new session..."
              : busy
                ? "Agent is busy. Enter to queue: sent as its next turn when this one ends"
                : "Message the agent..."
          }
          className="min-h-20 resize-none border-0 bg-transparent px-4 py-3 text-[15px] shadow-none focus-visible:ring-0 dark:bg-transparent"
        />
        <div className="flex items-center justify-between gap-2 px-2 pb-2">
          <div role="toolbar" aria-label="Session actions" className="flex items-center gap-1 rounded-lg border border-danger/60 p-1">
            {tools.map(({ label, icon: Icon, danger }) => (
              <IconButton
                key={label}
                label={label}
                className={cn("rounded-full", danger && "bg-danger text-white hover:bg-danger/85 hover:text-white")}
                onClick={actions[label]}
              >
                <Icon />
              </IconButton>
            ))}
          </div>
          <div className="flex items-center gap-1 rounded-lg border border-danger/60 p-1">
            <IconButton label="Stop" className="text-danger hover:text-danger" disabled={!busy || !sessionId || !online} onClick={() => sessionId && interrupt(sessionId)}>
              <Square className="fill-current" />
            </IconButton>
            <IconButton label={busy ? "Queue message" : "Send"} className="text-danger hover:text-danger" disabled={!text.trim() || !online || !!heldBy} onClick={submit}>
              <SendHorizontal />
            </IconButton>
          </div>
        </div>
      </div>
    </div>
  );
}
