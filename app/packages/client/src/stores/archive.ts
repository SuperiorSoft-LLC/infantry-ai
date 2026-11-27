// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// ARCHIVE-RUST-2: the ticket archive store. `archive_query` (list | search) fills the Archive view; `archive_apply`
// (archive | restore, optionally a dry run) answers through a per-call callback. Both are answered with `archive_report`
// (`text`, `items`, `count`). A real move also pushes `tickets`, so the board store updates without a reload.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";
import type { FrameLink } from "./link";

/** One archived ticket as `archive_report.items` carries it. */
export interface ArchiveItem {
  id: string;
  title: string;
  status: string;
  projectId?: string;
  epicId?: string;
  epicName?: string;
  archivedAt: number;
  updatedAt: number;
  branch?: string;
}

export interface ArchiveReport {
  text: string;
  items: ArchiveItem[];
  count: number;
}

/** The `archive_apply` body: ids, an epic, or a lane filter (status + olderThanDays). */
export interface ArchiveApply {
  op: "archive" | "restore";
  ids?: string[];
  epicId?: string;
  projectId?: string;
  status?: string;
  olderThanDays?: number;
  dryRun?: boolean;
}

export type ArchiveAnswer = { ok: true; report: ArchiveReport } | { ok: false; error: string };

export interface ArchiveState {
  items: ArchiveItem[];
  /** The archive total for the scope (the `count` of the last list). */
  count: number;
  loaded: boolean;
  loading: boolean;
  refusal: string | null;
}

export interface ArchiveActions {
  /** List (blank [text]) or search the archive of [projectId] (none = every project). */
  load(text?: string, projectId?: string | null): void;
  /** Archive or restore; [done] gets the report (or the refusal). A real move re-reads the list. */
  apply(req: ArchiveApply, done?: (a: ArchiveAnswer) => void): void;
  dispose(): void;
}

export type ArchiveStore = StoreApi<ArchiveState & ArchiveActions>;

const LIMIT = 200;

type Pending = { kind: "list" } | { kind: "apply"; done?: (a: ArchiveAnswer) => void; real: boolean };

function report(raw: unknown): ArchiveReport | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Partial<ArchiveReport>;
  return { text: typeof r.text === "string" ? r.text : "", items: Array.isArray(r.items) ? r.items : [], count: typeof r.count === "number" ? r.count : 0 };
}

export function createArchiveStore(link: FrameLink): ArchiveStore {
  let counter = 0;
  /** In-flight requests: a list, or an apply with its callback. */
  const inflight = new Map<string, Pending>();
  let last: { text: string; projectId?: string | null } = { text: "" };
  return createStore<ArchiveState & ArchiveActions>()((set, get) => {
    const send = (type: string, body: unknown, entry: Pending): string | null => {
      counter += 1;
      const requestId = `archive:${Date.now().toString(36)}-${counter.toString(36)}`;
      inflight.set(requestId, entry);
      if (link.sendFrame({ type, data: JSON.stringify(body), requestId })) return requestId;
      inflight.delete(requestId);
      return null;
    };
    const off = link.onFrame((env: Envelope) => {
      const rid = env.requestId;
      const entry = rid ? inflight.get(rid) : undefined;
      if (!entry || (env.type !== "archive_report" && env.type !== "error")) return;
      inflight.delete(rid as string);
      if (entry.kind === "list") {
        if (env.type === "error") return set({ refusal: env.data, loading: false, loaded: true });
        const r = report(json<unknown>(env.data));
        set(r ? { items: r.items, count: r.count, loaded: true, loading: false, refusal: null } : { loading: false, loaded: true, refusal: "archive_query: the daemon's answer was not understood" });
        return;
      }
      if (env.type === "error") return entry.done?.({ ok: false, error: env.data });
      const r = report(json<unknown>(env.data));
      entry.done?.(r ? { ok: true, report: r } : { ok: false, error: "archive_apply: the daemon's answer was not understood" });
      if (r && entry.real && get().loaded) get().load(last.text, last.projectId);
    });
    return {
      items: [],
      count: 0,
      loaded: false,
      loading: false,
      refusal: null,
      load(text = "", projectId) {
        last = { text, projectId };
        const q = text.trim();
        const body = { op: q ? "search" : "list", ...(q ? { text: q } : {}), ...(projectId ? { projectId } : {}), limit: LIMIT };
        set({ loading: true });
        if (!send("archive_query", body, { kind: "list" })) set({ loading: false, refusal: "archive_query not sent: the daemon link is down" });
      },
      apply(req, done) {
        if (!send("archive_apply", req, { kind: "apply", done, real: !req.dryRun })) done?.({ ok: false, error: "archive_apply not sent: the daemon link is down" });
      },
      dispose: off,
    };
  });
}
