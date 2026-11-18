// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RUST-PERF-1: the embedded runner's transport. The Tauri backend (src-tauri/src/local.rs) runs the agent CLIs itself and
// speaks the daemon's envelope frames over Tauri IPC: `local_connect` hands it a channel for every outbound frame,
// `local_send` takes one inbound frame. The store, the reducer and the components are the same as against a daemon.
import { Channel, invoke } from "@tauri-apps/api/core";
import type { TransportFactory } from "@infantry/client";

export function localTransport(): TransportFactory {
  return () => {
    let open = false;
    return {
      open(h) {
        const ch = new Channel<string>();
        ch.onmessage = (text) => open && h.onText(text);
        open = true;
        // Open first: the backend's `ready` burst may arrive before the invoke resolves, and the store answers it.
        h.onOpen();
        invoke("local_connect", { channel: ch })
          .catch((e) => {
            open = false;
            h.onClose(e instanceof Error ? e.message : String(e));
          });
      },
      send(text) {
        void invoke("local_send", { text }).catch(() => {});
      },
      close() {
        open = false;
      },
    };
  };
}

export interface ResumableSession {
  id: string;
  modified: number;
  bytes: number;
  title: string;
  running: boolean;
}

export interface LocalProject {
  id: string;
  name: string;
  directory: string;
}

export const localSetProject = (folder: string) => invoke<LocalProject>("local_set_project", { folder });
export const localResumable = (folder: string) => invoke<ResumableSession[]>("local_resumable", { folder });
