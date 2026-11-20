// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The desktop's window state (DESK-2), pure so it is tested without a screen. Each app is closed, open or minimized; at most one
// is in front. Minimizing hides an app but keeps it running (its state survives); closing ends it. Opening one app sends the
// one in front to the taskbar-to-be (minimized) rather than closing it.

export type AppId = "orchestrator" | "apps";
export type WindowState = "closed" | "open" | "minimized";

export interface DesktopState {
  windows: Record<AppId, WindowState>;
  /** The app drawn full screen, or null for the bare desktop. */
  front: AppId | null;
}

export type DesktopAction = { type: "open" | "minimize" | "close"; app: AppId };

export function initialState(openOrchestrator: boolean): DesktopState {
  return openOrchestrator
    ? { windows: { orchestrator: "open", apps: "closed" }, front: "orchestrator" }
    : { windows: { orchestrator: "closed", apps: "closed" }, front: null };
}

export function reduce(state: DesktopState, action: DesktopAction): DesktopState {
  const { app } = action;
  const windows = { ...state.windows };
  switch (action.type) {
    case "open": {
      if (state.front && state.front !== app) windows[state.front] = "minimized";
      windows[app] = "open";
      return { windows, front: app };
    }
    case "minimize": {
      if (windows[app] !== "open") return state;
      windows[app] = "minimized";
      return { windows, front: state.front === app ? null : state.front };
    }
    case "close": {
      windows[app] = "closed";
      return { windows, front: state.front === app ? null : state.front };
    }
  }
}
