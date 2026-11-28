// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #8: the chat pieces the Kotlin chat had: the agent's to-do box (in the transcript flow, collapsible, the
// collapsed state kept per session), the report-back chip (session header badges), Start / Resume in an empty pane (the
// existing `session_restart` and resume `new` frames), and the generic `chat.message.actions` plugin slot under a message.
import { memo, useState } from "react";
import { CheckCircle2, ChevronDown, ChevronRight, Circle, CircleDot, ListTodo, Play, RotateCcw } from "lucide-react";
import type { SessionRecord, TodoItem, TranscriptItem } from "@infantry/client";
import { Button, Chip, cn } from "@infantry/ui";
import { PluginSlot, useSlotFills } from "@/plugins/web-plugins";

// ---- to-do box ----

const COLLAPSED_KEY = (sessionId: string) => `infantry.chat.todos.collapsed.${sessionId}`;

function readCollapsed(sessionId: string): boolean {
  try {
    return globalThis.localStorage?.getItem(COLLAPSED_KEY(sessionId)) === "1";
  } catch {
    return false;
  }
}

function writeCollapsed(sessionId: string, collapsed: boolean): void {
  try {
    if (collapsed) globalThis.localStorage?.setItem(COLLAPSED_KEY(sessionId), "1");
    else globalThis.localStorage?.removeItem(COLLAPSED_KEY(sessionId));
  } catch {
    /* storage unavailable: the box just opens expanded next time */
  }
}

/** "2/5 done". */
export const todoSummary = (todos: readonly TodoItem[]): string => `${todos.filter((t) => t.status === "completed").length}/${todos.length} done`;

function TodoIcon({ status }: { status: string }) {
  if (status === "completed") return <CheckCircle2 className="size-3.5 shrink-0 text-ok" aria-label="done" />;
  if (status === "in_progress") return <CircleDot className="size-3.5 shrink-0 text-info" aria-label="in progress" />;
  return <Circle className="size-3.5 shrink-0 text-muted-foreground" aria-label="pending" />;
}

/** The agent's to-do list; nothing when the session has none. */
export const TodoBox = memo(function TodoBox({ sessionId, todos }: { sessionId: string; todos: readonly TodoItem[] | undefined }) {
  const [collapsed, setCollapsed] = useState(() => readCollapsed(sessionId));
  const [forSession, setForSession] = useState(sessionId);
  if (forSession !== sessionId) {
    setForSession(sessionId);
    setCollapsed(readCollapsed(sessionId));
  }
  if (!todos || todos.length === 0) return null;
  const toggle = () => {
    writeCollapsed(sessionId, !collapsed);
    setCollapsed(!collapsed);
  };
  return (
    <section className="mx-4 mt-2 rounded-md border bg-card text-xs" aria-label="Agent to-do list" data-testid="todo-box">
      <button type="button" className="flex w-full items-center gap-1.5 px-2.5 py-1.5 text-left" aria-expanded={!collapsed} onClick={toggle}>
        {collapsed ? <ChevronRight className="size-3.5" /> : <ChevronDown className="size-3.5" />}
        <ListTodo className="size-3.5 text-muted-foreground" />
        <span className="font-medium">To-do</span>
        <span className="text-muted-foreground tabular-nums">{todoSummary(todos)}</span>
      </button>
      {!collapsed && (
        <ul className="max-h-40 space-y-0.5 overflow-y-auto px-2.5 pb-2">
          {todos.map((t, i) => (
            <li key={i} className="flex items-start gap-1.5" data-status={t.status}>
              <span className="mt-px">
                <TodoIcon status={t.status} />
              </span>
              <span className={cn("min-w-0 break-words", t.status === "completed" && "text-muted-foreground line-through")}>
                {t.status === "in_progress" && t.activeText ? t.activeText : t.text}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
});

// ---- report-back chip ----

/** The REPORT BACK flag of a session: `unreported` in the attention colour, `reported` muted, nothing otherwise. */
export function ReportChip({ report }: { report: SessionRecord["report"] | undefined }) {
  const status = report?.status;
  if (status === "unreported")
    return (
      <span title="This agent has not reported back yet" data-testid="report-chip">
        <Chip tone="amber">unreported</Chip>
      </span>
    );
  if (status === "reported")
    return (
      <span title="This agent reported back" data-testid="report-chip">
        <Chip>reported</Chip>
      </span>
    );
  return null;
}

// ---- Start / Resume in an empty pane ----

export type PaneAction = "start" | "resume" | null;

/**
 * What an empty pane offers: Resume when the session has a conversation and this window can resume it (the embedded
 * runner); Start when it has none; nothing while a turn runs.
 */
export function paneActionOf(record: Pick<SessionRecord, "providerSessionId" | "turnActive" | "state"> | undefined, canResume: boolean): PaneAction {
  if (!record || record.turnActive || record.state === "BUSY" || record.state === "STARTING") return null;
  if (record.providerSessionId) return canResume ? "resume" : null;
  return "start";
}

export function EmptyPaneActions({ action, onStart, onResume }: { action: PaneAction; onStart: () => void; onResume: () => void }) {
  if (action === "resume")
    return (
      <Button size="sm" variant="outline" onClick={onResume} data-testid="session-resume">
        <RotateCcw /> Resume
      </Button>
    );
  if (action === "start")
    return (
      <Button size="sm" variant="outline" onClick={onStart} data-testid="session-start">
        <Play /> Start session
      </Button>
    );
  return null;
}

// ---- message actions slot ----

type MessageItem = Extract<TranscriptItem, { kind: "text" | "user" | "comms" }>;
const MESSAGE_KINDS = new Set<TranscriptItem["kind"]>(["text", "user", "comms"]);

/** The props a `chat.message.actions` view gets for [item], or null for an item that is not a message. */
export function messageActionProps(sessionId: string, item: TranscriptItem) {
  if (!MESSAGE_KINDS.has(item.kind)) return null;
  const m = item as MessageItem;
  return { sessionId, message: { id: m.id, kind: m.kind, text: m.text, ...(m.kind === "comms" ? { from: m.from } : {}) } };
}

/** Plugin buttons under one chat message (the `chat.message.actions` slot); nothing when no plugin fills it. */
export function MessageActions({ sessionId, item }: { sessionId: string; item: TranscriptItem }) {
  const fills = useSlotFills("chat.message.actions");
  const props = fills.length ? messageActionProps(sessionId, item) : null;
  if (!props) return null;
  return (
    <div className="mt-1 flex flex-wrap items-center gap-1" data-testid="message-actions">
      <PluginSlot fills={fills} props={props} frame="badge" />
    </div>
  );
}
