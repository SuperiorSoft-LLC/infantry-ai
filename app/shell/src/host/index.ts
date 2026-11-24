// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// HC-1: this window's host (@infantry/client [Host]): a window of BattalionOS (BOS-8), else the Tauri app (desktop or phone),
// else a plain browser. Components ask it for window buttons, app commands, dialogs and daemon files instead of calling a
// platform API themselves.
import { browserHost, type Host } from "@infantry/client";
import { battalionAppId, battalionHost } from "./battalion";
import { battalionFrameHost, frameAppId, windowLink } from "./battalion/frame";
import { tauriHost } from "./tauri";

/** Inside the Tauri app (desktop or phone). */
export const isTauri = typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;

/** Set when this window is an app window of BattalionOS. */
const osApp = isTauri ? battalionAppId() : null;

/** Set when this page is drawn in a frame of the BattalionOS window (BOS-17): it calls the OS by messages. */
const frameApp = osApp ? null : frameAppId();

export const host: Host = osApp ? battalionHost(osApp) : frameApp ? battalionFrameHost(frameApp, windowLink()) : isTauri ? tauriHost() : browserHost();
