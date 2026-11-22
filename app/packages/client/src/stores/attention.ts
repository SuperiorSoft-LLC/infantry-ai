// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The attention store: what wants the owner. Attention items (`attention_list` -> `attention_items`, the `attention`
// push, `attention_ack`), owner questions (`question_list` -> `questions`, the `question` push, `question_answer`) and
// the notification bus history (`notification_list` -> `notifications`, `notification_push`, `notification_dismiss`).
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";

export interface AttentionItem {
  id: string;
  at: number;
  kind: string;
  triggerId: string | null;
  ticketId: string | null;
  podId: string | null;
  sessionId: string | null;
  outcome: string;
  detail: string;
  ackedAt: number | null;
}

export interface OwnerQuestion {
  id: string;
  projectId?: string | null;
  sessionId: string;
  agentId?: string | null;
  role?: string | null;
  question: string;
  options: { label: string; description?: string }[];
  recommended: string;
  reason: string;
  allowOther?: boolean;
  defaultAfterMin?: number | null;
  state: string;
  answer?: string | null;
  otherText?: string | null;
  createdAt: number;
  resolvedAt?: number | null;
}

export interface NotificationAction {
  id: string;
  label: string;
}

export interface BusNotification {
  id: string;
  title: string;
  body?: string;
  level?: string;
  source?: string;
  actions?: NotificationAction[];
  at?: number;
}

export interface AttentionLink {
  sendFrame(env: Envelope): boolean;
  onFrame(listener: (env: Envelope) => void): () => void;
}

export interface AttentionState {
  items: Record<string, AttentionItem>;
  questions: Record<string, OwnerQuestion>;
  notifications: BusNotification[];
  loaded: boolean;
  refusal: string | null;
}

export interface AttentionActions {
  ensureLoaded(): void;
  ack(id: string): void;
  answer(questionId: string, answer: string | null, otherText?: string): boolean;
  dismiss(notificationId: string): void;
  /** A notice from this app itself, not the daemon bus (RUST-UPD-1: "update available"); `source` becomes `app`. */
  post(n: BusNotification): void;
  act(notificationId: string, actionId: string): void;
  clearRefusal(): void;
  dispose(): void;
}

export type AttentionStore = StoreApi<AttentionState & AttentionActions>;

/** The `source` of a notice the app posted itself: kept across a daemon list refresh, dismissed without a frame. */
export const APP_SOURCE = "app";

export function appNotes(list: BusNotification[]): BusNotification[] {
  return list.filter((n) => n.source === APP_SOURCE);
}

/** How many bus notifications the panel keeps. */
export const NOTIFICATIONS_KEPT = 50;

export function openItems(items: Record<string, AttentionItem>): AttentionItem[] {
  return Object.values(items)
    .filter((i) => i.ackedAt == null)
    .sort((a, b) => b.at - a.at);
}

export function openQuestions(qs: Record<string, OwnerQuestion>): OwnerQuestion[] {
  return Object.values(qs)
    .filter((q) => q.state === "open")
    .sort((a, b) => a.createdAt - b.createdAt);
}

/** The badge count: open items plus open questions. */
export function attentionCount(s: Pick<AttentionState, "items" | "questions">): number {
  return openItems(s.items).length + openQuestions(s.questions).length;
}

