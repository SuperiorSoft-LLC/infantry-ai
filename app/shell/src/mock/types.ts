// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Typed shapes for every mock the GUI template renders.
// When the daemon is wired in, these become the API types; components only read them.

export type AgentStatus = "idle" | "working" | "needs-input" | "review" | "done" | "error";
export type { Tone, NamedRow } from "@infantry/ui";

export interface Role {
  id: string;
  label: string;
  /** lucide icon name, resolved in agents/role-icon.tsx */
  icon: RoleIconName;
}

export type RoleIconName =
  | "architect"
  | "assistant"
  | "backend"
  | "frontend"
  | "fullstack"
  | "pm"
  | "qa"
  | "automation"
  | "devops";

export interface Agent {
  id: string;
  name: string;
  roleId: string;
  sessionId: string;
  status: AgentStatus;
  statusLine: string;
  ticketId?: string;
  ticketTitle?: string;
  /** 0-100, or null when indeterminate (striped bar) */
  progress?: number | null;
  age: string;
  /** When the agent last made any event (ms); the card shows it live. */
  activityAt: number;
  lastLine: string;
  group: "running" | "quiet";
  provider: string;
  model: string;
  runs: number;
  tokensIn: string;
  tokensOut: string;
  /** False when no `usage` frame reached this window for the session: the totals are unknown, not zero. */
  tokensKnown: boolean;
  /** avatar gradient hue */
  hue: number;
  /** The session's project, by name (RUST-MIGRATE-1). */
  projectName?: string;
  /** The other app running this session now ("Infantry (Kotlin)"): shown, and a turn into it is blocked. */
  heldBy?: string;
}

export interface DiffLine {
  oldNo?: number;
  newNo?: number;
  kind: "add" | "del" | "ctx";
  text: string;
}

export type TranscriptItem =
  | { kind: "thinking"; id: string; count: number; text: string }
  | { kind: "edit"; id: string; path: string; added: number; removed: number; lines: DiffLine[] }
  | { kind: "comms"; id: string; time: string; owner?: boolean; body: CommsBody }
  | { kind: "finished"; id: string; outcome: "succeeded" | "failed"; exitCode: number }
  | { kind: "note"; id: string; text: string }
  | { kind: "spinner"; id: string; label: string; elapsed: string };

export interface CommsBody {
  paragraphs: string[];
  bullets?: { lead: string; text: string }[];
  outro?: string;
}

export interface PlanLimit {
  label: string;
  percent: number;
  resets: string;
  underPace: string;
}

export interface ProviderUsage {
  id: string;
  name: string;
  signedIn: boolean;
  limits: PlanLimit[];
  note?: string;
}

export interface PacingProvider {
  id: string;
  name: string;
  percent?: number;
  projected?: string;
  detail: string;
  updated?: string;
  workers: number;
  limit: string;
  freeSlot: boolean;
  warning?: string;
  setLabel: "Type %" | "Set %";
}

export interface AttentionItem {
  id: string;
  agentId: string;
  kind: "question" | "review" | "error" | "permission";
  title: string;
  detail: string;
  age: string;
}

export interface Ticket {
  id: string;
  title: string;
  kind: "feature" | "bug" | "plan" | "chore";
  roleId: string;
  epic?: string;
  assignee?: string;
}

export interface BoardColumn {
  id: string;
  title: string;
  tickets: Ticket[];
}

export interface Plugin {
  id: string;
  name: string;
  version: string;
  author: string;
  description: string;
  tier: "official" | "community";
  enabled: boolean;
  updateAvailable?: string;
}


export interface Project {
  id: string;
  name: string;
}
