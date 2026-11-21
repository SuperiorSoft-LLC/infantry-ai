// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The secrets store: the daemon vault's `secret_state` (names, fields and masked tails; never a value). Changes go out as
// `secret_field_set` / `secret_field_delete` / `secret_delete` / `secret_set` / `secret_clear` and come back as a pushed
// `secret_state`. Reveal and copy (`secret_reveal`) ask the daemon, which asks Windows Hello first; the value comes back
// in the promise ONLY and is never kept in the store. Backup export / import are request/answer too.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";

export interface SecretFieldRow {
  name: string;
  tail: string;
}

/** A user secret; `project` absent = the app scope. */
export interface SecretEntryRow {
  name: string;
  fields: SecretFieldRow[];
  project?: string;
}

/** A built-in single-value secret (the update token, harness keys). */
export interface SecretRow {
  name: string;
  label: string;
  set: boolean;
  tail: string;
  importable: boolean;
  overriddenByEnv: boolean;
  help?: string;
  /** The harness has an in-app sign-in on this row (a token flow: the browser opens, the token comes back). */
  canLogin?: boolean;
  /** "true" | "false" | "unknown": the harness can answer now. */
  signedIn?: string;
  /** "" | running | done | failed. */
  loginState?: string;
  loginUrl?: string;
  /** One short line for the owner; never a token. */
  loginMessage?: string;
  /** 0 = the daemon never probed this sign-in. */
  lastProbe?: number;
  /** The shared secret this row inherits (a non-empty name counts as set). */
  usesSecret?: string;
  authModes?: string[];
  authMode?: string;
  loginCommand?: string;
}

export interface SecretRevealed {
  name: string;
  field: string;
  project?: string | null;
  value: string;
  method: string;
  showSeconds: number;
  clearSeconds: number;
}

export interface BackupImported {
  applied: boolean;
  added: string[];
  replaced: string[];
  kept: string[];
  same: string[];
  conflicts: string[];
}

export interface VerifyCheck {
  label: string;
  available: boolean;
  method: string;
  detail: string;
}

export interface SecretsLink {
  sendFrame(env: Envelope): boolean;
  onFrame(listener: (env: Envelope) => void): () => void;
}

export interface SecretsState {
  protection: string;
  builtIns: SecretRow[];
  entries: SecretEntryRow[];
  locked: boolean;
  loaded: boolean;
  refusal: string | null;
  verify: VerifyCheck | null;
}

export interface SecretsActions {
  ensureLoaded(): void;
  /** Asks the daemon for the rows again (a sign-in or an install changed them). */
  refresh(): void;
  setField(name: string, field: string, value: string, project?: string): Promise<void>;
  deleteField(name: string, field: string, project?: string): Promise<void>;
  deleteSecret(name: string, project?: string): Promise<void>;
  setBuiltIn(name: string, value: string): Promise<void>;
  clearBuiltIn(name: string): Promise<void>;
  /** start / input / cancel the in-app sign-in on a built-in row (`input` carries the pasted token or code; it is never kept here). */
  login(name: string, action: "start" | "input" | "cancel", text?: string): Promise<void>;
  /** Asks the daemon for one value after the OS check. Resolves with it; the caller shows it briefly, then drops it. */
  reveal(name: string, field: string, purpose: "reveal" | "copy", project?: string): Promise<SecretRevealed>;
  exportBackup(path: string, passphrase: string): Promise<{ path: string; secrets: number; method: string }>;
  importBackup(path: string, passphrase: string, resolutions?: Record<string, "keep" | "replace">): Promise<BackupImported>;
  checkVerifier(): void;
  clearRefusal(): void;
  dispose(): void;
}

export type SecretsStore = StoreApi<SecretsState & SecretsActions>;

/** The key a UI uses for one secret: `name` or `project/name`. */
export const secretId = (e: { name: string; project?: string | null }): string => (e.project ? `${e.project}/${e.name}` : e.name);

/** The placeholder an agent or a template uses for this field. */
export function placeholderOf(e: SecretEntryRow, field: string): string {
  return e.fields.length <= 1 ? `{{secret:${e.name}}}` : `{{secret:${e.name}#${field}}}`;
}

const ANSWERS = new Set(["secret_state", "secret_reveal", "secret_backup_export", "secret_backup_import", "error"]);

