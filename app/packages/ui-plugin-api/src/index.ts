// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// WEBUI-1: what a web UI plugin imports. The contract (types, slots, UI_API_VERSION), `definePlugin` for its default
// export, and the app state a plugin may read (`useLinkState`), taken from the app's host when it is used. `react`,
// `react-dom` and `@infantry/ui` (the UI kit, TabPage, Field, RefusalBanner and `ago` included) are imported as usual: the
// build helper turns those imports into the app's own copy (build/plugin-config.mjs).
import { createElement, type ReactElement } from "react";
import { HOST_GLOBAL, type AgentActivity, type HostApi, type UiHost, type UiPluginModule, type WindowFrame } from "./contract";

export * from "./contract";
// The frame helpers every app store uses: a request with its own id, and a JSON read that never throws. Bundled (they are
// small and pure), so a plugin's store runs in a plain test with no app around it.
export { json, type Envelope } from "../../client/src/wire";
export { requester } from "../../client/src/stores/link";

function host(): UiHost {
  const h = (globalThis as Record<string, unknown>)[HOST_GLOBAL] as UiHost | undefined;
  if (!h) throw new Error("@infantry/ui-plugin-api: no Infantry AI app on this page (a web UI plugin runs inside the app)");
  return h;
}

const lent = <K extends keyof HostApi>(k: K): HostApi[K] => host().api[k];

/** A plugin module's default export, typed: `export default definePlugin({ apiVersion: UI_API_VERSION, activate(ctx) {...} })`. */
export function definePlugin(m: UiPluginModule): UiPluginModule {
  return m;
}

/** The daemon link's state (`open`, `connecting`, `closed`...); the component re-renders when it changes. */
export const useLinkState = (): string => lent("useLinkState")();

/** A per-device preference (see [HostApi.usePreference]); `[value, set]`, re-rendering when it changes. */
export const usePreference = <T extends string | boolean>(key: string, fallback: T): readonly [T, (next: T) => void] =>
  lent("usePreference")(key, fallback);

/** The agents that are busy right now (DESK-3, a taskbar badge): how many work, how many need the user, and which. */
export const useAgentActivity = (): AgentActivity => lent("useAgentActivity")();

/** The real window's frame (minimize, maximize, close), or null in a browser or on a phone (DESK-5: a layout's Start menu). */
export const windowFrame = (): WindowFrame | null => lent("windowFrame")();

/** The app's pixel-bot avatar for an agent's session id. */
export function AgentAvatar(props: { agentId: string; size?: "xs" | "sm" }): ReactElement {
  return createElement(lent("AgentAvatar"), props);
}
