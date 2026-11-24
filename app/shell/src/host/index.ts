// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// HC-1: this window's host (@infantry/client [Host]): a window of BattalionOS (BOS-8), else the Tauri app (desktop or phone),
// else a plain browser. Components ask it for window buttons, app commands, dialogs and daemon files instead of calling a
// platform API themselves.
import { browserHost, type Host } from "@infantry/client";
import { battalionAppId, battalionHost } from "./battalion";
import { battalionFrameHost, frameAppId, installFrameDrag, windowLink } from "./battalion/frame";
import { tauriHost } from "./tauri";

/** Set when this page is drawn in a frame of the BattalionOS window (BOS-17): it calls the OS by messages. Being framed decides it (BOS-26). */
const frameApp = frameAppId();

/** Inside the Tauri app (desktop or phone). A page in an OS frame is never the Tauri window, even when Windows gives its frame the Tauri internals. */
export const isTauri = !frameApp && typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;

/** Set when this window is an app window of BattalionOS. */
const osApp = isTauri ? battalionAppId() : null;

// BOS-27: the drag areas of the page's own title bar start the window drag by message (the attribute does nothing in a frame).
if (frameApp) installFrameDrag(windowLink());

export const host: Host = frameApp ? battalionFrameHost(frameApp, windowLink()) : osApp ? battalionHost(osApp) : isTauri ? tauriHost() : browserHost();