export function createSecretsStore(link: SecretsLink, timeoutMs = 180_000): SecretsStore {
  let counter = 0;
  const waiting = new Map<string, { resolve: (e: Envelope) => void; reject: (why: string) => void; timer: ReturnType<typeof setTimeout> }>();

  return createStore<SecretsState & SecretsActions>()((set, get) => {
    const applyState = (data: string): void => {
      const p = json<{ protection?: string; secrets?: SecretRow[]; entries?: SecretEntryRow[]; locked?: boolean }>(data);
      if (p) set({ protection: p.protection ?? "", builtIns: p.secrets ?? [], entries: p.entries ?? [], locked: !!p.locked, loaded: true });
    };

    /** Sends one frame and waits for its answer (matched on requestId). A refusal rejects with the daemon's text. */
    const request = (type: string, body: unknown, timeout = timeoutMs): Promise<Envelope> =>
      new Promise((resolve, reject) => {
        counter += 1;
        const requestId = `secret:${Date.now().toString(36)}-${counter.toString(36)}`;
        const timer = setTimeout(() => {
          waiting.delete(requestId);
          reject(`${type} got no answer`);
        }, timeout);
        waiting.set(requestId, { resolve, reject, timer });
        const data = typeof body === "string" ? body : JSON.stringify(body);
        if (!link.sendFrame({ type, data, requestId })) {
          clearTimeout(timer);
          waiting.delete(requestId);
          reject(`${type} not sent: the daemon link is down`);
        }
      });

    const change = async (type: string, body: unknown): Promise<void> => {
      try {
        const e = await request(type, body, 30_000);
        if (e.type === "secret_state") applyState(e.data);
      } catch (why) {
        set({ refusal: String(why) });
        throw why;
      }
    };

    const off = link.onFrame((env) => {
      if (env.requestId && waiting.has(env.requestId) && ANSWERS.has(env.type)) {
        const w = waiting.get(env.requestId)!;
        waiting.delete(env.requestId);
        clearTimeout(w.timer);
        if (env.type === "error") w.reject(env.data);
        else w.resolve(env);
        return;
      }
      switch (env.type) {
        case "ready":
          link.sendFrame({ type: "secret_status", data: "" });
          return;
        case "secret_state":
          applyState(env.data);
          return;
        case "secret_verify_check": {
          const v = json<VerifyCheck>(env.data);
          if (v) set({ verify: v });
        }
      }
    });

    return {
      protection: "",
      builtIns: [],
      entries: [],
      locked: false,
      loaded: false,
      refusal: null,
      verify: null,

      ensureLoaded() {
        if (!get().loaded) link.sendFrame({ type: "secret_status", data: "" });
      },
      refresh() {
        link.sendFrame({ type: "secret_status", data: "" });
      },
      setField: (name, field, value, project) => change("secret_field_set", { name, field, value, ...(project ? { project } : {}) }),
      deleteField: (name, field, project) => change("secret_field_delete", { name, field, ...(project ? { project } : {}) }),
      deleteSecret: (name, project) => change("secret_delete", { name, ...(project ? { project } : {}) }),
      setBuiltIn: (name, value) => change("secret_set", { name, value }),
      clearBuiltIn: (name) => change("secret_clear", { name }),
      login: (name, action, text = "") => change("secret_login", { name, action, text }),
      async reveal(name, field, purpose, project) {
        const e = await request("secret_reveal", { name, field, purpose, ...(project ? { project } : {}) });
        const r = json<SecretRevealed>(e.data);
        if (!r) throw "secret_reveal: unreadable answer";
        return r;
      },
      async exportBackup(path, passphrase) {
        const e = await request("secret_backup_export", { path, passphrase });
        return json<{ path: string; secrets: number; method: string }>(e.data) ?? { path, secrets: 0, method: "" };
      },
      async importBackup(path, passphrase, resolutions = {}) {
        const e = await request("secret_backup_import", { path, passphrase, resolutions });
        return json<BackupImported>(e.data) ?? { applied: false, added: [], replaced: [], kept: [], same: [], conflicts: [] };
      },
      checkVerifier() {
        link.sendFrame({ type: "secret_verify_check", data: "" });
      },
      clearRefusal() {
        set({ refusal: null });
      },
      dispose() {
        off();
        waiting.forEach((w) => clearTimeout(w.timer));
        waiting.clear();
      },
    };
  });
}
