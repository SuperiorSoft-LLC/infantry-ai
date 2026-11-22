// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The owner-question store: open `prompt_request` frames (a harness question or a tool approval) by session, removed on
// `prompt_resolved`, answered with `prompt_response`. The card stays if the link is down. The link is injected, so the
// store is tested with recorded frames and no socket.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";

export interface PromptOption {
  label: string;
  description?: string;
}

/** An open prompt, as `prompt_request` carries it (PromptRequest in infantry-protocol). */
export interface OpenPrompt {
  promptId: string;
  sessionId: string;
  kind: string;
  createdAt: number;
  tool: string | null;
  input: string | null;
  question: string | null;
  options: PromptOption[];
  multiSelect: boolean;
  allowOther: boolean;
  expiresAt: number | null;
  recommended: string | null;
}

export interface PromptsLink {
  sendFrame(env: Envelope): boolean;
  onFrame(listener: (env: Envelope) => void): () => void;
}

export interface PromptsState {
  open: Record<string, OpenPrompt>;
}

export interface PromptsActions {
  /** Answer a question: chosen option labels and/or free text. False (card kept) when the link is down. */
  answer(promptId: string, answers: string[], otherText?: string): boolean;
  /** Answer a tool approval. */
  decide(promptId: string, decision: string): boolean;
  dispose(): void;
}

export type PromptsStore = StoreApi<PromptsState & PromptsActions>;

export function promptsOf(open: Record<string, OpenPrompt>, sessionId: string): OpenPrompt[] {
  return Object.values(open)
    .filter((p) => p.sessionId === sessionId)
    .sort((a, b) => a.createdAt - b.createdAt || a.promptId.localeCompare(b.promptId));
}

export function createPromptsStore(link: PromptsLink): PromptsStore {
  return createStore<PromptsState & PromptsActions>()((set, get) => {
    const drop = (id: string): void => {
      const rest = { ...get().open };
      delete rest[id];
      set({ open: rest });
    };
    const respond = (promptId: string, body: object): boolean => {
      const p = get().open[promptId];
      if (!p) return false;
      const ok = link.sendFrame({ type: "prompt_response", sessionId: p.sessionId, data: JSON.stringify({ promptId, ...body }) });
      if (ok) drop(promptId);
      return ok;
    };
    const off = link.onFrame((env) => {
      if (env.type === "prompt_request") {
        const r = json<Record<string, unknown>>(env.data);
        if (!r || typeof r.promptId !== "string") return;
        // NEEDS-INPUT-1: a card routed to the PM (`route` set) is the PM's to answer, not the owner's: no card here. An
        // escalation sends it again with no route, and then it is drawn.
        if (typeof r.route === "string" && r.route) {
          if (get().open[r.promptId]) drop(r.promptId);
          return;
        }
        const p: OpenPrompt = {
          promptId: r.promptId,
          sessionId: String(r.sessionId ?? env.sessionId ?? ""),
          kind: String(r.kind ?? "question"),
          createdAt: Number(r.createdAt ?? 0),
          tool: (r.tool as string | null) ?? null,
          input: (r.input as string | null) ?? null,
          question: (r.question as string | null) ?? null,
          options: Array.isArray(r.options) ? (r.options as PromptOption[]) : [],
          multiSelect: r.multiSelect === true,
          allowOther: r.allowOther === true,
          expiresAt: (r.expiresAt as number | null) ?? null,
          recommended: (r.recommended as string | null) ?? null,
        };
        set({ open: { ...get().open, [p.promptId]: p } });
      } else if (env.type === "prompt_resolved") {
        const r = json<{ promptId?: string }>(env.data);
        if (r?.promptId && get().open[r.promptId]) drop(r.promptId);
      }
    });
    return {
      open: {},
      answer: (promptId, answers, otherText) => respond(promptId, { answers, ...(otherText?.trim() ? { otherText: otherText.trim() } : {}) }),
      decide: (promptId, decision) => respond(promptId, { decision }),
      dispose: off,
    };
  });
}
