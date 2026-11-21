// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The desktop's window state (DESK-2), pure so it is tested without a screen. Each app is closed, open or minimized; at most one
// is in front. Minimizing hides an app but keeps it running (its state survives); closing ends it. Opening one app sends the
// one in front to the taskbar-to-be (minimized) rather than closing it.
// DESK-5: an open app is maximized (fills the desktop work area, as an app always opened) or restored (a smaller window with the
// wallpaper around it). Minimizing and restoring from the taskbar keep that choice; closing the app forgets it, so the next
// launch opens full size again. These are the APP's window actions; the real window's belong to the Start menu.

export type AppId = "orchestrator" | "apps";
export type WindowState = "closed" | "open" | "minimized";

export interface DesktopState {
  windows: Record<AppId, WindowState>;
  /** The app drawn in front, or null for the bare desktop. */
  front: AppId | null;
  /** Whether each app's window fills the work area (true) or is the smaller restored window (false). */
  maximized: Record<AppId, boolean>;
}

export type DesktopAction = { type: "open" | "minimize" | "close" | "toggleMaximize"; app: AppId };

const FULL: Record<AppId, boolean> = { orchestrator: true, apps: true };

export function initialState(openOrchestrator: boolean): DesktopState {
  return openOrchestrator
    ? { windows: { orchestrator: "open", apps: "closed" }, front: "orchestrator", maximized: { ...FULL } }
    : { windows: { orchestrator: "closed", apps: "closed" }, front: null, maximized: { ...FULL } };
}

export function reduce(state: DesktopState, action: DesktopAction): DesktopState {
  const { app } = action;
  const windows = { ...state.windows };
  const maximized = { ...state.maximized };
  switch (action.type) {
    case "open": {
      if (state.front && state.front !== app) windows[state.front] = "minimized";
      windows[app] = "open";
      return { windows, front: app, maximized };
    }
    case "minimize": {
      if (windows[app] !== "open") return state;
      windows[app] = "minimized";
      return { windows, front: state.front === app ? null : state.front, maximized };
    }
    case "close": {
      windows[app] = "closed";
      maximized[app] = true;
      return { windows, front: state.front === app ? null : state.front, maximized };
    }
    case "toggleMaximize": {
      if (windows[app] !== "open") return state;
      maximized[app] = !maximized[app];
      return { windows, front: state.front, maximized };
    }
  }
}

/** What a taskbar entry does when clicked: restore a minimized app, minimize the one in front. */
export function toggle(state: DesktopState, app: AppId): DesktopAction {
  return { type: state.front === app ? "minimize" : "open", app };
}
