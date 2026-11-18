// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The mail store: the owner's view of every mailbox, fed by `mail_inbox` "*" -> `mails` and the `mail` push (a message
// sent or changed). Threads are grouped here; reading a message sends `mail_read` (the owner's read never wakes an agent).
// The link is injected (the daemon store's sendFrame / onFrame), so the store is tested with recorded frames.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";

export const OWNER = "owner";

/** A message as the `mail` frame carries it (Message in infantry-mail; same bytes as mailbox.json). */
export interface MailMessage {
  id: string;
  threadId: string;
  seq: number;
  from: string;
  to: string;
  subject: string;
  body: string;
  sentAt: number;
  state: string;
  replyTo?: string | null;
  needsInput?: boolean;
  deliveredAt?: number | null;
  readAt?: number | null;
  answeredAt?: number | null;
  failureReason?: string | null;
}

export interface MailThread {
  threadId: string;
  subject: string;
  members: string[];
  messages: MailMessage[];
  last: MailMessage;
  unread: number;
}

export type MailFolder = "inbox" | "sent" | "all";

export interface MailLink {
  sendFrame(env: Envelope): boolean;
  onFrame(listener: (env: Envelope) => void): () => void;
}

export interface MailState {
  messages: Record<string, MailMessage>;
  loaded: boolean;
  refusal: string | null;
  folder: MailFolder;
  query: string;
}

export interface MailActions {
  ensureLoaded(): void;
  refresh(): void;
  /** Send as the owner. False when the link is down or the draft is incomplete. */
  send(to: string, subject: string, body: string, replyTo?: string): boolean;
  markRead(id: string): void;
  setFolder(folder: MailFolder): void;
  setQuery(query: string): void;
  clearRefusal(): void;
  dispose(): void;
}

export type MailStore = StoreApi<MailState & MailActions>;

export function isUnreadForOwner(m: MailMessage): boolean {
  return m.to === OWNER && !m.readAt && m.state !== "READ" && m.state !== "ANSWERED";
}

/** Threads of [folder], newest activity first, filtered by [query] (subject, body, member). */
export function threadsOf(messages: Record<string, MailMessage>, folder: MailFolder = "all", query = ""): MailThread[] {
  const by = new Map<string, MailMessage[]>();
  for (const m of Object.values(messages)) {
    const list = by.get(m.threadId) ?? [];
    list.push(m);
    by.set(m.threadId, list);
  }
  const q = query.trim().toLowerCase();
  const out: MailThread[] = [];
  for (const [threadId, list] of by) {
    list.sort((a, b) => a.seq - b.seq || a.sentAt - b.sentAt);
    if (folder === "inbox" && !list.some((m) => m.to === OWNER)) continue;
    if (folder === "sent" && !list.some((m) => m.from === OWNER)) continue;
    if (q && !list.some((m) => `${m.subject}\n${m.body}\n${m.from}\n${m.to}`.toLowerCase().includes(q))) continue;
    const last = list[list.length - 1];
    out.push({
      threadId,
      subject: list.find((m) => m.subject)?.subject ?? "(no subject)",
      members: [...new Set(list.flatMap((m) => [m.from, m.to]))],
      messages: list,
      last,
      unread: list.filter(isUnreadForOwner).length,
    });
  }
  return out.sort((a, b) => b.last.sentAt - a.last.sentAt);
}

export function unreadCount(messages: Record<string, MailMessage>): number {
  return Object.values(messages).filter(isUnreadForOwner).length;
}

export function createMailStore(link: MailLink): MailStore {
  return createStore<MailState & MailActions>()((set, get) => {
    const put = (list: MailMessage[]): void => {
      const next = { ...get().messages };
      for (const m of list) if (m?.id) next[m.id] = m;
      set({ messages: next });
    };
    const off = link.onFrame((env) => {
      switch (env.type) {
        case "mails": {
          const list = json<MailMessage[]>(env.data);
          if (Array.isArray(list)) {
            put(list);
            set({ loaded: true });
          }
          return;
        }
        case "mail": {
          const m = json<MailMessage>(env.data);
          if (m?.id) put([m]);
          return;
        }
        case "mail_refused": {
          const r = json<{ reason?: string }>(env.data);
          set({ refusal: r?.reason ?? "mail refused" });
          return;
        }
        case "error":
          if (env.requestId?.startsWith("mail:")) set({ refusal: env.data });
          return;
      }
    });
    let counter = 0;
    const frame = (type: string, data: string): boolean => {
      counter += 1;
      return link.sendFrame({ type, data, requestId: `mail:${counter.toString(36)}` });
    };
    return {
      messages: {},
      loaded: false,
      refusal: null,
      folder: "inbox",
      query: "",
      ensureLoaded: () => {
        if (!get().loaded) get().refresh();
      },
      refresh: () => {
        frame("mail_inbox", "*");
      },
      send: (to, subject, body, replyTo) => {
        if (!to.trim() || !body.trim()) {
          set({ refusal: "a recipient and a body are required" });
          return false;
        }
        const ok = frame("mail_send", JSON.stringify({ from: OWNER, to: to.trim(), subject: subject.trim(), body, ...(replyTo ? { replyTo } : {}) }));
        if (!ok) set({ refusal: "mail not sent: the daemon link is down" });
        return ok;
      },
      markRead: (id) => {
        const m = get().messages[id];
        if (!m || !isUnreadForOwner(m)) return;
        put([{ ...m, readAt: Date.now(), state: "READ" }]);
        frame("mail_read", id);
      },
      setFolder: (folder) => set({ folder }),
      setQuery: (query) => set({ query }),
      clearRefusal: () => set({ refusal: null }),
      dispose: off,
    };
  });
}
