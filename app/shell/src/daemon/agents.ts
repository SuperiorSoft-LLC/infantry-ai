// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Daemon sessions -> the sidebar/header view model (`Agent`). One Agent per session view, cached by view identity, so
// a card whose session did not change in a flush keeps the same object and does not re-render.
import { useMemo } from "react";
import { useShallow } from "zustand/react/shallow";
import { isRunning, type AgentProfile, type ProjectSummary, type SessionView, type TicketSummary } from "@infantry/client";
import type { Agent, AgentStatus } from "@/mock/types";
import { useDaemon } from "./daemon";

function statusOf(v: SessionView): AgentStatus {
  const s = v.record.state;
  if (s === "WAITING_INPUT" || v.record.waiting) return "needs-input";
  if (isRunning(v.record)) return "working";
  if (s === "FAILED" || s === "ORPHANED") return "error";
  if (s === "EXITED") return "done";
  return "idle";
}

function statusLine(v: SessionView): string {
  const r = v.record;
  if (r.heldBy) return `live in ${r.heldBy}`;
  if (r.watchLabel) return r.watchLabel;
  if (r.waiting?.reason) return `waiting: ${r.waiting.reason}`;
  const st = v.status;
  if (isRunning(r) && st?.tool) return st.activity ? `${st.tool}: ${st.activity}` : st.tool;
  if (isRunning(r) && st?.runActivity?.label) return st.runActivity.label;
  if (r.progress?.label) return r.progress.label;
  if (r.lastError && r.state === "FAILED") return r.lastError;
  return isRunning(r) ? (st?.runActivity?.state ?? "working") : r.state.toLowerCase().replace(/_/g, " ");
}

export function ago(ms: number, now = Date.now()): string {
  const s = Math.max(0, Math.round((now - ms) / 1000));
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.round(s / 60)}m`;
  if (s < 86_400) return `${Math.round(s / 3600)}h`;
  return `${Math.round(s / 86_400)}d`;
}

export function compact(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

/** A stable hue per session, so an avatar keeps its colour across restarts of the UI. */
function hueOf(id: string): number {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0;
  return h % 360;
}

function title(s: string): string {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

export function toAgent(
  v: SessionView,
  profiles: Record<string, AgentProfile>,
  tickets: Record<string, TicketSummary>,
  projects: ProjectSummary[] = [],
): Agent {
  const r = v.record;
  const profile = r.agentId ? profiles[r.agentId] : undefined;
  let tin = 0, tout = 0;
  for (const u of Object.values(v.usage)) {
    tin += u.input;
    tout += u.output;
  }
  const status = statusOf(v);
  return {
    id: r.id,
    name: profile?.name ?? (r.role ? title(r.role) : "Session"),
    roleId: r.role ?? "assistant",
    sessionId: r.id.slice(0, 8),
    status,
    statusLine: statusLine(v),
    ticketId: r.ticketId ? r.ticketId.replace(/^ticket-/, "").slice(0, 8) : undefined,
    ticketTitle: r.ticketId ? tickets[r.ticketId]?.title : undefined,
    progress: r.progress && isRunning(r) ? (r.progress.percent ?? null) : undefined,
    age: ago(r.lastHeartbeat),
    lastLine: v.lastLine || r.workingDir,
    group: status === "working" || status === "needs-input" ? "running" : "quiet",
    provider: r.provider,
    model: r.model ?? "default",
    runs: r.runCount ?? 0,
    tokensIn: `${compact(tin)} in`,
    tokensOut: `${compact(tout)} out`,
    hue: hueOf(r.id),
    projectName: r.projectId ? (projects.find((p) => p.id === r.projectId)?.name ?? r.projectId) : undefined,
    heldBy: r.heldBy ?? undefined,
  };
}

const cache = new WeakMap<SessionView, { profiles: unknown; tickets: unknown; projects: unknown; agent: Agent }>();

function cachedAgent(
  v: SessionView,
  profiles: Record<string, AgentProfile>,
  tickets: Record<string, TicketSummary>,
  projects: ProjectSummary[],
): Agent {
  const hit = cache.get(v);
  if (hit && hit.profiles === profiles && hit.tickets === tickets && hit.projects === projects) return hit.agent;
  const agent = toAgent(v, profiles, tickets, projects);
  cache.set(v, { profiles, tickets, projects, agent });
  return agent;
}

/** Every session as an Agent, newest first. */
export function useAgentList(): Agent[] {
  const { sessions, agents, tickets, projects } = useDaemon(
    useShallow((s) => ({ sessions: s.sessions, agents: s.agents, tickets: s.tickets, projects: s.projects })),
  );
  return useMemo(
    () =>
      Object.values(sessions)
        .sort((a, b) => b.record.spawnedAt - a.record.spawnedAt)
        .map((v) => cachedAgent(v, agents, tickets, projects)),
    [sessions, agents, tickets, projects],
  );
}

/** One session as an Agent (null when it is gone). */
export function useAgent(id: string): Agent | null {
  const { view, agents, tickets, projects } = useDaemon(
    useShallow((s) => ({ view: s.sessions[id], agents: s.agents, tickets: s.tickets, projects: s.projects })),
  );
  return view ? cachedAgent(view, agents, tickets, projects) : null;
}
