// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Daemon sessions -> the sidebar/header view model (`Agent`). One Agent per session view, cached by view identity, so
// a card whose session did not change in a flush keeps the same object and does not re-render.
import { useMemo } from "react";
import { useShallow } from "zustand/react/shallow";
import { holdOf, inProject, isRunning, withoutAssistant, type AgentProfile, type ProjectSummary, type SessionView, type TicketSummary } from "@infantry/client";
import type { Agent, AgentStatus } from "@/mock/types";
import { useDaemon } from "./daemon";
import { useProjects } from "@/components/git/use-projects";

/**
 * [ticketStatus]: the board lane of the session's ticket; a quiet session whose ticket is Pending is ready to check.
 * NEEDS-INPUT-1: `needs-input` is an agent that needs an ANSWER (WAITING_INPUT: a question or approval card is open).
 * `waiting` is the flag a worker sets with `infantry waiting on` while a build or process runs: calm, no attention.
 */
function statusOf(v: SessionView, ticketStatus?: string): AgentStatus {
  const s = v.record.state;
  const hold = holdOf(v.record);
  if (hold) return hold;
  if (isRunning(v.record)) return "working";
  if (ticketStatus === "pending") return "review";
  if (s === "FAILED" || s === "ORPHANED") return "error";
  if (s === "EXITED") return "done";
  return "idle";
}

function statusLine(v: SessionView): string {
  const r = v.record;
  if (r.heldBy) return `live in ${r.heldBy}`;
  if (r.state === "WAITING_INPUT") return "needs input";
  if (r.watchLabel) return r.watchLabel;
  if (r.waiting?.reason) return `waiting: ${r.waiting.reason}`;
  const st = v.status;
  if (isRunning(r) && st?.tool) return st.activity ? `${st.tool}: ${st.activity}` : st.tool;
  if (isRunning(r) && st?.runActivity?.label) return st.runActivity.label;
  if (r.progress?.label) return r.progress.label;
  if (r.lastError && r.state === "FAILED") return r.lastError;
  if (isRunning(r)) return st?.runActivity?.state ?? "working";
  // STUCK-RUNNING-1: at rest with tasks of the CLI's own still going reads idle, never Running.
  const bg = r.backgroundTasks?.length ?? 0;
  return bg && r.state === "IDLE" ? `idle · ${bg} background` : r.state.toLowerCase().replace(/_/g, " ");
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
  const status = statusOf(v, r.ticketId ? tickets[r.ticketId]?.status : undefined);
  return {
    id: r.id,
    name: r.displayName?.trim() || profile?.name || (r.role ? title(r.role) : "Session"),
    roleId: r.role ?? "assistant",
    sessionId: r.id.slice(0, 8),
    status,
    statusLine: statusLine(v),
    ticketId: r.ticketId ? r.ticketId.replace(/^ticket-/, "").slice(0, 8) : undefined,
    ticketTitle: r.ticketId ? tickets[r.ticketId]?.title : undefined,
    progress: r.progress && isRunning(r) ? (r.progress.percent ?? null) : undefined,
    age: ago(r.lastHeartbeat),
    activityAt: v.activityAt ?? r.lastEventAt ?? r.lastHeartbeat,
    lastLine: v.lastLine || r.lastAction || r.workingDir,
    group: status === "working" || status === "waiting" || status === "needs-input" ? "running" : "quiet",
    provider: r.provider,
    model: r.model ?? "default",
    runs: r.runCount ?? 0,
    tokensIn: `${compact(tin)} in`,
    tokensOut: `${compact(tout)} out`,
    tokensKnown: Object.keys(v.usage).length > 0,
    hue: hueOf(r.id),
    projectId: r.projectId ?? undefined,
    projectName: r.projectId ? (projects.find((p) => p.id === r.projectId)?.name ?? r.projectId) : undefined,
    heldBy: r.heldBy ?? undefined,
    backgroundTasks: r.backgroundTasks?.length ? r.backgroundTasks.map((t) => ({ id: t.id, label: t.label, startedAt: Number(t.startedAt) })) : undefined,
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

/** FIRST-SYNC-1: true until the project list has arrived and a project is selected. Lists of sessions stay empty
 *  ("Syncing") meanwhile, so no session shows under a wrong or default project and nothing jumps when projects load. */
export function useSyncing(): boolean {
  const loaded = useDaemon((s) => s.projectsLoaded);
  // Both stores: the daemon store's list and the projects store's own (which selects the current project).
  const pending = useProjects((s) => !s.loaded || (s.projects.length > 0 && !s.currentId));
  return !loaded || pending;
}

/** The daemon's host as the user typed it (for "Syncing with <host>…"). */
export function useDaemonHost(): string {
  const url = useDaemon((s) => s.connection.url);
  try { return new URL(url).hostname; } catch { return "the daemon"; }
}

/** Every session of the selected project as an Agent, newest first. The Assistant is not one of them: it lives in its own box. */
export function useAgentList(): Agent[] {
  const syncing = useSyncing();
  const { sessions, agents, tickets, projects } = useDaemon(
    useShallow((s) => ({ sessions: s.sessions, agents: s.agents, tickets: s.tickets, projects: s.projects })),
  );
  const projectId = useProjects((s) => s.currentId);
  return useMemo(
    () =>
      syncing ? [] : inProject(withoutAssistant(Object.values(sessions)), projectId)
        .sort((a, b) => b.record.spawnedAt - a.record.spawnedAt)
        .map((v) => cachedAgent(v, agents, tickets, projects)),
    [sessions, agents, tickets, projects, projectId, syncing],
  );
}

/** One session as an Agent (null when it is gone). */
export function useAgent(id: string): Agent | null {
  const { view, agents, tickets, projects } = useDaemon(
    useShallow((s) => ({ view: s.sessions[id], agents: s.agents, tickets: s.tickets, projects: s.projects })),
  );
  return view ? cachedAgent(view, agents, tickets, projects) : null;
}

/** Every session of every project as an Agent, newest first (the Global Workers board). */
export function useAllAgentList(): Agent[] {
  const { sessions, agents, tickets, projects } = useDaemon(
    useShallow((s) => ({ sessions: s.sessions, agents: s.agents, tickets: s.tickets, projects: s.projects })),
  );
  return useMemo(
    () => Object.values(sessions).sort((a, b) => b.record.spawnedAt - a.record.spawnedAt).map((v) => cachedAgent(v, agents, tickets, projects)),
    [sessions, agents, tickets, projects],
  );
}

/** CARD-STALE-1: the setting key (minutes) and its default; 0 turns the red timer off. */
export const STALE_RUNNING_KEY = "limits.ui.staleRunningMinutes";
export const STALE_RUNNING_DEFAULT = 30;

/** Minutes a Running session has gone without new activity, when that is past [minutes]; otherwise null.
 *  Only `working` (a turn actually running) counts: idle with background tasks, waiting or finished never do. */
export function staleRunningMinutes(status: Agent["status"], activityAt: number, now: number, minutes: number): number | null {
  if (status !== "working" || !(minutes > 0)) return null;
  const gone = Math.floor((now - activityAt) / 60_000);
  return gone >= minutes ? gone : null;
}
