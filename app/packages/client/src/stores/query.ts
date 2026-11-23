// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// One request/answer store for the read-mostly panels (RUST-UI-7): a request frame answered by one frame type, an
// optional push that changes the data (or asks for a re-read), and a refusal kept as text. A daemon that serves no unit
// for the frame answers `<type> refused: no unit serves this frame`; that is the refusal the panel shows, never fake data.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";
import { requester, type FrameLink } from "./link";

export interface QueryState<T> {
  data: T;
  loaded: boolean;
  loading: boolean;
  refusal: string | null;
}

export interface QueryActions {
  /** Sends the request; [data] replaces the configured body for this call. */
  refresh(data?: string): void;
  /** Sends another frame of this panel (a write); its answer or refusal lands here like the request's. */
  send(type: string, data: string): boolean;
  clearRefusal(): void;
  dispose(): void;
}

export type QueryStore<T> = StoreApi<QueryState<T> & QueryActions>;

export interface QuerySpec<T> {
  /** Request-id prefix. */
  prefix: string;
  request: string;
  /** The body sent with no override. */
  body?: () => string;
  /** Frame types whose body is the whole new data. */
  answers: string[];
  parse: (raw: unknown, prev: T) => T | null;
  empty: T;
  /** Pushed frame types that fold into the data (the default folds through [parse] too). */
  pushes?: Record<string, (raw: unknown, prev: T) => T | null>;
  /** Pushed frame types that only mean "read again". */
  rereads?: string[];
  /** Error prefixes that belong to this panel even without a request id of ours. */
  own?: string[];
}

export function createQueryStore<T>(link: FrameLink, spec: QuerySpec<T>): QueryStore<T> {
  return createStore<QueryState<T> & QueryActions>()((set, get) => {
    const req = requester(link, spec.prefix, (why) => set({ refusal: why, loading: false }));
    let lastBody: string | undefined;
    const off = link.onFrame((env: Envelope) => {
      if (env.type === "ready") {
        if (get().loaded) get().refresh(lastBody);
        return;
      }
      if (spec.answers.includes(env.type)) {
        // Only answers to us: another client's list request answers the same type with its own filter.
        if (!req.settle(env)) return;
        const next = spec.parse(json<unknown>(env.data), get().data);
        set(next === null ? { loading: false, loaded: true, refusal: `${spec.request}: the daemon's answer was not understood` } : { data: next, loaded: true, loading: false, refusal: null });
        return;
      }
      const fold = spec.pushes?.[env.type];
      if (fold) {
        req.settle(env);
        const next = fold(json<unknown>(env.data), get().data);
        if (next !== null) set({ data: next });
        return;
      }
      if (spec.rereads?.includes(env.type)) {
        if (get().loaded) get().refresh(lastBody);
        return;
      }
      if (env.type === "error" && (req.settle(env) || (spec.own ?? []).some((p) => env.data.startsWith(p)))) set({ refusal: env.data, loading: false, loaded: true });
    });
    return {
      data: spec.empty,
      loaded: false,
      loading: false,
      refusal: null,
      refresh(data) {
        lastBody = data ?? lastBody;
        set({ loading: true });
        req.send(spec.request, lastBody ?? spec.body?.() ?? "");
      },
      send: (type, data) => req.send(type, data),
      clearRefusal: () => set({ refusal: null }),
      dispose: off,
    };
  });
}

/** True when a refusal says the daemon has no unit for the frame (not built yet), rather than a refused request. */
export function isUnserved(refusal: string | null): boolean {
  return !!refusal && /no unit serves this frame|unknown frame|not served/i.test(refusal);
}
