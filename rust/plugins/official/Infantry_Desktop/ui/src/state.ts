// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The desktop's window state (DESK-2), pure so it is tested without a screen. Each app is closed, open or minimized; at most one
// is in front. Minimizing hides an app but keeps it running (its state survives); closing ends it. Opening one app sends the
// one in front to the taskbar-to-be (minimized) rather than closing it.

/** BOS-24: under BattalionOS (the window is an OS app) the desktop lists no Apps app and offers no updates: the OS has its own. */
export const appsListed = <T extends { id: string }>(all: readonly T[], underOs: boolean): T[] => all.filter((a) => !(underOs && a.id === "apps"));

/** BOS-24: the updates the menu shows; none under BattalionOS, where the OS updates apps. */
export const updatesShown = <T>(all: readonly T[], underOs: boolean): T[] => (underOs ? [] : [...all]);

export type AppId = "orchestrator" | "apps";
export type WindowState = "closed" | "open" | "minimized";

export interface DesktopState {
  windows: Record<AppId, WindowState>;
  /** The app drawn full screen, or null for the bare desktop. */
  front: AppId | null;
  /** Where an app was last opened to (ARMORY-2: an update opens Apps on that app's page); absent = the app's own start page. */
  routes: Partial<Record<AppId, string>>;
}

/** `open` may carry a [route]: the page inside the app to show (the app is told through its view's `route` prop). */
export type DesktopAction = { type: "open"; app: AppId; route?: string } | { type: "minimize" | "close"; app: AppId };

export function initialState(openOrchestrator: boolean): DesktopState {
  return openOrchestrator
    ? { windows: { orchestrator: "open", apps: "closed" }, front: "orchestrator", routes: {} }
    : { windows: { orchestrator: "closed", apps: "closed" }, front: null, routes: {} };
}

export function reduce(state: DesktopState, action: DesktopAction): DesktopState {
  const { app } = action;
  const windows = { ...state.windows };
  switch (action.type) {
    case "open": {
      if (state.front && state.front !== app) windows[state.front] = "minimized";
      windows[app] = "open";
      // A plain open (the Start menu, a taskbar restore) keeps the page the app is on; an open with a route moves it there.
      const routes = action.route === undefined ? state.routes : { ...state.routes, [app]: action.route };
      return { windows, front: app, routes };
    }
    case "minimize": {
      if (windows[app] !== "open") return state;
      windows[app] = "minimized";
      return { windows, front: state.front === app ? null : state.front, routes: state.routes };
    }
    case "close": {
      windows[app] = "closed";
      const { [app]: _gone, ...routes } = state.routes;
      return { windows, front: state.front === app ? null : state.front, routes };
    }
  }
}

/** What a taskbar entry does when clicked: restore a minimized app, minimize the one in front. */
export function toggle(state: DesktopState, app: AppId): DesktopAction {
  return { type: state.front === app ? "minimize" : "open", app };
}
