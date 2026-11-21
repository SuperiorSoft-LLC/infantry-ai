// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Assistant box asks the daemon for the installation-wide Assistant (frame `assistant`). The daemon answers by pushing the
// session itself (a `session` frame with `global: true`, which the session store takes in like any other, see `assistantOf`),
// or by refusing (`assistant refused: ...`). This store only keeps the refusal, so the box can say why it stays empty
// instead of waiting for ever; the box never starts a session of its own.
import { createStore, type StoreApi } from "zustand/vanilla";
import { Frame } from "../wire";
import { requester, type FrameLink } from "./link";

export interface AssistantAskState {
  /** The daemon's reason for the last refused ask, or null (no ask yet, one in flight, or answered). */
  refusal: string | null;
}

export interface AssistantAskActions {
  /** Asks for the Assistant; clears the last refusal first. */
  ask(): void;
  dispose(): void;
}

export type AssistantAskStore = StoreApi<AssistantAskState & AssistantAskActions>;

export function createAssistantAskStore(link: FrameLink): AssistantAskStore {
  return createStore<AssistantAskState & AssistantAskActions>()((set) => {
    const req = requester(link, "assistant", (why) => set({ refusal: why }));
    const off = link.onFrame((env) => {
      if (env.type === Frame.ERROR && req.settle(env)) set({ refusal: env.data });
    });
    return {
      refusal: null,
      ask() {
        set({ refusal: null });
        req.send(Frame.ASSISTANT, "");
      },
      dispose() {
        off();
      },
    };
  });
}
