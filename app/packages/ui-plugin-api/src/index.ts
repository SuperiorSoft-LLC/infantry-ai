// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// WEBUI-1: what a web UI plugin imports. The contract (types, slots, UI_API_VERSION), `definePlugin` for its default
// export, and the app state a plugin may read (`useLinkState`), taken from the app's host when it is used. `react`,
// `react-dom` and `@infantry/ui` (the UI kit, TabPage, Field, RefusalBanner and `ago` included) are imported as usual: the
// build helper turns those imports into the app's own copy (build/plugin-config.mjs).
import { createElement, useEffect, useState, type ReactElement } from "react";
import { HOST_GLOBAL, type AgentActivity, type FrameLink, type HostApi, type UiHost, type UiPluginModule, type ShellUpdater, type WindowFrame } from "./contract";
import { json as readJson } from "../../client/src/wire";
import { runnerRequest as request } from "../../client/src/runner-request";

export * from "./contract";
// The frame helpers every app store uses: a request with its own id, and a JSON read that never throws. Bundled (they are
// small and pure), so a plugin's store runs in a plain test with no app around it.
export { json, type Envelope } from "../../client/src/wire";
export { requester } from "../../client/src/stores/link";
// RR-14: the one helper that aims a frame at a runner (plain for `local`, `runner_frame` for any other); a plugin reuses it, never copies it.
export { runnerRequest, isLocalRunner } from "../../client/src/runner-request";

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

/** The shell's own updater (Tauri updater + release feed), or null in a browser or on a phone (ARMORY-3). */
/** BOS-8: the BattalionOS app id of this window, or null. Safe on a host that predates it. */
export const osApp = (): string | null => host().api.osApp?.() ?? null;
export const shellUpdater = (): ShellUpdater | null => lent("shellUpdater")();

/** RR-14: opens the app's setup wizard aimed at [runner] (Runners tab: "Set up this runner"). */
export const openRunnerSetup = (runner: string): void => lent("openRunnerSetup")(runner);

/** The app's pixel-bot avatar for an agent's session id. */
export function AgentAvatar(props: { agentId: string; size?: "xs" | "sm" }): ReactElement {
  return createElement(lent("AgentAvatar"), props);
}

/**
 * PLUGIN-AGENTS-1: opens [url] outside the app through the host seam (`host.openExternal`: the system browser on desktop
 * and BattalionOS, a new tab in a browser). A host that predates the seam lends no opener: nothing opens, and the app
 * webview is never navigated.
 */
export function openExternal(url: string): void {
  void host().api.openExternal?.(url).catch(() => undefined);
}

const cardImages = new Map<string, Promise<string | null>>();

/**
 * PLUGIN-AGENTS-1: an https image inside a chat card of [type], as a `data:` URL (null while it loads, or when refused).
 * The daemon fetches it through the card image proxy (`card_image`): only the hosts that type declares in module.json
 * `cards[].images.hosts` (plus the owner's `cards.images.allow`), raster images only, size-capped. One fetch per URL.
 */
export function useCardImage(link: FrameLink, type: string, url?: string | null): string | null {
  const [src, setSrc] = useState<string | null>(null);
  useEffect(() => {
    setSrc(null);
    if (!url || !/^https:\/\//i.test(url)) return;
    const key = `${type} ${url}`;
    let p = cardImages.get(key);
    if (!p) {
      p = request(link, null, "card_image", JSON.stringify({ type, url }), 30_000)
        .then((env) => {
          const a = readJson<{ ok?: boolean; mime?: string; base64?: string }>(env.data);
          return a?.ok && a.mime && a.base64 ? `data:${a.mime};base64,${a.base64}` : null;
        })
        .catch(() => null);
      cardImages.set(key, p);
    }
    let live = true;
    void p.then((v) => live && setSrc(v));
    return () => {
      live = false;
    };
  }, [link, type, url]);
  return src;
}