export function createAttentionStore(link: AttentionLink): AttentionStore {
  return createStore<AttentionState & AttentionActions>()((set, get) => {
    const putNote = (n: BusNotification): void => {
      const rest = get().notifications.filter((x) => x.id !== n.id);
      set({ notifications: [{ ...n, at: n.at ?? Date.now() }, ...rest].slice(0, NOTIFICATIONS_KEPT) });
    };
    const off = link.onFrame((env) => {
      switch (env.type) {
        case "attention_items": {
          const list = json<AttentionItem[]>(env.data);
          if (Array.isArray(list)) set({ items: Object.fromEntries(list.map((i) => [i.id, i])), loaded: true });
          return;
        }
        case "attention": {
          const i = json<AttentionItem>(env.data);
          if (i?.id) set({ items: { ...get().items, [i.id]: i } });
          return;
        }
        case "questions": {
          const list = json<OwnerQuestion[]>(env.data);
          if (Array.isArray(list)) set({ questions: Object.fromEntries(list.map((q) => [q.id, q])) });
          return;
        }
        case "question": {
          const q = json<OwnerQuestion>(env.data);
          if (q?.id) set({ questions: { ...get().questions, [q.id]: q } });
          return;
        }
        case "notifications": {
          const list = json<BusNotification[]>(env.data);
          if (Array.isArray(list)) set({ notifications: [...appNotes(get().notifications), ...list.slice(-NOTIFICATIONS_KEPT).reverse()].slice(0, NOTIFICATIONS_KEPT) });
          return;
        }
        case "notification_push": {
          const n = json<BusNotification>(env.data);
          if (n?.id) putNote(n);
          return;
        }
        case "notification_dismiss": {
          const id = json<{ id?: string }>(env.data)?.id ?? env.data;
          set({ notifications: get().notifications.filter((n) => n.id !== id) });
          return;
        }
        case "error":
          if (env.requestId?.startsWith("attn:")) set({ refusal: env.data });
          return;
      }
    });
    let counter = 0;
    const frame = (type: string, data: string): boolean => {
      counter += 1;
      return link.sendFrame({ type, data, requestId: `attn:${counter.toString(36)}` });
    };
    return {
      items: {},
      questions: {},
      notifications: [],
      loaded: false,
      refusal: null,
      ensureLoaded: () => {
        if (get().loaded) return;
        frame("attention_list", "");
        frame("question_list", "");
        frame("notification_list", "");
      },
      ack: (id) => {
        const i = get().items[id];
        if (!i) return;
        set({ items: { ...get().items, [id]: { ...i, ackedAt: Date.now() } } });
        if (!frame("attention_ack", id)) set({ items: { ...get().items, [id]: i }, refusal: "not sent: the daemon link is down" });
      },
      answer: (questionId, answer, otherText) => {
        const body = { questionId, ...(answer ? { answer } : {}), ...(otherText?.trim() ? { otherText: otherText.trim() } : {}) };
        const ok = frame("question_answer", JSON.stringify(body));
        if (!ok) set({ refusal: "answer not sent: the daemon link is down" });
        return ok;
      },
      dismiss: (id) => {
        const local = get().notifications.some((n) => n.id === id && n.source === APP_SOURCE);
        set({ notifications: get().notifications.filter((n) => n.id !== id) });
        if (!local) frame("notification_dismiss", JSON.stringify({ id }));
      },
      post: (n) => putNote({ ...n, source: APP_SOURCE }),
      act: (notificationId, actionId) => {
        frame("notification_action", JSON.stringify({ notificationId, actionId, source: "in-app" }));
        set({ notifications: get().notifications.filter((n) => n.id !== notificationId) });
      },
      clearRefusal: () => set({ refusal: null }),
      dispose: off,
    };
  });
}

// ---- held notices (WAKE-1: a notice that did not wake a session lands here as kind `notice`) ------------------------------

/** The notice kind of a held item (`inbox:<kind>`). */
export const heldKind = (i: Pick<AttentionItem, "outcome">): string => i.outcome.replace(/^inbox:/, "");

export interface HeldFilter {
  kind?: string | null;
  session?: string | null;
  /** Include the ones already marked read. */
  read?: boolean;
}

/** The held notices (newest first), narrowed by kind and session; the read ones only when asked for. */
export function heldNotices(items: Record<string, AttentionItem>, f: HeldFilter = {}): AttentionItem[] {
  return Object.values(items)
    .filter((i) => i.kind === "notice" && (f.read || i.ackedAt == null) && (!f.kind || heldKind(i) === f.kind) && (!f.session || i.sessionId === f.session))
    .sort((a, b) => b.at - a.at);
}
